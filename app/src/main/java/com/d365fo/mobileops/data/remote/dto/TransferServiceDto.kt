package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contracts for the HSMobileOps transfer service.
 *
 * One order, worked from two ends, and the two are not symmetrical: which quantity is outstanding
 * differs, which warehouse the operator should be standing in differs, and which statuses permit
 * the work differs. [TransferRequestBody.mode] carries the direction, and the server answers for
 * that direction only — the client never has to know whether it is looking at a ship quantity or
 * a receive one.
 */
@Serializable
data class TransferServiceRequest(
    @SerialName("_request") val request: TransferRequestBody,
)

@Serializable
data class TransferRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("TransferId") val transferId: String,
    /** Where the operator is. Checked against the right end of the transfer, not used to filter. */
    @SerialName("WarehouseId") val warehouseId: String = "",
    /** "Ship" or "Receive". */
    @SerialName("Mode") val mode: String,
)

@Serializable
data class TransferLineServiceRequest(
    @SerialName("_request") val request: TransferLineRequestBody,
)

@Serializable
data class TransferLineRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("TransferId") val transferId: String,
    @SerialName("ItemId") val itemId: String,
    @SerialName("Mode") val mode: String,
)

@Serializable
data class TransferServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    /**
     * NOT_FOUND, WRONG_WAREHOUSE, NOT_SHIPPABLE, NOT_RECEIVABLE, NOTHING_OUTSTANDING,
     * NOT_ON_ORDER, INVALID_REQUEST.
     */
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
    @SerialName("TransferId") val transferId: String = "",
    @SerialName("TransferStatus") val transferStatus: String? = null,
    /** Whether the requested direction can be worked now. Decided server-side. */
    @SerialName("Actionable") val actionable: Boolean = false,
    @SerialName("FromSiteId") val fromSiteId: String? = null,
    @SerialName("FromWarehouseId") val fromWarehouseId: String? = null,
    @SerialName("ToSiteId") val toSiteId: String? = null,
    @SerialName("ToWarehouseId") val toWarehouseId: String? = null,
    @SerialName("OutstandingLineCount") val outstandingLineCount: Int = 0,
    /** False when the order is too large to send lines inline; ask per item instead. */
    @SerialName("LinesIncluded") val linesIncluded: Boolean = false,
    @SerialName("Lines") val lines: List<TransferLineDto> = emptyList(),
    /**
     * Receive direction only - see the identical field on PurchOrderServiceResponse and
     * HSMobileOpsProcessSetup.RequireLocationCapture. Defaults true so an old server that has
     * not deployed this field, deserializing it as absent, changes nothing about existing
     * behaviour.
     */
    @SerialName("RequireLocationCapture") val requireLocationCapture: Boolean = true,
)

@Serializable
data class TransferLineDto(
    @SerialName("LineNumber") val lineNumber: Double = 0.0,
    @SerialName("ItemId") val itemId: String = "",
    @SerialName("ItemName") val itemName: String = "",
    @SerialName("UnitId") val unitId: String = "",
    @SerialName("TransferQty") val transferQty: Double = 0.0,
    @SerialName("ShippedQty") val shippedQty: Double = 0.0,
    @SerialName("ReceivedQty") val receivedQty: Double = 0.0,
    /**
     * Outstanding **for the requested direction**.
     *
     * Not the ordered quantity. Receiving is bounded by what was shipped, so a ten-unit line with
     * four shipped has six outstanding to ship and four to receive — reading the ordered quantity
     * at the receiving end would invite booking in stock that never left the source site.
     */
    @SerialName("RemainingQty") val remainingQty: Double = 0.0,
    @SerialName("FromSiteId") val fromSiteId: String? = null,
    @SerialName("FromWarehouseId") val fromWarehouseId: String? = null,
    @SerialName("ToSiteId") val toSiteId: String? = null,
    @SerialName("ToWarehouseId") val toWarehouseId: String? = null,
    /** The line's own location on the source side. A hint when shipping; never an answer. */
    @SerialName("LocationId") val locationId: String? = null,
    @SerialName("ConfigId") val configId: String? = null,
    @SerialName("SizeId") val sizeId: String? = null,
    @SerialName("ColorId") val colorId: String? = null,
    @SerialName("StyleId") val styleId: String? = null,
    @SerialName("BatchRequired") val batchRequired: Boolean = false,
    @SerialName("SerialRequired") val serialRequired: Boolean = false,
    @SerialName("LocationRequired") val locationRequired: Boolean = false,
    @SerialName("BatchMandatory") val batchMandatory: Boolean = true,
    @SerialName("SerialMandatory") val serialMandatory: Boolean = true,
    @SerialName("LocationMandatory") val locationMandatory: Boolean = true,
    @SerialName("SerialPerUnit") val serialPerUnit: Boolean = true,
    /** Units the quantity can be entered in, to UnitId - empty from a server without UoM support. */
    @SerialName("Units") val units: List<ItemUnitDto> = emptyList(),
)
