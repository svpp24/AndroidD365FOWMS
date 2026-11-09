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

/** Wall-to-wall: one location of the count, to complete (or confirm empty). */
@Serializable
data class CountLocationServiceRequest(@SerialName("_request") val request: CountLocationRequestBody)

@Serializable
data class CountLocationRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("JournalId") val journalId: String,
    @SerialName("UserId") val userId: String,
    @SerialName("DeviceId") val deviceId: String,
    @SerialName("LocationId") val locationId: String,
)

@Serializable
data class CountLocationServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
    /** Locations of the count not complete yet. */
    @SerialName("OpenTasks") val openTasks: Int = 0,
)

/** Is a warehouse frozen for a wall-to-wall count? Asked before a stock movement starts. */
@Serializable
data class FreezeStatusServiceRequest(@SerialName("_request") val request: FreezeStatusRequestBody)

@Serializable
data class FreezeStatusRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("WarehouseId") val warehouseId: String,
)

@Serializable
data class FreezeStatusServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Frozen") val frozen: Boolean = false,
    @SerialName("JournalId") val journalId: String? = null,
    @SerialName("Status") val status: String? = null,
    /** The text a refused movement shows, from D365FO. */
    @SerialName("Message") val message: String? = null,
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
    /** Dual count: whose lines these are, so a line they have counted is left out. */
    @SerialName("UserId") val userId: String? = null,
    /** Dual count pages: the last line of the page before. */
    @SerialName("FromLine") val fromLine: Double = 0.0,
)

@Serializable
data class CountBinServiceRequest(@SerialName("_request") val request: CountBinRequestBody)

@Serializable
data class CountBinRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("WarehouseId") val warehouseId: String,
    @SerialName("UserId") val userId: String,
    @SerialName("BinId") val binId: String? = null,
    /** addCountBin: the location being counted - the new bin is in it. */
    @SerialName("LocationId") val locationId: String? = null,
)

@Serializable
data class CountBinRowDto(
    @SerialName("BinId") val binId: String = "",
    @SerialName("Description") val description: String? = null,
    @SerialName("LocationId") val locationId: String? = null,
)

@Serializable
data class CountBinServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
    @SerialName("Accepted") val accepted: Int = 0,
    @SerialName("Bins") val bins: List<CountBinRowDto> = emptyList(),
)

@Serializable
data class CountBinLinesServiceRequest(@SerialName("_request") val request: CountBinLinesRequestBody)

@Serializable
data class CountBinLinesRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("JournalId") val journalId: String,
    @SerialName("UserId") val userId: String,
    @SerialName("SubmissionId") val submissionId: String,
    /** [rowsJson] of [CountBinLineRowDto]s. */
    @SerialName("RowsJson") val rowsJson: String,
)

@Serializable
data class CountBinLineRowDto(
    @SerialName("LineNumber") val lineNumber: Double,
    @SerialName("BinId") val binId: String,
    @SerialName("Quantity") val quantity: Double,
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
    @SerialName("UnitId") val unitId: String? = null,
    @SerialName("ConfigId") val configId: String? = null,
    @SerialName("SizeId") val sizeId: String? = null,
    @SerialName("ColorId") val colorId: String? = null,
    @SerialName("StyleId") val styleId: String? = null,
    @SerialName("PassNumber") val passNumber: Int = 1,
    @SerialName("Recount") val recount: Boolean = false,
    @SerialName("LocationType") val locationType: String? = null,
    @SerialName("Owner") val owner: String? = null,
    @SerialName("VerifyOnly") val verifyOnly: Boolean = false,
)

@Serializable
data class DirectLinesServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Lines") val lines: List<DirectLineRowDto> = emptyList(),
    /** Above zero: there are more lines, ask again from this one. */
    @SerialName("NextLine") val nextLine: Double = 0.0,
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
    /** The counted lines: [rowsJson] of [DirectCountRowDto]s. */
    @SerialName("LinesJson") val linesJson: String,
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
    @SerialName("MatchedLines") val matchedLines: Int = 0,
    @SerialName("MismatchedLines") val mismatchedLines: Int = 0,
    @SerialName("SameUserLines") val sameUserLines: Int = 0,
    @SerialName("SettledLines") val settledLines: Int = 0,
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
    /** [rowsJson] of [CountExceptionRowDto]s. */
    @SerialName("ExceptionsJson") val exceptionsJson: String,
    /** [rowsJson] of [CountEmptyLocationRowDto]s. */
    @SerialName("EmptyLocationsJson") val emptyLocationsJson: String,
    /** [rowsJson] of [CountConfirmationRowDto]s. */
    @SerialName("ConfirmationsJson") val confirmationsJson: String = "[]",
)

@Serializable
data class CountConfirmationRowDto(
    @SerialName("LineNumber") val lineNumber: Double,
    @SerialName("ConfirmedBy") val confirmedBy: String? = null,
    @SerialName("ConfirmedAtUtc") val confirmedAtUtc: String,
)

@Serializable
data class CountExceptionRowDto(
    @SerialName("ClientId") val clientId: String,
    @SerialName("ItemNumber") val itemNumber: String,
    @SerialName("ConfigId") val configId: String? = null,
    @SerialName("SizeId") val sizeId: String? = null,
    @SerialName("ColorId") val colorId: String? = null,
    @SerialName("StyleId") val styleId: String? = null,
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

@Serializable
data class OpportunityServiceRequest(@SerialName("_request") val request: OpportunityRequestBody)

@Serializable
data class OpportunityRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("UserId") val userId: String,
    @SerialName("WarehouseId") val warehouseId: String,
    @SerialName("LocationId") val locationId: String,
    @SerialName("ItemNumber") val itemNumber: String? = null,
)

@Serializable
data class OpportunityServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Eligible") val eligible: Boolean = false,
    @SerialName("JournalId") val journalId: String? = null,
    @SerialName("JournalNameId") val journalNameId: String? = null,
    @SerialName("Description") val description: String? = null,
    @SerialName("Line") val line: DirectLineRowDto? = null,
)

@Serializable
data class DiscrepancyServiceRequest(@SerialName("_request") val request: DiscrepancyRequestBody)

@Serializable
data class DiscrepancyRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("UserId") val userId: String,
    @SerialName("DeviceId") val deviceId: String,
    @SerialName("WarehouseId") val warehouseId: String,
    @SerialName("LocationId") val locationId: String,
    @SerialName("ItemNumber") val itemNumber: String? = null,
    @SerialName("Reason") val reason: String,
    @SerialName("Note") val note: String? = null,
)

@Serializable
data class DiscrepancyServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
    @SerialName("TriggeredJournalId") val triggeredJournalId: String? = null,
)

@Serializable
data class CounterProfileServiceRequest(@SerialName("_request") val request: CounterProfileRequestBody)

@Serializable
data class CounterProfileRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("UserId") val userId: String,
)

@Serializable
data class CounterProfileServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("IsExternalCounter") val isExternalCounter: Boolean = false,
    @SerialName("CounterTeam") val counterTeam: String? = null,
    @SerialName("IsCycleCountSupervisor") val isCycleCountSupervisor: Boolean = false,
)

@Serializable
data class LineAttributesServiceRequest(@SerialName("_request") val request: LineAttributesRequestBody)

@Serializable
data class LineAttributesRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("JournalId") val journalId: String,
    @SerialName("UserId") val userId: String,
)

@Serializable
data class LineAttributeRowDto(
    @SerialName("LineNumber") val lineNumber: Double = 0.0,
    @SerialName("LocationType") val locationType: String? = null,
    @SerialName("Owner") val owner: String? = null,
    @SerialName("VerifyOnly") val verifyOnly: Boolean = false,
)

@Serializable
data class LineAttributesServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Lines") val lines: List<LineAttributeRowDto> = emptyList(),
)
