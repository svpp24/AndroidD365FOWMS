package com.d365fo.mobileops.data.repository

import androidx.room.withTransaction
import com.d365fo.mobileops.data.local.D365Database
import com.d365fo.mobileops.data.local.dao.ItemDao
import com.d365fo.mobileops.data.local.dao.OrderCreationDao
import com.d365fo.mobileops.data.local.dao.PermissionDao
import com.d365fo.mobileops.data.local.entity.CreatedOrderEntity
import com.d365fo.mobileops.data.local.entity.CreatedOrderStatus
import com.d365fo.mobileops.data.local.entity.OrderDraftEntity
import com.d365fo.mobileops.data.local.entity.OrderKind
import com.d365fo.mobileops.data.local.entity.SyncOperation
import com.d365fo.mobileops.data.local.entity.VendorEntity
import com.d365fo.mobileops.data.local.entity.WorkerPermissionEntity
import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.BarcodeRequestBody
import com.d365fo.mobileops.data.remote.dto.BarcodeServiceRequest
import com.d365fo.mobileops.data.remote.dto.OrderItemDto
import com.d365fo.mobileops.data.remote.dto.OrderLineDto
import com.d365fo.mobileops.data.remote.dto.OrderRequestBody
import com.d365fo.mobileops.data.remote.dto.linesJson
import com.d365fo.mobileops.data.remote.dto.OrderServiceRequest
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.data.sync.PostingDispatcher
import com.d365fo.mobileops.data.sync.SyncQueueRepository
import com.d365fo.mobileops.data.sync.SyncScheduler
import com.d365fo.mobileops.data.sync.payload.CreatePurchaseOrderPayload
import com.d365fo.mobileops.data.sync.payload.CreateTransferOrderPayload
import com.d365fo.mobileops.data.sync.payload.OrderLinePayload
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** An item as an order line needs it - see HSMobileOpsOrderCreateService::getOrderItem. */
data class OrderItem(
    val itemId: String,
    val itemName: String,
    val purchUnit: String,
    val inventUnit: String,
    val configActive: Boolean,
    val sizeActive: Boolean,
    val colorActive: Boolean,
    val styleActive: Boolean,
    val batchActive: Boolean,
    val serialActive: Boolean,
    val minQty: Double,
    val maxQty: Double,
    val multipleQty: Double,
    val standardQty: Double,
    val variants: List<OrderVariant>,
    /** The variant the scanned barcode named, when it named one. */
    val scannedVariant: OrderVariant?,
    /** Physical negative inventory allowed (the item's model group): no stock refusal or cap. */
    val negativePhysical: Boolean = false,
    /** Each variant's own name in D365FO, from the device's SKU cache - the picker showed only "Config C1". */
    val variantNames: Map<OrderVariant, String> = emptyMap(),
) {
    val hasProductDimensions: Boolean get() = configActive || sizeActive || colorActive || styleActive
}

@Serializable
data class OrderVariant(
    val configId: String = "",
    val sizeId: String = "",
    val colorId: String = "",
    val styleId: String = "",
) {
    /**
     * "Config C1 · Size L · Colour Black · Style Regular" - each value named, only the ones set.
     * The bare values ("C1 L Black Regular") did not say which was the size and which the
     * style (feedback, variant picker).
     */
    val label: String get() = listOfNotNull(
        configId.takeIf { it.isNotBlank() }?.let { "Configuration: $it" },
        sizeId.takeIf { it.isNotBlank() }?.let { "Size: $it" },
        colorId.takeIf { it.isNotBlank() }?.let { "Colour: $it" },
        styleId.takeIf { it.isNotBlank() }?.let { "Style: $it" },
    ).joinToString(" · ")
}

/** One available stock row at a warehouse - batch and serial blank for untracked items; the bin only when asked for. */
data class StockRow(val batchId: String, val serialId: String, val available: Double, val locationId: String = "") {
    val label: String
        get() = listOfNotNull(
            locationId.takeIf { it.isNotBlank() }?.let { "Location $it" },
            batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
            serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" },
        ).joinToString(" · ")
}

/** A return reason code (ReturnReasonCode in D365FO). */
data class ReturnReason(val code: String, val description: String) {
    val label: String get() = if (description.isBlank()) code else "$code - $description"
}

/** A line of an order being built, as kept in its draft. */
@Serializable
data class DraftLine(
    val id: String = UUID.randomUUID().toString(),
    val itemId: String,
    val itemName: String,
    val variant: OrderVariant = OrderVariant(),
    val qty: Double,
    val unitId: String,
    val batchId: String = "",
    val serialId: String = "",
    /** The bin a return line takes the stock from (purchase return only). */
    val locationId: String = "",
    /** D365FO's price while online (or the supervisor's override); null = priced when sent. */
    val price: Double? = null,
    val priceOverride: Boolean = false,
    val discPct: Double? = null,
    val lineAmount: Double? = null,
    val priceSource: String? = null,
    /** Available at the From warehouse when the line was added (transfers, online only). */
    val available: Double? = null,
    /** The item may go below zero physically - [available] is shown, not enforced (transfers). */
    val allowsNegative: Boolean = false,
)

@Serializable
data class PurchaseDraft(
    val vendorAccount: String = "",
    val vendorName: String = "",
    val currencyCode: String = "",
    val warehouseId: String = "",
    val deliveryDate: String = LocalDate.now().toString(),
    val lines: List<DraftLine> = emptyList(),
)

/** A return to a vendor being built - see ReturnCreateViewModel. */
@Serializable
data class ReturnDraft(
    val vendorAccount: String = "",
    val vendorName: String = "",
    val warehouseId: String = "",
    val reasonCode: String = "",
    /** The return action every line gets - D365FO will not post a return line without one. */
    val actionCode: String = "",
    val rmaNumber: String = "",
    val lines: List<DraftLine> = emptyList(),
)

@Serializable
data class TransferDraft(
    val fromWarehouseId: String = "",
    val toWarehouseId: String = "",
    val shipDate: String = LocalDate.now().toString(),
    val receiveDate: String = LocalDate.now().toString(),
    val lines: List<DraftLine> = emptyList(),
)

/** A purchase order as PO confirmation shows it. */
data class PurchaseOrderSummary(
    val purchId: String,
    val vendorAccount: String,
    val vendorName: String,
    val warehouseId: String,
    val deliveryDate: String,
    val lineCount: Int,
    val total: Double,
    val currencyCode: String,
    /** VersioningDocumentState symbol: Draft, InReview, Approved, Confirmed... */
    val approvalStatus: String,
    val createdBy: String,
    val confirmationNumber: String,
) {
    val confirmable: Boolean get() = approvalStatus == "Approved"
    val confirmed: Boolean get() = approvalStatus == "Confirmed" || confirmationNumber.isNotBlank()

    /** [approvalStatus] as D365FO's own form shows it ("In review", not "InReview"). */
    val approvalLabel: String get() = when (approvalStatus) {
        "InReview" -> "In review"
        "InExternalReview" -> "In external review"
        else -> approvalStatus
    }
}

/** One line of a purchase order being confirmed, priced by D365FO. */
data class PurchaseOrderLine(
    val lineNumber: Int,
    val itemId: String,
    val itemName: String,
    val variant: OrderVariant,
    val qty: Double,
    val unitId: String,
    val price: Double,
    val discPct: Double,
    val lineAmount: Double,
)

data class PurchaseOrderDetail(val summary: PurchaseOrderSummary, val lines: List<PurchaseOrderLine>)

/**
 * Purchase and transfer order creation on the handheld - offline first. Vendors and items come
 * from what the device has saved; prices and stock are D365FO's, looked up only while online.
 * A finished order is saved and queued in one transaction, then sent at once when D365FO answers
 * (else by the sync queue later), and its row picks up the D365FO number - see OrderSender.
 */
@Singleton
class OrderCreationRepository @Inject constructor(
    private val api: MobileOpsServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val dao: OrderCreationDao,
    private val itemDao: ItemDao,
    private val permissionDao: PermissionDao,
    private val database: D365Database,
    private val syncQueue: SyncQueueRepository,
    private val postingDispatcher: PostingDispatcher,
    private val scheduler: SyncScheduler,
    private val companyProvider: CompanyProvider,
    private val secureStore: SecureStore,
    private val json: Json,
    /** Sends outlive the screen that queued them - see [queueAndSend]. */
    private val applicationScope: kotlinx.coroutines.CoroutineScope,
) {
    private fun company(): String = companyProvider().orEmpty()

    // -- Permissions -------------------------------------------------------------------------

    /** Re-reads the worker's Menu access, so a new allow shows without signing out. */
    suspend fun refreshPermissions(userName: String) {
        val result = errorMapper.safeCall {
            api.getModulePermissions(OrderServiceRequest(OrderRequestBody(dataAreaId = company(), userName = userName)))
        }
        if (result is NetworkResult.Success && result.data.success) {
            val rows = result.data.permissions.mapNotNull { dto ->
                runCatching { WarehouseWorkflow.valueOf(dto.module) }.getOrNull()
                    ?.let { WorkerPermissionEntity(userName = userName, workflow = it, allowed = dto.isAllowed) }
            }
            permissionDao.replaceFor(userName, rows)
        }
    }

    // -- Vendors -----------------------------------------------------------------------------

    suspend fun searchVendors(query: String): List<VendorEntity> = dao.searchVendors(query.trim())

    suspend fun vendorCount(): Int = dao.vendorCount()

    suspend fun vendorsSavedAt(): Long? = dao.vendorsSavedAt()

    /**
     * The whole vendor list from D365FO, page by page, replacing what the device has. Skipped
     * when it was refreshed in the last [maxAgeMs] unless [force].
     */
    suspend fun refreshVendors(force: Boolean = false, maxAgeMs: Long = 6 * 60 * 60 * 1000L): NetworkResult<Int> {
        val savedAt = dao.vendorsSavedAt()
        if (!force && savedAt != null && System.currentTimeMillis() - savedAt < maxAgeMs) {
            return NetworkResult.Success(dao.vendorCount())
        }
        val now = System.currentTimeMillis()
        val all = mutableListOf<VendorEntity>()
        var skip = 0
        repeat(MAX_VENDOR_PAGES) {
            val result = errorMapper.safeCall {
                api.listVendors(OrderServiceRequest(OrderRequestBody(dataAreaId = company(), skip = skip, take = VENDOR_PAGE)))
            }
            when (result) {
                is NetworkResult.Success -> {
                    if (!result.data.success) return NetworkResult.Error(AppFailure.BusinessRule(result.data.message.ifBlank { "Vendors could not be read" }))
                    all += result.data.vendors.map {
                        VendorEntity(it.accountNum, it.name.ifBlank { it.accountNum }, it.currencyCode, it.paymentTerms, it.deliveryTerms, it.deliveryMode, now)
                    }
                    if (!result.data.hasMore) {
                        dao.replaceVendors(all, now)
                        return NetworkResult.Success(all.size)
                    }
                    skip += VENDOR_PAGE
                }
                is NetworkResult.Error -> return result
                NetworkResult.Loading -> Unit
            }
        }
        dao.replaceVendors(all, now)
        return NetworkResult.Success(all.size)
    }

    // -- Items -------------------------------------------------------------------------------

    /**
     * The item behind a scan or a typed number: a barcode (from the device's barcode list, else
     * D365FO's) or an item number. Its order details come from D365FO, or from the copy saved
     * the last time it was looked up.
     */
    /** [fromWarehouseId]: a transfer's From warehouse, for [OrderItem.negativePhysical]. */
    /**
     * [offline]: D365FO is known to be out of reach - the device's item cache only, at once, rather
     * than two calls that each wait for their timeout first ("D365FO is slow", store count, 8 Nov).
     */
    suspend fun findItem(code: String, fromWarehouseId: String = "", offline: Boolean = false): NetworkResult<OrderItem> {
        val value = code.trim()
        if (value.isBlank()) return NetworkResult.Error(AppFailure.Validation("Scan or type an item"))

        var itemId = value
        var scanned: OrderVariant? = null
        val local = runCatching { itemDao.resolveBarcode(value) }.getOrNull()
        if (local != null) {
            itemId = local.itemId
            scanned = OrderVariant(local.configId.orEmpty(), local.sizeId.orEmpty(), local.colorId.orEmpty(), local.styleId.orEmpty())
        } else if (!offline) {
            val live = errorMapper.safeCall {
                api.getBarcode(BarcodeServiceRequest(BarcodeRequestBody(dataAreaId = company(), barcode = value)))
            }
            if (live is NetworkResult.Success && live.data.success && live.data.itemId.isNotBlank()) {
                itemId = live.data.itemId
                scanned = OrderVariant(live.data.configId, live.data.sizeId, live.data.colorId, live.data.styleId)
            }
        }

        if (offline) {
            return offlineItem(itemId, scanned)
                ?: NetworkResult.Error(AppFailure.Offline("$value is not on this device yet - offline, so it can't be looked up."))
        }
        val result = errorMapper.safeCall {
            api.getOrderItem(OrderServiceRequest(OrderRequestBody(dataAreaId = company(), itemId = itemId, fromWarehouseId = fromWarehouseId)))
        }
        return when (result) {
            is NetworkResult.Success -> {
                val body = result.data
                val item = body.item
                if (!body.success || item == null || item.itemId.isBlank()) {
                    NetworkResult.Error(AppFailure.BusinessRule(body.message.ifBlank { "$value is not an item" }, d365ErrorCode = body.errorCode))
                } else {
                    NetworkResult.Success(item.toDomain(scanned?.takeIf { it.label.isNotBlank() }).withVariantNames())
                }
            }
            // No connection, and no copy of D365FO's answer saved: the item and its variants
            // from the device's SKU cache (SkuSyncRepository), so scanning by item number still
            // works offline. Order rules (min/max/multiple) are D365FO's, checked when it syncs.
            is NetworkResult.Error -> if (result.failure.retryable) offlineItem(itemId, scanned) ?: result else result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
        }
    }

    private suspend fun OrderItem.withVariantNames(): OrderItem {
        val names = runCatching { itemDao.variantsOf(itemId) }.getOrDefault(emptyList())
            // Only a name that says something: D365FO copies the master's name ("DM001") onto
            // every variant by default, which would read as the same name five times.
            .filter { it.name.isNotBlank() && !it.name.equals(itemName, ignoreCase = true) && !it.name.equals(itemId, ignoreCase = true) }
            .associate { OrderVariant(it.configId, it.sizeId, it.colorId, it.styleId) to it.name }
        return if (names.isEmpty()) this else copy(variantNames = names)
    }

    private suspend fun offlineItem(itemId: String, scanned: OrderVariant?): NetworkResult<OrderItem>? {
        val item = runCatching { itemDao.getItem(itemId) }.getOrNull() ?: return null
        val variants = runCatching { itemDao.variantsOf(itemId) }.getOrDefault(emptyList())
            .map { OrderVariant(it.configId, it.sizeId, it.colorId, it.styleId) }
        return NetworkResult.Success(
            OrderItem(
                itemId = item.itemId,
                itemName = item.itemName.ifBlank { item.itemId },
                purchUnit = item.purchaseUnit ?: item.inventoryUnit,
                inventUnit = item.inventoryUnit,
                configActive = variants.any { it.configId.isNotBlank() },
                sizeActive = variants.any { it.sizeId.isNotBlank() },
                colorActive = variants.any { it.colorId.isNotBlank() },
                styleActive = variants.any { it.styleId.isNotBlank() },
                batchActive = item.trackingRequirement.requiresBatch,
                serialActive = item.trackingRequirement.requiresSerial,
                minQty = 0.0,
                maxQty = 0.0,
                multipleQty = 0.0,
                standardQty = 0.0,
                variants = variants,
                scannedVariant = scanned?.takeIf { it.label.isNotBlank() },
            ).withVariantNames(),
        )
    }

    private fun OrderItemDto.toDomain(scanned: OrderVariant?) = OrderItem(
        itemId = itemId,
        itemName = itemName.ifBlank { itemId },
        purchUnit = purchUnit,
        inventUnit = inventUnit,
        configActive = configActive,
        sizeActive = sizeActive,
        colorActive = colorActive,
        styleActive = styleActive,
        batchActive = batchActive,
        serialActive = serialActive,
        negativePhysical = negativePhysical,
        minQty = minQty,
        maxQty = maxQty,
        multipleQty = multipleQty,
        standardQty = standardQty,
        variants = variants.map { OrderVariant(it.configId, it.sizeId, it.colorId, it.styleId) }.distinct(),
        scannedVariant = scanned,
    )

    // -- Prices and stock (online only) ------------------------------------------------------

    /** D365FO's price for each line, as createLine would set it. Fails offline - prices then wait for the sync. */
    suspend fun priceLines(draft: PurchaseDraft): NetworkResult<List<DraftLine>> {
        val request = OrderServiceRequest(
            OrderRequestBody(
                dataAreaId = company(),
                vendorAccount = draft.vendorAccount,
                warehouseId = draft.warehouseId,
                deliveryDate = draft.deliveryDate,
                linesJson = linesJson(draft.lines.map { it.toDto() }),
            ),
        )
        return when (val result = errorMapper.safeCall { api.priceLines(request) }) {
            is NetworkResult.Success -> {
                if (!result.data.success) {
                    NetworkResult.Error(AppFailure.BusinessRule(result.data.message.ifBlank { "Prices could not be read" }))
                } else {
                    NetworkResult.Success(
                        draft.lines.mapIndexed { i, line ->
                            val priced = result.data.lines.getOrNull(i)
                            if (priced == null || line.priceOverride) line else line.copy(
                                price = priced.price,
                                discPct = priced.discPct,
                                lineAmount = priced.lineAmount,
                                priceSource = priced.priceSource.ifBlank { null },
                                unitId = priced.unitId.ifBlank { line.unitId },
                            )
                        },
                    )
                }
            }
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
        }
    }

    /** Available stock of a variant at [warehouseId], per batch and serial - and per bin with [byLocation]. */
    suspend fun stock(itemId: String, variant: OrderVariant, warehouseId: String, byLocation: Boolean = false): NetworkResult<List<StockRow>> {
        val request = OrderServiceRequest(
            OrderRequestBody(
                dataAreaId = company(),
                itemId = itemId,
                configId = variant.configId,
                sizeId = variant.sizeId,
                colorId = variant.colorId,
                styleId = variant.styleId,
                warehouseId = warehouseId,
                groupByLocation = byLocation,
            ),
        )
        return when (val result = errorMapper.safeCall { api.getStock(request) }) {
            is NetworkResult.Success -> NetworkResult.Success(result.data.lines.map { StockRow(it.batchId, it.serialId, it.available, it.locationId) })
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
        }
    }

    // -- Drafts ------------------------------------------------------------------------------

    suspend fun loadPurchaseDraft(userName: String): PurchaseDraft? =
        dao.draft(OrderKind.PURCHASE, userName)?.let { runCatching { json.decodeFromString(PurchaseDraft.serializer(), it.json) }.getOrNull() }

    suspend fun savePurchaseDraft(userName: String, draft: PurchaseDraft) =
        dao.saveDraft(OrderDraftEntity(OrderKind.PURCHASE, userName, json.encodeToString(PurchaseDraft.serializer(), draft), System.currentTimeMillis()))

    suspend fun loadTransferDraft(userName: String): TransferDraft? =
        dao.draft(OrderKind.TRANSFER, userName)?.let { runCatching { json.decodeFromString(TransferDraft.serializer(), it.json) }.getOrNull() }

    suspend fun saveTransferDraft(userName: String, draft: TransferDraft) =
        dao.saveDraft(OrderDraftEntity(OrderKind.TRANSFER, userName, json.encodeToString(TransferDraft.serializer(), draft), System.currentTimeMillis()))

    suspend fun loadReturnDraft(userName: String): ReturnDraft? =
        dao.draft(OrderKind.RETURN, userName)?.let { runCatching { json.decodeFromString(ReturnDraft.serializer(), it.json) }.getOrNull() }

    suspend fun saveReturnDraft(userName: String, draft: ReturnDraft) =
        dao.saveDraft(OrderDraftEntity(OrderKind.RETURN, userName, json.encodeToString(ReturnDraft.serializer(), draft), System.currentTimeMillis()))

    suspend fun discardDraft(kind: OrderKind, userName: String) = dao.deleteDraft(kind, userName)

    // -- Orders ------------------------------------------------------------------------------

    fun observeOrders(kind: OrderKind): Flow<List<CreatedOrderEntity>> = dao.observeOrders(kind)

    /**
     * Saves the purchase order and queues it - one transaction, then the draft is gone - and
     * sends it straight away when D365FO answers. Returns the order's id (its idempotency key).
     */
    suspend fun submitPurchase(userName: String, draft: PurchaseDraft): String {
        val id = UUID.randomUUID().toString()
        val payload = CreatePurchaseOrderPayload(
            idempotencyKey = id,
            dataAreaId = company(),
            userName = userName,
            deviceId = secureStore.deviceId(),
            vendorAccount = draft.vendorAccount,
            vendorName = draft.vendorName,
            warehouseId = draft.warehouseId,
            deliveryDate = draft.deliveryDate,
            lines = draft.lines.map { it.toPayload() },
        )
        val order = CreatedOrderEntity(
            id = id,
            kind = OrderKind.PURCHASE,
            status = CreatedOrderStatus.WAITING,
            title = "${draft.vendorAccount} ${draft.vendorName}".trim(),
            lineCount = draft.lines.size,
            total = draft.lines.mapNotNull { it.lineAmount }.takeIf { it.size == draft.lines.size }?.sum(),
            currencyCode = draft.currencyCode.ifBlank { null },
            userName = userName,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        queueAndSend(order, SyncOperation.CREATE_PURCHASE_ORDER, payload.vendorAccount, payload, userName, OrderKind.PURCHASE)
        return id
    }

    suspend fun submitTransfer(userName: String, draft: TransferDraft): String {
        val id = UUID.randomUUID().toString()
        val payload = CreateTransferOrderPayload(
            idempotencyKey = id,
            dataAreaId = company(),
            userName = userName,
            deviceId = secureStore.deviceId(),
            fromWarehouseId = draft.fromWarehouseId,
            toWarehouseId = draft.toWarehouseId,
            shipDate = draft.shipDate,
            receiveDate = draft.receiveDate,
            lines = draft.lines.map { it.toPayload() },
        )
        val order = CreatedOrderEntity(
            id = id,
            kind = OrderKind.TRANSFER,
            status = CreatedOrderStatus.WAITING,
            title = "${draft.fromWarehouseId} → ${draft.toWarehouseId}",
            lineCount = draft.lines.size,
            userName = userName,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        queueAndSend(order, SyncOperation.CREATE_TRANSFER_ORDER, "${draft.fromWarehouseId}-${draft.toWarehouseId}", payload, userName, OrderKind.TRANSFER)
        return id
    }

    // -- Purchase return -----------------------------------------------------------------------

    /** Return reason codes and return actions, as D365FO has them set up. */
    data class ReturnCodes(val reasons: List<ReturnReason>, val actions: List<ReturnReason>)

    /** The company's return reasons and actions - D365FO's, or the copy saved the last time it answered. */
    suspend fun returnCodes(): NetworkResult<ReturnCodes> =
        when (val result = errorMapper.safeCall { api.listReturnReasons(OrderServiceRequest(OrderRequestBody(dataAreaId = company()))) }) {
            is NetworkResult.Success ->
                if (result.data.success) {
                    // Codes are often numbers: 1, 2 ... 10, not 1, 10, 2.
                    val order = compareBy<ReturnReason>({ it.code.toIntOrNull() ?: Int.MAX_VALUE }, { it.code })
                    NetworkResult.Success(
                        ReturnCodes(
                            reasons = result.data.reasons.map { ReturnReason(it.code, it.description) }.sortedWith(order),
                            actions = result.data.actions.map { ReturnReason(it.code, it.description) }.sortedWith(order),
                        ),
                    )
                }
                else NetworkResult.Error(AppFailure.BusinessRule(result.data.message.ifBlank { "Could not read the return reasons" }))
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
        }

    /**
     * Saves and queues the return - sent at once when D365FO answers, else by the sync queue -
     * and D365FO creates the return order, confirms it and posts its packing slip. Returns its id.
     */
    suspend fun submitReturn(userName: String, draft: ReturnDraft): String {
        val id = UUID.randomUUID().toString()
        val payload = com.d365fo.mobileops.data.sync.payload.CreatePurchaseReturnPayload(
            idempotencyKey = id,
            dataAreaId = company(),
            userName = userName,
            deviceId = secureStore.deviceId(),
            vendorAccount = draft.vendorAccount,
            vendorName = draft.vendorName,
            warehouseId = draft.warehouseId,
            returnReasonCode = draft.reasonCode,
            returnActionId = draft.actionCode,
            rmaNumber = draft.rmaNumber.trim(),
            lines = draft.lines.map { it.toPayload() },
        )
        val order = CreatedOrderEntity(
            id = id,
            kind = OrderKind.RETURN,
            status = CreatedOrderStatus.WAITING,
            title = "${draft.vendorAccount} ${draft.vendorName}".trim(),
            lineCount = draft.lines.size,
            userName = userName,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        queueAndSend(order, SyncOperation.CREATE_PURCHASE_RETURN, draft.vendorAccount, payload, userName, OrderKind.RETURN)
        return id
    }

    // -- PO confirmation ----------------------------------------------------------------------

    /** Approved, unconfirmed purchase orders of [warehouseId] (cached for offline replay). */
    suspend fun purchaseOrdersToConfirm(userName: String, warehouseId: String): NetworkResult<List<PurchaseOrderSummary>> {
        val request = OrderServiceRequest(OrderRequestBody(dataAreaId = company(), userName = userName, warehouseId = warehouseId, take = 200))
        return when (val result = errorMapper.safeCall { api.listPurchaseOrdersToConfirm(request) }) {
            is NetworkResult.Success ->
                if (result.data.success) NetworkResult.Success(result.data.orders.map { it.toSummary() })
                else NetworkResult.Error(AppFailure.BusinessRule(result.data.message.ifBlank { "Could not list purchase orders (${result.data.errorCode})" }))
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
        }
    }

    /** One purchase order with its lines - any status; [PurchaseOrderSummary.confirmable] says if it can be confirmed. */
    suspend fun purchaseOrder(userName: String, purchId: String): NetworkResult<PurchaseOrderDetail> {
        val request = OrderServiceRequest(OrderRequestBody(dataAreaId = company(), userName = userName, purchId = purchId.trim()))
        return when (val result = errorMapper.safeCall { api.getPurchaseOrderForConfirm(request) }) {
            is NetworkResult.Success -> {
                val body = result.data
                val summary = body.orders.firstOrNull()
                if (body.success && summary != null) {
                    NetworkResult.Success(
                        PurchaseOrderDetail(
                            summary = summary.toSummary(),
                            lines = body.lines.map {
                                PurchaseOrderLine(
                                    lineNumber = it.lineNumber.toInt(),
                                    itemId = it.itemId,
                                    itemName = it.itemName,
                                    variant = OrderVariant(it.configId, it.sizeId, it.colorId, it.styleId),
                                    qty = it.qty,
                                    unitId = it.unitId,
                                    price = it.price,
                                    discPct = it.discPct,
                                    lineAmount = it.lineAmount,
                                )
                            },
                        ),
                    )
                } else {
                    NetworkResult.Error(AppFailure.BusinessRule(body.message.ifBlank { "Could not read $purchId (${body.errorCode})" }, d365ErrorCode = body.errorCode))
                }
            }
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
        }
    }

    /**
     * Saves and queues the confirmation of [order] - sent at once when D365FO answers, else by
     * the sync queue. Returns its id (the idempotency key).
     */
    suspend fun submitConfirmation(userName: String, order: PurchaseOrderSummary): String {
        val id = UUID.randomUUID().toString()
        val payload = com.d365fo.mobileops.data.sync.payload.ConfirmPurchaseOrderPayload(
            idempotencyKey = id,
            dataAreaId = company(),
            userName = userName,
            deviceId = secureStore.deviceId(),
            purchId = order.purchId,
        )
        val row = CreatedOrderEntity(
            id = id,
            kind = OrderKind.CONFIRMATION,
            status = CreatedOrderStatus.WAITING,
            documentNumber = order.purchId,
            title = "${order.vendorAccount} ${order.vendorName}".trim(),
            lineCount = order.lineCount,
            total = order.total.takeIf { order.lineCount > 0 },
            currencyCode = order.currencyCode.ifBlank { null },
            userName = userName,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        queueAndSend(row, SyncOperation.CONFIRM_PURCHASE_ORDER, order.purchId, payload, userName, null)
        return id
    }

    private fun com.d365fo.mobileops.data.remote.dto.OrderSummaryDto.toSummary() = PurchaseOrderSummary(
        purchId = purchId,
        vendorAccount = vendorAccount,
        vendorName = vendorName,
        warehouseId = warehouseId,
        deliveryDate = deliveryDate,
        lineCount = lineCount,
        total = orderTotal,
        currencyCode = currencyCode,
        approvalStatus = approvalStatus,
        createdBy = createdBy,
        confirmationNumber = confirmationNumber,
    )

    internal suspend fun queueAndSend(
        order: CreatedOrderEntity,
        operation: SyncOperation,
        aggregateId: String,
        payload: com.d365fo.mobileops.data.sync.payload.SyncPayload,
        userName: String,
        kind: OrderKind?,
    ) {
        val entry = syncQueue.buildEntry(operation, aggregateId, payload, userName, secureStore.deviceId())
        database.withTransaction {
            dao.upsertOrder(order)
            syncQueue.enqueue(entry)
            if (kind != null) dao.deleteDraft(kind, userName)
        }
        // Sent now when D365FO answers, by the same claim the background worker uses - see
        // TransferFlowViewModel.postTransfer for why the entry is leased first. Not awaited:
        // creating and confirming can take D365FO a while, and the worker should not wait on a
        // spinner for it - the order's card follows it from Waiting to Created.
        applicationScope.launch { sendNow(entry.queueId, order.id) }
    }

    private suspend fun sendNow(queueId: String, orderId: String) {
        if (!postingDispatcher.postingAllowed()) return
        val leased = syncQueue.leaseOne(queueId)
        if (leased == null) {
            scheduler.requestImmediatePush()
            return
        }
        when (val result = postingDispatcher.dispatch(leased)) {
            is NetworkResult.Success -> syncQueue.onSuccess(leased)
            is NetworkResult.Error -> {
                syncQueue.onFailure(leased, result.failure)
                Timber.i("order %s not sent yet: %s", orderId, result.failure.message)
                scheduler.requestImmediatePush()
            }
            NetworkResult.Loading -> Unit
        }
    }

    private fun DraftLine.toDto() = OrderLineDto(
        itemId = itemId,
        configId = variant.configId,
        sizeId = variant.sizeId,
        colorId = variant.colorId,
        styleId = variant.styleId,
        batchId = batchId,
        serialId = serialId,
        qty = qty,
        unitId = unitId,
        price = if (priceOverride) price ?: 0.0 else 0.0,
        priceOverride = priceOverride,
    )

    private fun DraftLine.toPayload() = OrderLinePayload(
        itemId = itemId,
        itemName = itemName,
        configId = variant.configId,
        sizeId = variant.sizeId,
        colorId = variant.colorId,
        styleId = variant.styleId,
        batchId = batchId,
        serialId = serialId,
        locationId = locationId,
        qty = qty,
        unitId = unitId,
        price = if (priceOverride) price else null,
        priceOverride = priceOverride,
    )

    private companion object {
        const val VENDOR_PAGE = 1000
        const val MAX_VENDOR_PAGES = 50
    }
}
