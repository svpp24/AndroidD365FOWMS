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
    /** Shipping: picked in D365FO when it was scanned - see TransferPicker. */
    val picked: Boolean = false,
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

/**
 * A purchase order built on the handheld. Prices are D365FO's, set when it is created; a line's
 * [OrderLinePayload.price] is only sent as an override (a supervisor, online).
 */
@Serializable
@SerialName("create_purchase_order")
data class CreatePurchaseOrderPayload(
    override val idempotencyKey: String,
    override val dataAreaId: String,
    val userName: String,
    val deviceId: String,
    val vendorAccount: String,
    val vendorName: String = "",
    val warehouseId: String,
    /** yyyy-MM-dd */
    val deliveryDate: String,
    val lines: List<OrderLinePayload>,
) : SyncPayload

/**
 * Feedback from the app. The screenshot stays a file on the device ([screenshotPath]) until it is
 * sent - a JPEG in the queue's own table would bloat every read of it.
 */
@Serializable
@SerialName("send_feedback")
data class SendFeedbackPayload(
    override val idempotencyKey: String,
    override val dataAreaId: String,
    val userName: String,
    val deviceId: String,
    val warehouseId: String,
    val appVersion: String,
    val deviceModel: String,
    val screen: String,
    val feedbackType: String,
    val message: String,
    val logs: String,
    val screenshotPath: String? = null,
) : SyncPayload

/** Confirmation of an approved purchase order (PO confirmation). */
@Serializable
@SerialName("confirm_purchase_order")
data class ConfirmPurchaseOrderPayload(
    override val idempotencyKey: String,
    override val dataAreaId: String,
    val userName: String,
    val deviceId: String,
    val purchId: String,
) : SyncPayload

/** Stock returned to a vendor: a return order D365FO creates, confirms and posts the packing slip of. */
@Serializable
@SerialName("create_purchase_return")
data class CreatePurchaseReturnPayload(
    override val idempotencyKey: String,
    override val dataAreaId: String,
    val userName: String,
    val deviceId: String,
    val vendorAccount: String,
    val vendorName: String = "",
    val warehouseId: String,
    val returnReasonCode: String = "",
    val rmaNumber: String = "",
    val returnActionId: String = "",
    val lines: List<OrderLinePayload>,
) : SyncPayload

/** A transfer order built on the handheld, between two of the worker's warehouses. */
@Serializable
@SerialName("create_transfer_order")
data class CreateTransferOrderPayload(
    override val idempotencyKey: String,
    override val dataAreaId: String,
    val userName: String,
    val deviceId: String,
    val fromWarehouseId: String,
    val toWarehouseId: String,
    /** yyyy-MM-dd */
    val shipDate: String,
    val receiveDate: String,
    val lines: List<OrderLinePayload>,
) : SyncPayload

@Serializable
data class OrderLinePayload(
    val itemId: String,
    val itemName: String = "",
    val configId: String = "",
    val sizeId: String = "",
    val colorId: String = "",
    val styleId: String = "",
    val batchId: String = "",
    val serialId: String = "",
    /** The bin a return takes the stock from; blank for orders. */
    val locationId: String = "",
    val qty: Double,
    val unitId: String = "",
    /** Set by D365FO once priced; sent only with [priceOverride]. */
    val price: Double? = null,
    val priceOverride: Boolean = false,
    val discPct: Double? = null,
    val lineAmount: Double? = null,
    val priceSource: String? = null,
)
