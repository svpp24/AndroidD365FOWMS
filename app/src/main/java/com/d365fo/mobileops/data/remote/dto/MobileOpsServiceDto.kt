package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contracts for the HSMobileOps AOS service.
 *
 * A custom service takes its parameters wrapped in an object named after the X++ method parameter
 * — `_request` here. That wrapper is not decoration: post the contract unwrapped and the AOS
 * deserialises every field to its default and answers as though nothing was asked. If the service
 * is ever regenerated with a differently named parameter, this wrapper name changes with it.
 *
 * Booleans are real JSON booleans on this path, unlike the OData entities where they arrive as the
 * NoYes strings "Yes"/"No" — a custom service serialises the X++ `boolean` type directly.
 */
@Serializable
data class PurchOrderServiceRequest(
    @SerialName("_request") val request: PurchOrderRequestBody,
)

@Serializable
data class PurchOrderRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("PurchId") val purchId: String,
    @SerialName("WarehouseId") val warehouseId: String = "",
)

@Serializable
data class PurchOrderServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    /** Stable token — NOT_FOUND, NOT_APPROVED, NOTHING_OUTSTANDING, INVALID_REQUEST. */
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
    @SerialName("PurchId") val purchId: String = "",
    @SerialName("VendorAccount") val vendorAccount: String = "",
    @SerialName("VendorName") val vendorName: String = "",
    @SerialName("PurchStatus") val purchStatus: String? = null,
    @SerialName("ApprovalStatus") val approvalStatus: String? = null,
    /**
     * Whether the AOS considers this order receivable. Decided on the server precisely because
     * the client got it wrong twice while inferring it from an approval-status string.
     */
    @SerialName("Receivable") val receivable: Boolean = false,
    @SerialName("SiteId") val siteId: String? = null,
    @SerialName("WarehouseId") val warehouseId: String? = null,
    @SerialName("DeliveryDate") val deliveryDate: String? = null,
    @SerialName("CurrencyCode") val currencyCode: String? = null,
    /** Always the true total, whether or not [lines] was populated. */
    @SerialName("OutstandingLineCount") val outstandingLineCount: Int = 0,
    /**
     * False when the order was too large to send lines inline; ask per item instead.
     * Not an error - see LineServiceResponse.
     */
    @SerialName("LinesIncluded") val linesIncluded: Boolean = false,
    @SerialName("Lines") val lines: List<PurchLineServiceDto> = emptyList(),
    /**
     * Whether receiving still asks for a put-away location when a line has none of its own.
     * Backoffice-configurable - see HSMobileOpsProcessSetup.RequireLocationCapture. Defaults
     * true (keep asking) so an old server that has not deployed this field yet, deserializing
     * it as absent, changes nothing about existing behaviour.
     */
    @SerialName("RequireLocationCapture") val requireLocationCapture: Boolean = true,
)

@Serializable
data class PurchLineServiceDto(
    @SerialName("LineNumber") val lineNumber: Double = 0.0,
    @SerialName("ItemId") val itemId: String = "",
    @SerialName("ItemName") val itemName: String = "",
    @SerialName("PurchUnit") val purchUnit: String = "",
    @SerialName("InventUnit") val inventUnit: String = "",
    @SerialName("OrderedQty") val orderedQty: Double = 0.0,
    /** Server-derived from PurchQty − RemainPurchPhysical. No client-side receipt join. */
    @SerialName("ReceivedQty") val receivedQty: Double = 0.0,
    @SerialName("RemainingQty") val remainingQty: Double = 0.0,
    @SerialName("SiteId") val siteId: String? = null,
    @SerialName("WarehouseId") val warehouseId: String? = null,
    @SerialName("LocationId") val locationId: String? = null,
    /** Straight from InventDimGroupSetup — the step sequence is computed from these three. */
    @SerialName("BatchRequired") val batchRequired: Boolean = false,
    @SerialName("SerialRequired") val serialRequired: Boolean = false,
    @SerialName("LocationRequired") val locationRequired: Boolean = false,
    @SerialName("OverDeliveryPct") val overDeliveryPct: Double = 0.0,
    @SerialName("UnderDeliveryPct") val underDeliveryPct: Double = 0.0,
    @SerialName("LineStatus") val lineStatus: String? = null,
    @SerialName("Stopped") val stopped: Boolean = false,
)

// -- Location -------------------------------------------------------------------------------

@Serializable
data class LocationServiceRequest(
    @SerialName("_request") val request: LocationRequestBody,
)

@Serializable
data class LocationRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("WarehouseId") val warehouseId: String,
    @SerialName("LocationId") val locationId: String,
)

@Serializable
data class LocationServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    /** Distinct from [canReceiveInto]: "no such bin" and "blocked" are different problems. */
    @SerialName("Exists") val exists: Boolean = false,
    @SerialName("CanReceiveInto") val canReceiveInto: Boolean = false,
    @SerialName("WarehouseId") val warehouseId: String = "",
    @SerialName("LocationId") val locationId: String = "",
    @SerialName("BlockingCause") val blockingCause: String? = null,
)

// -- Serial ---------------------------------------------------------------------------------

@Serializable
data class SerialServiceRequest(
    @SerialName("_request") val request: SerialRequestBody,
)

@Serializable
data class SerialRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("ItemId") val itemId: String,
    @SerialName("SerialId") val serialId: String,
)

@Serializable
data class SerialServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Registered") val registered: Boolean = false,
)

// -- Batch ------------------------------------------------------------------------------------

@Serializable
data class BatchServiceRequest(
    @SerialName("_request") val request: BatchRequestBody,
)

@Serializable
data class BatchRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("ItemId") val itemId: String,
    @SerialName("BatchId") val batchId: String,
)

@Serializable
data class BatchServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Registered") val registered: Boolean = false,
)

// -- On hand --------------------------------------------------------------------------------

@Serializable
data class OnHandServiceRequest(
    @SerialName("_request") val request: OnHandRequestBody,
)

@Serializable
data class OnHandRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("ItemId") val itemId: String,
    /** Blank means every warehouse. */
    @SerialName("WarehouseId") val warehouseId: String = "",
)

@Serializable
data class OnHandServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Rows") val rows: List<OnHandServiceRow> = emptyList(),
)

@Serializable
data class OnHandServiceRow(
    @SerialName("ItemId") val itemId: String = "",
    @SerialName("SiteId") val siteId: String? = null,
    @SerialName("WarehouseId") val warehouseId: String? = null,
    @SerialName("OnHand") val onHand: Double = 0.0,
    @SerialName("Available") val available: Double = 0.0,
    @SerialName("Reserved") val reserved: Double = 0.0,
    @SerialName("Ordered") val ordered: Double = 0.0,
    @SerialName("TotalAvailable") val totalAvailable: Double = 0.0,
)

// -- Lines for one item ---------------------------------------------------------------------

@Serializable
data class LineServiceRequest(
    @SerialName("_request") val request: LineRequestBody,
)

@Serializable
data class LineRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("PurchId") val purchId: String,
    @SerialName("ItemId") val itemId: String,
)

/**
 * The outstanding lines for one item on one order.
 *
 * The large-order path. `getPurchaseOrder` omits lines past a threshold and reports the count
 * instead, so the item step asks this — one keyed query returning the handful of lines that
 * actually carry the scanned item, rather than thousands the device would filter and discard.
 */
@Serializable
data class LineServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    /** NOT_ON_ORDER when the item is not outstanding here — a real answer, not a failure. */
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Lines") val lines: List<PurchLineServiceDto> = emptyList(),
)
