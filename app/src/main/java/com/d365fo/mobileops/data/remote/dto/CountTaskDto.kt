package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// -- HSMobileOpsCountTaskSvc (see HSMobileOpsCountTaskService's class doc) -------------------
//
// Every response carries Success/ErrorCode. The codes the app acts on:
// CLAIMED_BY_OTHER, CLAIM_EXPIRED, CLAIM_LOST, JOURNAL_POSTED, JOURNAL_NOT_FOUND, TOO_MANY_LINES,
// INVALID_REQUEST, NOT_IMPLEMENTED (the server does not offer this operation yet).

/** One active claim, inside a journal list row. [expiresUtc] has no zone suffix and is UTC. */
@Serializable
data class CountClaimRowDto(
    @SerialName("ClaimedBy") val claimedBy: String? = null,
    @SerialName("DeviceId") val deviceId: String? = null,
    @SerialName("RangeId") val rangeId: String? = null,
    @SerialName("LocationFrom") val locationFrom: String? = null,
    @SerialName("LocationTo") val locationTo: String? = null,
    @SerialName("ExpiresUtc") val expiresUtc: String? = null,
)

/** One claimable location range of a wall-to-wall journal. */
@Serializable
data class CountRangeRowDto(
    @SerialName("RangeId") val rangeId: String = "",
    @SerialName("Label") val label: String? = null,
    @SerialName("LocationFrom") val locationFrom: String? = null,
    @SerialName("LocationTo") val locationTo: String? = null,
    @SerialName("LineCount") val lineCount: Int = 0,
)

@Serializable
data class CountClaimServiceRequest(@SerialName("_request") val request: CountClaimRequestBody)

@Serializable
data class CountClaimRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("JournalId") val journalId: String,
    @SerialName("UserId") val userId: String,
    @SerialName("DeviceId") val deviceId: String,
    @SerialName("RangeId") val rangeId: String? = null,
    @SerialName("LocationFrom") val locationFrom: String? = null,
    @SerialName("LocationTo") val locationTo: String? = null,
)

@Serializable
data class CountClaimTokenServiceRequest(@SerialName("_request") val request: CountClaimTokenRequestBody)

@Serializable
data class CountClaimTokenRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("JournalId") val journalId: String,
    @SerialName("ClaimToken") val claimToken: String,
    @SerialName("UserId") val userId: String,
    @SerialName("DeviceId") val deviceId: String,
)

@Serializable
data class CountClaimServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
    @SerialName("ClaimToken") val claimToken: String? = null,
    @SerialName("ClaimedBy") val claimedBy: String? = null,
    @SerialName("RangeId") val rangeId: String? = null,
    @SerialName("LocationFrom") val locationFrom: String? = null,
    @SerialName("LocationTo") val locationTo: String? = null,
    @SerialName("ExpiresUtc") val expiresUtc: String? = null,
    @SerialName("HeartbeatSeconds") val heartbeatSeconds: Int = 0,
)

@Serializable
data class JournalStateServiceRequest(@SerialName("_request") val request: JournalStateRequestBody)

@Serializable
data class JournalStateRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("JournalId") val journalId: String,
)

@Serializable
data class JournalStateServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    /** OPEN, POSTED or NOT_FOUND. */
    @SerialName("State") val state: String? = null,
)

@Serializable
data class DirectLinesServiceRequest(@SerialName("_request") val request: DirectLinesRequestBody)

@Serializable
data class DirectLinesRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("JournalId") val journalId: String,
    @SerialName("ClaimToken") val claimToken: String? = null,
    @SerialName("LocationFrom") val locationFrom: String? = null,
    @SerialName("LocationTo") val locationTo: String? = null,
)

/** Blind by contract: no on-hand, counted or variance quantity. */
@Serializable
data class DirectLineRowDto(
    @SerialName("LineNumber") val lineNumber: Double = 0.0,
    @SerialName("ItemNumber") val itemNumber: String = "",
    @SerialName("ItemName") val itemName: String? = null,
    @SerialName("SiteId") val siteId: String? = null,
    @SerialName("WarehouseId") val warehouseId: String? = null,
    @SerialName("LocationId") val locationId: String? = null,
    @SerialName("BatchId") val batchId: String? = null,
    @SerialName("SerialId") val serialId: String? = null,
    @SerialName("PassNumber") val passNumber: Int = 1,
    @SerialName("Recount") val recount: Boolean = false,
)

@Serializable
data class DirectLinesServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Lines") val lines: List<DirectLineRowDto> = emptyList(),
)

@Serializable
data class DirectSubmitServiceRequest(@SerialName("_request") val request: DirectSubmitRequestBody)

@Serializable
data class DirectSubmitRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("JournalId") val journalId: String,
    @SerialName("ClaimToken") val claimToken: String? = null,
    @SerialName("SubmissionId") val submissionId: String,
    @SerialName("UserId") val userId: String,
    @SerialName("DeviceId") val deviceId: String,
    @SerialName("StartedAtUtc") val startedAtUtc: String? = null,
    @SerialName("SubmittedAtUtc") val submittedAtUtc: String,
    @SerialName("PassNumber") val passNumber: Int,
    @SerialName("OriginType") val originType: String,
    @SerialName("Lines") val lines: List<DirectCountRowDto>,
)

@Serializable
data class DirectCountRowDto(
    @SerialName("LineNumber") val lineNumber: Double,
    @SerialName("CountedQuantity") val countedQuantity: Double = 0.0,
    @SerialName("MarkedOnly") val markedOnly: Boolean = false,
    @SerialName("CountedBy") val countedBy: String? = null,
    @SerialName("CountedAtUtc") val countedAtUtc: String? = null,
    @SerialName("CountStartedAtUtc") val countStartedAtUtc: String? = null,
)

@Serializable
data class DirectSubmitServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
    @SerialName("AcceptedLines") val acceptedLines: Int = 0,
    @SerialName("ThirdCountNeeded") val thirdCountNeeded: Boolean = false,
    @SerialName("PassResult") val passResult: String? = null,
)

@Serializable
data class CountExceptionsServiceRequest(@SerialName("_request") val request: CountExceptionsRequestBody)

@Serializable
data class CountExceptionsRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("JournalId") val journalId: String,
    @SerialName("ClaimToken") val claimToken: String? = null,
    @SerialName("SubmissionId") val submissionId: String,
    @SerialName("UserId") val userId: String,
    @SerialName("DeviceId") val deviceId: String,
    @SerialName("Exceptions") val exceptions: List<CountExceptionRowDto>,
    @SerialName("EmptyLocations") val emptyLocations: List<CountEmptyLocationRowDto>,
)

@Serializable
data class CountExceptionRowDto(
    @SerialName("ClientId") val clientId: String,
    @SerialName("ItemNumber") val itemNumber: String,
    @SerialName("LocationId") val locationId: String? = null,
    @SerialName("Quantity") val quantity: Double,
    @SerialName("BatchId") val batchId: String? = null,
    @SerialName("SerialId") val serialId: String? = null,
    @SerialName("Note") val note: String? = null,
    @SerialName("CapturedBy") val capturedBy: String? = null,
    @SerialName("CapturedAtUtc") val capturedAtUtc: String,
)

@Serializable
data class CountEmptyLocationRowDto(
    @SerialName("LocationId") val locationId: String,
    @SerialName("VerifiedBy") val verifiedBy: String? = null,
    @SerialName("VerifiedAtUtc") val verifiedAtUtc: String,
)

@Serializable
data class CountExceptionsServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
    @SerialName("Accepted") val accepted: Int = 0,
)
