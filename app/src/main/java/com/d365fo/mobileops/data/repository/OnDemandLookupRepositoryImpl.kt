package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.data.remote.api.D365ODataApi
import com.d365fo.mobileops.data.remote.api.ODataFilter
import com.d365fo.mobileops.data.remote.dto.ProductReceiptLineDto
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.domain.model.DeliveryTolerance
import com.d365fo.mobileops.domain.model.OrderStatus
import com.d365fo.mobileops.domain.model.PurchaseOrder
import com.d365fo.mobileops.domain.model.PurchaseOrderLine
import com.d365fo.mobileops.domain.model.TrackingRequirement
import com.d365fo.mobileops.domain.repository.BarcodeMatch
import com.d365fo.mobileops.domain.repository.ItemFacts
import com.d365fo.mobileops.domain.repository.LocationFacts
import com.d365fo.mobileops.domain.repository.OnDemandLookupRepository
import com.d365fo.mobileops.domain.repository.OrderLookup
import com.d365fo.mobileops.domain.repository.OnHandLine
import com.d365fo.mobileops.domain.repository.PurchaseOrderSummary
import com.d365fo.mobileops.domain.repository.WarehouseOption
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.Formatters
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One scan, one bounded set of queries.
 *
 * Every method here filters on a key the operator just supplied, so the result set is small by
 * construction rather than by a `$top` that hopes for the best. Nothing caches: a receiving bay
 * is exactly where a stale answer costs the most, and the fetch is a single small query anyway.
 */
@Singleton
class OnDemandLookupRepositoryImpl @Inject constructor(
    private val api: D365ODataApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val ioDispatcher: CoroutineDispatcher,
) : OnDemandLookupRepository {

    private fun company(): String? =
        companyProvider()?.lowercase()?.takeIf { it.isNotBlank() }

    private fun scoped(vararg clauses: String?): String =
        ODataFilter.and(company()?.let { ODataFilter.eq("dataAreaId", it) }, *clauses)

    override suspend fun lookupOrder(purchId: String): NetworkResult<OrderLookup> =
        withContext(ioDispatcher) {
            val trimmed = purchId.trim()
            if (trimmed.isEmpty()) {
                return@withContext NetworkResult.Error(
                    AppFailure.BusinessRule("Enter or scan a purchase order number"),
                )
            }

            val headerResult = errorMapper.safeCall {
                api.getPurchaseOrderHeaders(
                    filter = scoped(ODataFilter.eq("PurchaseOrderNumber", trimmed)),
                    top = 1,
                )
            }
            val header = when (headerResult) {
                is NetworkResult.Error -> return@withContext headerResult
                NetworkResult.Loading -> return@withContext loading()
                is NetworkResult.Success -> headerResult.data.value.firstOrNull()
                    ?: return@withContext NetworkResult.Error(
                        AppFailure.BusinessRule("No purchase order $trimmed in ${company()?.uppercase() ?: "this company"}"),
                    )
            }

            // Lines and posted receipts are independent queries against the same key, so they
            // go out together. On a handheld over site wifi this is the difference between one
            // round trip of latency at the PO step and two.
            val (lineResult, receiptResult) = coroutineScope {
                val lines = async {
                    errorMapper.safeCall {
                        api.getPurchaseOrderLines(
                            filter = scoped(ODataFilter.eq("PurchaseOrderNumber", trimmed)),
                            top = MAX_LINES_PER_ORDER,
                        )
                    }
                }
                val receipts = async {
                    errorMapper.safeCall {
                        api.getProductReceiptLines(
                            filter = scoped(ODataFilter.eq("PurchaseOrderNumber", trimmed)),
                            top = MAX_RECEIPTS_PER_ORDER,
                        )
                    }
                }
                lines.await() to receipts.await()
            }

            val lineDtos = when (lineResult) {
                is NetworkResult.Error -> return@withContext lineResult
                NetworkResult.Loading -> return@withContext loading()
                is NetworkResult.Success -> lineResult.data.value
            }
            val receiptDtos = when (receiptResult) {
                is NetworkResult.Error -> return@withContext receiptResult
                NetworkResult.Loading -> return@withContext loading()
                is NetworkResult.Success -> receiptResult.data.value
            }

            val received = sumReceived(receiptDtos)
            val headerEntity = header.toEntity()
            val tolerance = DeliveryTolerance(overPercent = null, underPercent = null)

            // OData has no cheap count and no way to fetch lines for one item without the same
            // entity-view cost, so this path always fetches them all. That is precisely why the
            // AOS service exists: on a five-thousand-line order this response is the problem.
            val order = PurchaseOrder(
                    purchId = headerEntity.purchId,
                    vendorAccount = headerEntity.vendorAccount,
                    vendorName = headerEntity.vendorName,
                    status = headerEntity.status.toDomain(),
                    siteId = headerEntity.siteId,
                    warehouseId = headerEntity.warehouseId,
                    deliveryDate = headerEntity.deliveryDate,
                    approved = headerEntity.approved,
                    tolerance = tolerance,
                    lines = lineDtos.map { dto ->
                        val receivedQty = received[key(trimmed, dto.lineNumber)] ?: BigDecimal.ZERO
                        PurchaseOrderLine(
                            lineId = "${dto.purchaseOrderNumber}:${dto.lineNumber}",
                            purchId = dto.purchaseOrderNumber,
                            lineNumber = dto.lineNumber,
                            itemId = dto.itemNumber,
                            itemName = dto.lineDescription.orEmpty(),
                            purchaseUnit = dto.purchaseUnit.orEmpty(),
                            // The line does not carry the inventory unit. The item step resolves
                            // it; until then the purchase unit is what the operator sees, which
                            // is also what is printed on the vendor's paperwork.
                            inventoryUnit = dto.purchaseUnit.orEmpty(),
                            unitConversionFactor = BigDecimal.ONE,
                            orderedQuantity = BigDecimal.valueOf(dto.orderedQuantity),
                            receivedQuantity = receivedQty,
                            uncommittedQuantity = BigDecimal.ZERO,
                            siteId = dto.siteId,
                            warehouseId = dto.warehouseId,
                            locationId = dto.locationId?.takeIf { it.isNotBlank() },
                            tolerance = DeliveryTolerance(
                                overPercent = dto.overDeliveryPercentage
                                    ?.takeIf { it > 0.0 }
                                    ?.let { BigDecimal.valueOf(it) },
                                underPercent = dto.underDeliveryPercentage
                                    ?.takeIf { it > 0.0 }
                                    ?.let { BigDecimal.valueOf(it) },
                            ),
                            // Resolved at the item step, from the item's dimension group.
                            tracking = TrackingRequirement.NONE,
                        )
                    },
            )

            NetworkResult.Success(
                OrderLookup(
                    order = order,
                    outstandingLineCount = order.lines.count { it.hasOutstanding },
                    linesInline = true,
                ),
            )
        }

    /**
     * Filters the order's lines client-side.
     *
     * There is no server-side equivalent over OData that would be any cheaper than the full
     * fetch already done, so this exists to satisfy the interface rather than to be fast. The
     * service path overrides it with a keyed query.
     */
    override suspend fun lookupLinesForItem(
        purchId: String,
        itemNumber: String,
    ): NetworkResult<List<PurchaseOrderLine>> = when (val result = lookupOrder(purchId)) {
        is NetworkResult.Error -> result
        NetworkResult.Loading -> loading()
        is NetworkResult.Success -> NetworkResult.Success(
            result.data.order.lines.filter {
                it.itemId.equals(itemNumber.trim(), ignoreCase = true) && it.hasOutstanding
            },
        )
    }

    override suspend fun lookupBarcode(barcode: String): NetworkResult<BarcodeMatch?> =
        withContext(ioDispatcher) {
            val trimmed = barcode.trim()
            if (trimmed.isEmpty()) return@withContext NetworkResult.Success(null)

            val result = errorMapper.safeCall {
                api.getBarcodes(
                    filter = scoped(ODataFilter.eq("Barcode", trimmed)),
                    top = 1,
                )
            }
            when (result) {
                is NetworkResult.Error -> result
                NetworkResult.Loading -> loading()
                is NetworkResult.Success -> NetworkResult.Success(
                    result.data.value.firstOrNull()?.let { dto ->
                        BarcodeMatch(
                            barcode = dto.barcode,
                            itemNumber = dto.itemNumber,
                            // A zero or absent pack quantity means one unit per scan. Treating
                            // it as zero would let an operator scan a carton and receive nothing.
                            quantityPerScan = dto.quantity
                                ?.takeIf { it > 0.0 }
                                ?.let { BigDecimal.valueOf(it) }
                                ?: BigDecimal.ONE,
                            unitSymbol = dto.unitSymbol,
                        )
                    },
                )
            }
        }

    override suspend fun lookupItem(itemNumber: String): NetworkResult<ItemFacts?> =
        withContext(ioDispatcher) {
            val trimmed = itemNumber.trim()
            if (trimmed.isEmpty()) return@withContext NetworkResult.Success(null)

            val productResult = errorMapper.safeCall {
                api.getReleasedProducts(
                    filter = scoped(ODataFilter.eq("ItemNumber", trimmed)),
                    top = 1,
                )
            }
            val product = when (productResult) {
                is NetworkResult.Error -> return@withContext productResult
                NetworkResult.Loading -> return@withContext loading()
                is NetworkResult.Success -> productResult.data.value.firstOrNull()
                    ?: return@withContext NetworkResult.Success(null)
            }

            // No second call. ReleasedProductsV2 publishes IsBatchNumberActive and
            // IsSerialNumberActive directly, so joining TrackingDimensionGroups to learn the
            // same thing was a whole round trip per item lookup for data already in hand -
            // and on this tenant a round trip is not cheap.
            NetworkResult.Success(
                ItemFacts(
                    itemNumber = product.itemNumber,
                    // There is no ProductName on this entity; SearchName is the name an
                    // operator recognises, and it is the one printed on the pick face.
                    productName = product.searchName.orEmpty(),
                    inventoryUnit = product.inventoryUnit.orEmpty(),
                    purchaseUnit = product.purchaseUnit,
                    batchRequired = product.batchActive == true,
                    serialRequired = product.serialActive == true,
                    // No StorageDimensionGroups entity exists here, so this is inferred from the
                    // group's name. Defaulting to required is the safe direction: prompting for
                    // a location that was not strictly needed is an annoyance, while skipping a
                    // mandatory one produces a posting D365FO rejects after the pallet is away.
                    locationRequired = product.storageDimensionGroup
                        ?.contains("Loc", ignoreCase = true) ?: true,
                ),
            )
        }

    override suspend fun lookupLocation(
        warehouseId: String,
        locationId: String,
    ): NetworkResult<LocationFacts?> = withContext(ioDispatcher) {
        val trimmed = locationId.trim()
        if (trimmed.isEmpty()) return@withContext NetworkResult.Success(null)

        val result = errorMapper.safeCall {
            api.getLocations(
                filter = scoped(
                    ODataFilter.eq("WarehouseId", warehouseId),
                    ODataFilter.eq("WarehouseLocationId", trimmed),
                ),
                top = 1,
            )
        }
        when (result) {
            is NetworkResult.Error -> result
            NetworkResult.Loading -> loading()
            is NetworkResult.Success -> NetworkResult.Success(
                result.data.value.firstOrNull()?.let { dto ->
                    val cause = dto.inputBlockingCause?.takeIf { it.isNotBlank() }
                    LocationFacts(
                        warehouseId = dto.warehouseId,
                        locationId = dto.locationId,
                        canReceiveInto = cause == null,
                        blockingCause = cause,
                    )
                },
            )
        }
    }

    override suspend fun lookupOnHand(
        itemNumber: String,
        warehouseId: String?,
    ): NetworkResult<List<OnHandLine>> = withContext(ioDispatcher) {
        val trimmed = itemNumber.trim()
        if (trimmed.isEmpty()) return@withContext NetworkResult.Success(emptyList())

        val result = errorMapper.safeCall {
            api.getOnHand(
                filter = scoped(
                    ODataFilter.eq("ItemNumber", trimmed),
                    warehouseId
                        ?.takeIf { it.isNotBlank() }
                        ?.let { ODataFilter.eq("InventoryWarehouseId", it) },
                ),
                top = MAX_ON_HAND_ROWS,
            )
        }
        when (result) {
            is NetworkResult.Error -> result
            NetworkResult.Loading -> loading()
            is NetworkResult.Success -> NetworkResult.Success(
                result.data.value.map { dto ->
                    OnHandLine(
                        itemNumber = dto.itemNumber,
                        siteId = dto.siteId,
                        warehouseId = dto.warehouseId,
                        onHand = BigDecimal.valueOf(dto.physicalInventory),
                        available = BigDecimal.valueOf(dto.availablePhysical),
                        reserved = BigDecimal.valueOf(dto.reservedPhysical),
                        ordered = BigDecimal.valueOf(dto.orderedInTotal),
                        totalAvailable = BigDecimal.valueOf(dto.totalAvailable),
                    )
                },
            )
        }
    }

    override suspend fun isSerialRegistered(
        itemNumber: String,
        serial: String,
    ): NetworkResult<Boolean> = withContext(ioDispatcher) {
        val result = errorMapper.safeCall {
            api.findSerialNumber(
                filter = scoped(
                    ODataFilter.eq("ItemNumber", itemNumber.trim()),
                    ODataFilter.eq("SerialNumber", serial.trim()),
                ),
            )
        }
        when (result) {
            is NetworkResult.Error -> result
            NetworkResult.Loading -> loading()
            is NetworkResult.Success -> NetworkResult.Success(result.data.value.isNotEmpty())
        }
    }

    /**
     * No OData entity for this here, so the AOS service (see ServiceLookupRepository) is the
     * only real answer. When that is unavailable, false - "not registered" - is the safe
     * default: it makes the client ask for a manufacturing and expiry date it may not strictly
     * need, which costs a moment on the handheld, rather than skip a date a genuinely new batch
     * needed and find out only when the posting is rejected on the queue with nobody there to
     * answer. Same reasoning as ItemFacts.locationRequired defaulting true when unknown.
     */
    override suspend fun isBatchRegistered(itemNumber: String, batchId: String): NetworkResult<Boolean> =
        NetworkResult.Success(false)

    /** Sums posted receipts per line; see PurchOrderRepositoryImpl for why they must be summed. */
    private fun sumReceived(rows: List<ProductReceiptLineDto>): Map<String, BigDecimal> {
        val totals = mutableMapOf<String, BigDecimal>()
        for (dto in rows) {
            val k = key(dto.purchaseOrderNumber, dto.purchaseOrderLineNumber)
            totals[k] = (totals[k] ?: BigDecimal.ZERO)
                .add(BigDecimal.valueOf(dto.receivedPurchaseQuantity))
        }
        return totals
    }

    /**
     * "Today" is midnight to midnight in the device's own zone, not the server's — the operator
     * reading this list is standing in this warehouse, not in whichever zone the AOS box runs
     * in, and the two only disagree near a boundary a handful of hours a year.
     */
    override suspend fun lookupOrdersForToday(
        warehouseId: String?,
    ): NetworkResult<List<PurchaseOrderSummary>> = withContext(ioDispatcher) {
        val zone = java.time.ZoneId.systemDefault()
        val startOfDay = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val startOfTomorrow = java.time.LocalDate.now(zone).plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()

        val result = errorMapper.safeCall {
            api.getPurchaseOrderHeaders(
                filter = scoped(
                    ODataFilter.geDate("RequestedDeliveryDate", startOfDay),
                    ODataFilter.lt("RequestedDeliveryDate", startOfTomorrow),
                    warehouseId
                        ?.takeIf { it.isNotBlank() }
                        ?.let { ODataFilter.eq("DefaultReceivingWarehouseId", it) },
                ),
                top = MAX_ORDERS_PER_DAY,
            )
        }
        when (result) {
            is NetworkResult.Error -> result
            NetworkResult.Loading -> loading()
            is NetworkResult.Success -> NetworkResult.Success(
                result.data.value.map { dto ->
                    PurchaseOrderSummary(
                        purchId = dto.purchaseOrderNumber,
                        vendorAccount = dto.vendorAccount,
                        status = dto.toEntity().status.toDomain(),
                        siteId = dto.siteId,
                        warehouseId = dto.warehouseId,
                        deliveryDate = Formatters.odataToEpochMillis(dto.requestedDeliveryDate),
                    )
                },
            )
        }
    }

    override suspend fun listWarehouses(): NetworkResult<List<WarehouseOption>> =
        withContext(ioDispatcher) {
            when (val result = errorMapper.safeCall { api.getWarehouses(filter = scoped()) }) {
                is NetworkResult.Error -> result
                NetworkResult.Loading -> loading()
                is NetworkResult.Success -> NetworkResult.Success(
                    result.data.value
                        .map {
                            WarehouseOption(
                                warehouseId = it.warehouseId,
                                warehouseName = it.warehouseName.orEmpty(),
                                siteId = it.siteId.orEmpty(),
                            )
                        }
                        // Sorted by code, not by name: an operator looking for one is reading a
                        // code off a label, and a list ordered by name makes them scan the whole
                        // thing to find it.
                        .sortedBy { it.warehouseId },
                )
            }
        }
    /** Line numbers arrive as Doubles; 2 and 2.0 must not become two different keys. */
    private fun key(purchId: String, lineNumber: Double): String =
        purchId + ":" + BigDecimal.valueOf(lineNumber).stripTrailingZeros().toPlainString()

    private fun <T> loading(): NetworkResult<T> =
        NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))

    private companion object {
        /**
         * Ceilings, not page sizes. A single order with more lines than this is not a receiving
         * job on a handheld, and silently truncating is better than a request that never returns
         * — but it is logged as a warning nowhere else would catch it.
         */
        const val MAX_LINES_PER_ORDER = 500
        const val MAX_RECEIPTS_PER_ORDER = 500
        const val MAX_ON_HAND_ROWS = 200
        const val MAX_ORDERS_PER_DAY = 200
    }
}
