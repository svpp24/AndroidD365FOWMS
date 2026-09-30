package com.d365fo.mobileops.data.sync.payload

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The serialized body of a queued operation.
 *
 * Deliberately self-contained: a queue entry captured by the morning shift may be posted
 * after that worker has signed out and the underlying draft rows have been purged, so the
 * payload carries everything the posting needs rather than referencing local tables.
 *
 * Polymorphic by an explicit discriminator so the stored JSON stays readable in a bug
 * report and survives adding new operations.
 */
@Serializable
sealed interface SyncPayload {
    val idempotencyKey: String
    val dataAreaId: String
}

@Serializable
@SerialName("product_receipt")
data class ProductReceiptPayload(
    override val idempotencyKey: String,
    override val dataAreaId: String,
    val receiptId: String,
    val purchaseOrderNumber: String,
    val vendorPackingSlipId: String,
    val receiptDate: String,
    val userName: String = "",
    val personnelNumber: String? = null,
    val deviceId: String,
    val lines: List<ReceiptLinePayload>,
) : SyncPayload

@Serializable
data class ReceiptLinePayload(
    val purchLineId: String,
    val lineNumber: Double,
    val itemNumber: String,
    val receiveNowQuantity: Double,
    val unitSymbol: String,
    val siteId: String? = null,
    val warehouseId: String? = null,
    val locationId: String? = null,
    val batchNumber: String? = null,
    /** Set only when [batchNumber] was new to D365FO at capture time. yyyy-MM-dd. */
    val batchManufacturingDate: String? = null,
    val batchExpiryDate: String? = null,
    /** One id per unit for a serial-tracked item, so a quantity of five carries five ids. */
    val serialNumbers: List<String> = emptyList(),
)

@Serializable
@SerialName("arrival_journal")
data class ArrivalJournalPayload(
    override val idempotencyKey: String,
    override val dataAreaId: String,
    val journalId: String,
    val journalNameId: String,
    val journalDate: String,
    val deviceId: String,
    val lines: List<JournalLinePayload>,
) : SyncPayload

@Serializable
@SerialName("inventory_journal")
data class InventoryJournalPayload(
    override val idempotencyKey: String,
    override val dataAreaId: String,
    val journalId: String,
    val journalNameId: String,
    /** MOVEMENT | ADJUSTMENT | COUNTING | TRANSFER */
    val journalType: String,
    val journalDate: String,
    val deviceId: String,
    val lines: List<JournalLinePayload>,
) : SyncPayload

@Serializable
data class JournalLinePayload(
    val lineNumber: Int,
    val itemNumber: String,
    val quantity: Double,
    val unitSymbol: String,
    val countedQuantity: Double? = null,
    val siteId: String? = null,
    val warehouseId: String? = null,
    val locationId: String? = null,
    val batchNumber: String? = null,
    val serialNumber: String? = null,
    val reasonCode: String? = null,
    val offsetAccount: String? = null,
    val toSiteId: String? = null,
    val toWarehouseId: String? = null,
    val toLocationId: String? = null,
)

@Serializable
@SerialName("transfer_ship")
data class TransferShipPayload(
    override val idempotencyKey: String,
    override val dataAreaId: String,
    val transferId: String,
    val shipDate: String,
    val userName: String = "",
    val personnelNumber: String? = null,
    val deviceId: String,
    val lines: List<TransferLinePayload>,
) : SyncPayload

@Serializable
@SerialName("transfer_receive")
data class TransferReceivePayload(
    override val idempotencyKey: String,
    override val dataAreaId: String,
    val transferId: String,
    val receiveDate: String,
    val userName: String = "",
    val personnelNumber: String? = null,
    val deviceId: String,
    val lines: List<TransferLinePayload>,
) : SyncPayload

@Serializable
data class TransferLinePayload(
    val lineNumber: Double,
    val itemNumber: String,
    val quantity: Double,
    val unitSymbol: String,
    val batchNumber: String? = null,
    /** One id per unit for a serial-tracked item, so a quantity of five carries five ids. */
    val serialNumbers: List<String> = emptyList(),
    val locationId: String? = null,
)

@Serializable
@SerialName("packing_slip")
data class PackingSlipPayload(
    override val idempotencyKey: String,
    override val dataAreaId: String,
    val salesOrderNumber: String,
    val packingSlipDate: String,
    val deviceId: String,
    val lines: List<PackingSlipLinePayload>,
) : SyncPayload

@Serializable
data class PackingSlipLinePayload(
    val lineNumber: Double,
    val itemNumber: String,
    val deliverNowQuantity: Double,
    val unitSymbol: String,
    val batchNumber: String? = null,
    val serialNumber: String? = null,
    val locationId: String? = null,
)

/**
 * An admin's toggle for one worker's access to one module, queued so it survives the admin's
 * own device being offline at the moment of the toggle. [actingUserName] is re-verified
 * server-side as a current supervisor before anything is written - see
 * `HSMobileOpsUserService::setModulePermission`.
 */
@Serializable
@SerialName("set_worker_permission")
data class SetWorkerPermissionPayload(
    override val idempotencyKey: String,
    override val dataAreaId: String,
    val actingUserName: String,
    val targetUserName: String,
    /** A [com.d365fo.mobileops.domain.model.WarehouseWorkflow] name. */
    val workflow: String,
    val allowed: Boolean,
) : SyncPayload
