package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.BatchRequestBody
import com.d365fo.mobileops.data.remote.dto.BatchServiceRequest
import com.d365fo.mobileops.data.remote.dto.LocationRequestBody
import com.d365fo.mobileops.data.remote.dto.LocationServiceRequest
import com.d365fo.mobileops.data.remote.dto.OnHandRequestBody
import com.d365fo.mobileops.data.remote.dto.OrdersForDateRequestBody
import com.d365fo.mobileops.data.remote.dto.OrdersForDateServiceRequest
import com.d365fo.mobileops.data.remote.dto.OnHandServiceRequest
import com.d365fo.mobileops.data.remote.dto.PurchOrderRequestBody
import com.d365fo.mobileops.data.remote.dto.PurchOrderServiceRequest
import com.d365fo.mobileops.data.remote.dto.SerialRequestBody
import com.d365fo.mobileops.data.remote.dto.LineRequestBody
import com.d365fo.mobileops.data.remote.dto.LineServiceRequest
import com.d365fo.mobileops.data.remote.dto.SerialServiceRequest
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
import com.d365fo.mobileops.domain.repository.OnHandLine
import com.d365fo.mobileops.domain.repository.OrderLookup
import com.d365fo.mobileops.domain.repository.PurchaseOrderSummary
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.Formatters
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lookups served by the HSMobileOps AOS service, falling back to OData.
 *
 * The fallback is deliberate and temporary. The AOS model has to be built and deployed by whoever
 * owns the D365 codebase, which is not the same release train as this app — so the app has to work
 * on an environment where the service does not exist yet, and start using it the moment it does,
 * without a rebuild.
 *
 * Fallback triggers on 404 only. That is the one response that unambiguously means "this service
 * is not deployed here". A 401, a 429 or a 500 all mean the service *is* there and something else
 * is wrong; silently retrying those against OData would turn a loud, diagnosable failure into a
 * mysteriously slow app, and would double the load on an environment that is already objecting.
 */
@Singleton
class ServiceLookupRepository @Inject constructor(
    private val service: MobileOpsServiceApi,
    private val odataFallback: OnDemandLookupRepositoryImpl,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val ioDispatcher: CoroutineDispatcher,
) : OnDemandLookupRepository {

    /**
     * Remembers that the service is absent, so only the first lookup of a session pays the wasted
     * round trip. Never set back to true at runtime: a deployment mid-shift is not worth probing
     * for on every scan, and restarting the app is a cheaper way to pick it up.
     */
    @Volatile
    private var serviceAvailable = true

    /** Whether to attempt the AOS service, or go straight to the OData fallback. */
    private fun serviceEnabled(): Boolean = serviceAvailable

    private fun company(): String =
        companyProvider()?.takeIf { it.isNotBlank() }?.lowercase().orEmpty()

    override suspend fun lookupOrder(purchId: String): NetworkResult<OrderLookup> =
        withContext(ioDispatcher) {
            val trimmed = purchId.trim()
            if (trimmed.isEmpty()) {
                return@withContext NetworkResult.Error(
                    AppFailure.BusinessRule("Enter or scan a purchase order number"),
                )
            }
            if (!serviceEnabled()) return@withContext odataFallback.lookupOrder(trimmed)

            val result = errorMapper.safeCall {
                service.getPurchaseOrder(
                    PurchOrderServiceRequest(
                        PurchOrderRequestBody(
                            dataAreaId = company(),
                            purchId = trimmed,
                        ),
                    ),
                )
            }

            when (result) {
                is NetworkResult.Error ->
                    if (isNotDeployed(result.failure)) {
                        fallBack("lookupOrder")
                        odataFallback.lookupOrder(trimmed)
                    } else {
                        result
                    }

                NetworkResult.Loading ->
                    NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))

                is NetworkResult.Success -> {
                    val body = result.data
                    if (!body.success) {
                        return@withContext NetworkResult.Error(
                            AppFailure.BusinessRule(
                                when (body.errorCode) {
                                    "NOT_FOUND" ->
                                        "No purchase order $trimmed in ${company().uppercase()}"
                                    "NOTHING_OUTSTANDING" ->
                                        "$trimmed has nothing left to receive"
                                    else -> body.message ?: "Could not read $trimmed"
                                },
                            ),
                        )
                    }
                    NetworkResult.Success(
                        OrderLookup(
                            order = body.toDomain(),
                            outstandingLineCount = body.outstandingLineCount,
                            linesInline = body.linesIncluded,
                            blockReason = body.errorCode?.takeIf { !body.receivable },
                            requireLocationCapture = body.requireLocationCapture,
                        ),
                    )
                }
            }
        }

    override suspend fun lookupLinesForItem(
        purchId: String,
        itemNumber: String,
    ): NetworkResult<List<PurchaseOrderLine>> = withContext(ioDispatcher) {
        if (!serviceEnabled()) {
            return@withContext odataFallback.lookupLinesForItem(purchId, itemNumber)
        }

        val result = errorMapper.safeCall {
            service.getLinesForItem(
                LineServiceRequest(
                    LineRequestBody(company(), purchId.trim(), itemNumber.trim()),
                ),
            )
        }

        when (result) {
            is NetworkResult.Error ->
                if (isNotDeployed(result.failure)) {
                    fallBack("lookupLinesForItem")
                    odataFallback.lookupLinesForItem(purchId, itemNumber)
                } else {
                    result
                }

            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))

            is NetworkResult.Success -> NetworkResult.Success(
                result.data.lines.map { it.toDomain(purchId.trim()) },
            )
        }
    }

    override suspend fun lookupLocation(
        warehouseId: String,
        locationId: String,
    ): NetworkResult<LocationFacts?> = withContext(ioDispatcher) {
        val trimmed = locationId.trim()
        if (trimmed.isEmpty()) return@withContext NetworkResult.Success(null)
        if (!serviceEnabled()) {
            return@withContext odataFallback.lookupLocation(warehouseId, trimmed)
        }

        val result = errorMapper.safeCall {
            service.validateLocation(
                LocationServiceRequest(
                    LocationRequestBody(company(), warehouseId, trimmed),
                ),
            )
        }

        when (result) {
            is NetworkResult.Error ->
                if (isNotDeployed(result.failure)) {
                    fallBack("lookupLocation")
                    odataFallback.lookupLocation(warehouseId, trimmed)
                } else {
                    result
                }

            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))

            is NetworkResult.Success -> {
                val body = result.data
                NetworkResult.Success(
                    if (!body.exists) {
                        null
                    } else {
                        LocationFacts(
                            warehouseId = body.warehouseId,
                            locationId = body.locationId,
                            canReceiveInto = body.canReceiveInto,
                            blockingCause = body.blockingCause?.takeIf { it.isNotBlank() },
                        )
                    },
                )
            }
        }
    }

    override suspend fun isSerialRegistered(
        itemNumber: String,
        serial: String,
    ): NetworkResult<Boolean> = withContext(ioDispatcher) {
        if (!serviceEnabled()) {
            return@withContext odataFallback.isSerialRegistered(itemNumber, serial)
        }

        val result = errorMapper.safeCall {
            service.validateSerial(
                SerialServiceRequest(
                    SerialRequestBody(company(), itemNumber.trim(), serial.trim()),
                ),
            )
        }

        when (result) {
            is NetworkResult.Error ->
                if (isNotDeployed(result.failure)) {
                    fallBack("isSerialRegistered")
                    odataFallback.isSerialRegistered(itemNumber, serial)
                } else {
                    result
                }

            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> NetworkResult.Success(result.data.registered)
        }
    }

    override suspend fun isBatchRegistered(
        itemNumber: String,
        batchId: String,
    ): NetworkResult<Boolean> = withContext(ioDispatcher) {
        if (!serviceEnabled()) {
            return@withContext odataFallback.isBatchRegistered(itemNumber, batchId)
        }

        val result = errorMapper.safeCall {
            service.validateBatch(
                BatchServiceRequest(
                    BatchRequestBody(company(), itemNumber.trim(), batchId.trim()),
                ),
            )
        }

        when (result) {
            is NetworkResult.Error ->
                if (isNotDeployed(result.failure)) {
                    fallBack("isBatchRegistered")
                    odataFallback.isBatchRegistered(itemNumber, batchId)
                } else {
                    result
                }

            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> NetworkResult.Success(result.data.registered)
        }
    }

    override suspend fun lookupOnHand(
        itemNumber: String,
        warehouseId: String?,
    ): NetworkResult<List<OnHandLine>> = withContext(ioDispatcher) {
        val trimmed = itemNumber.trim()
        if (trimmed.isEmpty()) return@withContext NetworkResult.Success(emptyList())
        if (!serviceEnabled()) return@withContext odataFallback.lookupOnHand(trimmed, warehouseId)

        val result = errorMapper.safeCall {
            service.getOnHand(
                OnHandServiceRequest(
                    OnHandRequestBody(company(), trimmed, warehouseId.orEmpty()),
                ),
            )
        }

        when (result) {
            is NetworkResult.Error ->
                if (isNotDeployed(result.failure)) {
                    fallBack("lookupOnHand")
                    odataFallback.lookupOnHand(trimmed, warehouseId)
                } else {
                    result
                }

            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))

            is NetworkResult.Success -> NetworkResult.Success(
                result.data.rows.map { row ->
                    OnHandLine(
                        itemNumber = row.itemId.ifBlank { trimmed },
                        siteId = row.siteId,
                        warehouseId = row.warehouseId,
                        onHand = BigDecimal.valueOf(row.onHand),
                        available = BigDecimal.valueOf(row.available),
                        reserved = BigDecimal.valueOf(row.reserved),
                        ordered = BigDecimal.valueOf(row.ordered),
                        totalAvailable = BigDecimal.valueOf(row.totalAvailable),
                    )
                },
            )
        }
    }

    /**
     * Barcode and item lookups stay on OData for now.
     *
     * Barcode resolution has no AOS method yet, and the item facts the flow needs already arrive
     * inside [lookupOrder]'s line contracts — so on the service path the item step costs nothing.
     * These two remain for the barcode case and for callers that ask about an item outside a PO.
     */
    override suspend fun lookupBarcode(barcode: String): NetworkResult<BarcodeMatch?> =
        odataFallback.lookupBarcode(barcode)

    override suspend fun lookupItem(itemNumber: String): NetworkResult<ItemFacts?> =
        odataFallback.lookupItem(itemNumber)

    override suspend fun listWarehouses() = odataFallback.listWarehouses()

    /**
     * PO of today from HSMobileOpsPurchListSvc (PurchTable in X++) - no OData. "Today" is the
     * device's own day, as before: the operator reading the list is standing in this warehouse.
     * No OData fallback: if the service isn't deployed, the screen says so.
     */
    override suspend fun lookupOrdersForToday(
        warehouseId: String?,
    ): NetworkResult<List<PurchaseOrderSummary>> = withContext(ioDispatcher) {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val result = errorMapper.safeCall {
            service.getOrdersForDate(
                OrdersForDateServiceRequest(
                    OrdersForDateRequestBody(
                        dataAreaId = companyProvider()?.takeIf { it.isNotBlank() }?.lowercase().orEmpty(),
                        warehouseId = warehouseId?.trim().orEmpty(),
                        deliveryDate = today.toString(),
                    ),
                ),
            )
        }
        when (result) {
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> if (!result.data.success) {
                NetworkResult.Error(
                    AppFailure.BusinessRule("D365FO couldn't list today's orders (${result.data.errorCode ?: "unknown"}).", result.data.errorCode),
                )
            } else {
                NetworkResult.Success(
                    result.data.orders.filter { it.purchId.isNotBlank() }.map { row ->
                        PurchaseOrderSummary(
                            purchId = row.purchId,
                            vendorAccount = row.vendorAccount.orEmpty(),
                            status = orderStatusOf(row.status),
                            siteId = row.siteId?.takeIf { it.isNotBlank() },
                            warehouseId = row.warehouseId?.takeIf { it.isNotBlank() },
                            deliveryDate = row.deliveryDate
                                ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                                ?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
                        )
                    },
                )
            }
        }
    }

    private fun orderStatusOf(symbol: String?): OrderStatus = when (symbol?.lowercase()) {
        "backorder" -> OrderStatus.BACKORDER
        "received" -> OrderStatus.RECEIVED
        "invoiced" -> OrderStatus.INVOICED
        "canceled" -> OrderStatus.CANCELED
        else -> OrderStatus.NONE
    }

    /** Only a 404 means "not deployed here". Everything else is a real failure to surface. */
    private fun isNotDeployed(failure: AppFailure): Boolean =
        failure is AppFailure.BusinessRule && failure.httpCode == 404

    private fun fallBack(where: String) {
        if (serviceAvailable) {
            Timber.w("HSMobileOps service not deployed; falling back to OData (%s)", where)
        }
        serviceAvailable = false
    }
}

/**
 * Maps the service response onto the domain model.
 *
 * The dimension flags travel with each line, so the step sequence is decided from data the PO
 * lookup already returned — no separate item call, which is the round trip this whole service
 * exists to remove.
 */
private fun com.d365fo.mobileops.data.remote.dto.PurchOrderServiceResponse.toDomain(): PurchaseOrder =
    PurchaseOrder(
        purchId = purchId,
        vendorAccount = vendorAccount,
        vendorName = vendorName,
        status = when {
            purchStatus.equals("Backorder", true) -> OrderStatus.BACKORDER
            purchStatus.equals("Received", true) -> OrderStatus.RECEIVED
            purchStatus.equals("Invoiced", true) -> OrderStatus.INVOICED
            purchStatus.equals("Canceled", true) -> OrderStatus.CANCELED
            else -> OrderStatus.NONE
        },
        siteId = siteId,
        warehouseId = warehouseId,
        deliveryDate = Formatters.odataToEpochMillis(deliveryDate),
        // The server decided this. The client no longer parses an approval status at all.
        approved = receivable,
        tolerance = DeliveryTolerance(overPercent = null, underPercent = null),
        lines = lines.map { it.toDomain(purchId) },
    )

/**
 * Maps one service line contract onto the domain.
 *
 * Shared by the whole-order response and the per-item response. Two copies of this would drift,
 * and the half that drifted would be the large-order path — the one that is hardest to notice
 * going wrong, because it only runs on orders too big to eyeball.
 */
internal fun com.d365fo.mobileops.data.remote.dto.PurchLineServiceDto.toDomain(
    purchId: String,
): PurchaseOrderLine = PurchaseOrderLine(
    lineId = "$purchId:$lineNumber",
    purchId = purchId,
    lineNumber = lineNumber,
    itemId = itemId,
    itemName = itemName,
    purchaseUnit = purchUnit,
    inventoryUnit = inventUnit.ifBlank { purchUnit },
    unitConversionFactor = BigDecimal.ONE,
    orderedQuantity = BigDecimal.valueOf(orderedQty),
    receivedQuantity = BigDecimal.valueOf(receivedQty),
    uncommittedQuantity = BigDecimal.valueOf(uncommittedQty),
    siteId = siteId,
    warehouseId = warehouseId,
    locationId = locationId?.takeIf { it.isNotBlank() },
    variant = com.d365fo.mobileops.domain.model.ProductVariant.of(configId, sizeId, colorId, styleId),
    tolerance = DeliveryTolerance(
        overPercent = overDeliveryPct.takeIf { it > 0.0 }?.let { BigDecimal.valueOf(it) },
        underPercent = underDeliveryPct.takeIf { it > 0.0 }?.let { BigDecimal.valueOf(it) },
    ),
    tracking = when {
        batchRequired && serialRequired -> TrackingRequirement.BATCH_AND_SERIAL
        serialRequired -> TrackingRequirement.SERIAL
        batchRequired -> TrackingRequirement.BATCH
        else -> TrackingRequirement.NONE
    },
    // Non-null here specifically: it is what tells the item step it already has everything
    // this line needs and can skip lookupItem's round trip. See the field doc on
    // PurchaseOrderLine.locationRequired for why that has to be more than just this value.
    locationRequired = locationRequired,
)
