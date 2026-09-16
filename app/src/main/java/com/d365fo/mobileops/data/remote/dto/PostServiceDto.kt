package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The contract for `HSMobileOpsPostingSvc` — `postProductReceipt`, `postTransferShipment` and
 * `postTransferReceipt`. All three take the same request and response shape; see
 * `aos/HSMobileOps/AxClass/HSMobileOpsPostRequest.xml` and `HSMobileOpsPostResponse.xml`.
 *
 * There is no separate idempotency header — [PostRequestBody.idempotencyKey] is the only copy,
 * and it is what `HSMobileOpsPostingService::postTransfer`/`postProductReceipt` key their
 * `HSMobileOpsPostLog` dedupe check on. See `aos/README.md` for the replay contract.
 */
@Serializable
data class PostServiceRequest(
    @SerialName("_request") val request: PostRequestBody,
)

@Serializable
data class PostRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("IdempotencyKey") val idempotencyKey: String,
    /** Purchase order number, or transfer order number depending on which operation is called. */
    @SerialName("SourceId") val sourceId: String,
    /** Vendor packing slip id for a receipt; blank for a transfer. */
    @SerialName("Reference") val reference: String = "",
    @SerialName("UserName") val userName: String,
    @SerialName("PersonnelNumber") val personnelNumber: String = "",
    @SerialName("DeviceId") val deviceId: String,
    /**
     * The captured lines, pre-serialized to a JSON array string rather than sent as a nested
     * array.
     *
     * D365FO's custom-service JSON endpoint could not bind an inbound array of a custom
     * DataContract to `List HSMobileOpsPostLine` — every call came back
     * `InvalidCastException: Unable to cast ... JObject to ... HSMobileOpsPostLine`, confirmed
     * live and not a stale-deployment artefact. A `str` field always deserializes correctly on
     * the AOS side; `HSMobileOpsPostingService::parseLines` reads this back out with
     * Newtonsoft.Json.Linq directly. See `aos/HSMobileOps/AxClass/HSMobileOpsPostRequest.xml`.
     */
    @SerialName("LinesJson") val linesJson: String,
)

@Serializable
data class PostLineDto(
    @SerialName("LineNumber") val lineNumber: Double,
    @SerialName("ItemId") val itemId: String,
    @SerialName("Quantity") val quantity: Double,
    @SerialName("UnitId") val unitId: String,
    @SerialName("SiteId") val siteId: String = "",
    @SerialName("WarehouseId") val warehouseId: String = "",
    @SerialName("LocationId") val locationId: String = "",
    @SerialName("BatchId") val batchId: String = "",
    /** yyyy-MM-dd. Set only when this batch was new to D365FO at capture time. */
    @SerialName("BatchManufacturingDate") val batchManufacturingDate: String = "",
    @SerialName("BatchExpiryDate") val batchExpiryDate: String = "",
    /**
     * One id per unit, or empty when the item is not serial-tracked. The AOS service refuses a
     * count mismatch between this list and [quantity] rather than guessing which serials cover
     * which units.
     */
    @SerialName("SerialIds") val serialIds: List<String> = emptyList(),
)

@Serializable
data class PostServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    /** Stable token — see HSMobileOpsPostingService for the full list. */
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
    /** The product receipt id or the transfer order number, once posted. */
    @SerialName("DocumentNumber") val documentNumber: String = "",
    /**
     * True when this exact idempotency key was already posted by an earlier attempt. Treated
     * as a success, never as a duplicate error — see PostingDispatcher.
     */
    @SerialName("AlreadyPosted") val alreadyPosted: Boolean = false,
)

/**
 * The contract for `HSMobileOpsPostingSvc/processPending` and `/getQueueStatus` - both take the
 * same shape (just which company) and return the same shape (a page of `HSMobileOpsPostQueue`
 * rows). `processPending` returns the rows it just worked; `getQueueStatus` returns the most
 * recent rows regardless of status. See `aos/HSMobileOps/AxClass/HSMobileOpsPostQueueRequest.xml`
 * and `HSMobileOpsPostQueueResponse.xml`.
 */
@Serializable
data class PostQueueServiceRequest(
    @SerialName("_request") val request: PostQueueRequestBody,
)

@Serializable
data class PostQueueRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
)

@Serializable
data class PostQueueServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("Rows") val rows: List<PostQueueEntryDto> = emptyList(),
)

@Serializable
data class PostQueueEntryDto(
    @SerialName("SourceId") val sourceId: String = "",
    /** "Registration", "TransferShipment" or "TransferReceipt". */
    @SerialName("DocumentType") val documentType: String = "",
    /** "Pending", "Registered" or "Failed". */
    @SerialName("Status") val status: String = "",
    @SerialName("ErrorMessage") val errorMessage: String? = null,
    @SerialName("ReceivedUtc") val receivedUtc: String? = null,
    @SerialName("ProcessedUtc") val processedUtc: String? = null,
)
