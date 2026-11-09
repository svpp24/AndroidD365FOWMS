package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// -- HSMobileOpsCountSupervisorSvc - supervisor only. Separate DTOs on purpose: nothing here may
// ever be reused by a counter-facing call (blind counting).

@Serializable
data class VarianceServiceRequest(@SerialName("_request") val request: VarianceRequestBody)

@Serializable
data class VarianceRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("JournalId") val journalId: String,
    @SerialName("UserId") val userId: String,
)

@Serializable
data class VarianceLineDto(
    @SerialName("LineNumber") val lineNumber: Double = 0.0,
    @SerialName("ItemNumber") val itemNumber: String = "",
    @SerialName("ItemName") val itemName: String? = null,
    @SerialName("LocationId") val locationId: String? = null,
    @SerialName("BatchId") val batchId: String? = null,
    @SerialName("SerialId") val serialId: String? = null,
    @SerialName("Counted") val counted: Boolean = false,
    @SerialName("CountedQty") val countedQty: Double = 0.0,
    @SerialName("SystemQty") val systemQty: Double = 0.0,
    @SerialName("VarianceQty") val varianceQty: Double = 0.0,
    @SerialName("VarianceValue") val varianceValue: Double = 0.0,
    @SerialName("ToleranceStatus") val toleranceStatus: String? = null,
    @SerialName("AbcClass") val abcClass: String? = null,
    @SerialName("CountedBy") val countedBy: String? = null,
    @SerialName("ReviewStatus") val reviewStatus: String? = null,
    @SerialName("InitialQty") val initialQty: Double? = null,
    @SerialName("RecountQty") val recountQty: Double = 0.0,
    @SerialName("Recounted") val recounted: Boolean = false,
    @SerialName("ReasonCode") val reasonCode: String? = null,
    @SerialName("Comment") val comment: String? = null,
    @SerialName("ReviewedBy") val reviewedBy: String? = null,
)

@Serializable
data class CountReasonCodeDto(
    @SerialName("Code") val code: String = "",
    @SerialName("Description") val description: String? = null,
)

@Serializable
data class VarianceServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("CurrencyCode") val currencyCode: String? = null,
    @SerialName("Lines") val lines: List<VarianceLineDto> = emptyList(),
    @SerialName("ToleranceText") val toleranceText: String? = null,
    @SerialName("ReasonCodes") val reasonCodes: List<CountReasonCodeDto> = emptyList(),
)

@Serializable
data class ReviewServiceRequest(@SerialName("_request") val request: ReviewRequestBody)

@Serializable
data class ReviewRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("JournalId") val journalId: String,
    @SerialName("UserId") val userId: String,
    @SerialName("Action") val action: String,
    @SerialName("AssignTo") val assignTo: String? = null,
    @SerialName("Note") val note: String? = null,
    @SerialName("ReasonCode") val reasonCode: String? = null,
    /** [rowsJson] of the line numbers - a List of reals binds on the way in, but cannot be used. */
    @SerialName("LineNumbersJson") val lineNumbersJson: String,
)

@Serializable
data class PostJournalServiceRequest(@SerialName("_request") val request: PostJournalRequestBody)

@Serializable
data class PostJournalRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("JournalId") val journalId: String,
    @SerialName("UserId") val userId: String,
)

@Serializable
data class CountAttemptDto(
    @SerialName("AttemptNo") val attemptNo: Int = 0,
    @SerialName("CountedQty") val countedQty: Double = 0.0,
    @SerialName("UserId") val userId: String? = null,
    @SerialName("CountedDateTime") val countedDateTime: String? = null,
)

@Serializable
data class VarianceSummaryLineDto(
    @SerialName("LineNumber") val lineNumber: Double = 0.0,
    @SerialName("ItemNumber") val itemNumber: String = "",
    @SerialName("ItemName") val itemName: String? = null,
    @SerialName("LocationId") val locationId: String? = null,
    @SerialName("BatchId") val batchId: String? = null,
    @SerialName("SerialId") val serialId: String? = null,
    @SerialName("ConfigId") val configId: String? = null,
    @SerialName("SizeId") val sizeId: String? = null,
    @SerialName("ColorId") val colorId: String? = null,
    @SerialName("StyleId") val styleId: String? = null,
    @SerialName("Status") val status: String? = null,
    @SerialName("SystemQty") val systemQty: Double = 0.0,
    @SerialName("NeedsApproval") val needsApproval: Boolean = false,
    @SerialName("Attempts") val attempts: List<CountAttemptDto> = emptyList(),
)

@Serializable
data class VarianceSummaryServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("TotalLines") val totalLines: Int = 0,
    @SerialName("OpenLines") val openLines: Int = 0,
    @SerialName("FirstCounted") val firstCounted: Int = 0,
    @SerialName("Matched") val matched: Int = 0,
    @SerialName("Mismatch") val mismatch: Int = 0,
    @SerialName("Recount") val recount: Int = 0,
    @SerialName("Approved") val approved: Int = 0,
    @SerialName("EmptyConfirmed") val emptyConfirmed: Int = 0,
    @SerialName("Truncated") val truncated: Boolean = false,
    @SerialName("Lines") val lines: List<VarianceSummaryLineDto> = emptyList(),
)

@Serializable
data class ApproveLineServiceRequest(@SerialName("_request") val request: ApproveLineRequestBody)

@Serializable
data class ApproveLineRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("JournalId") val journalId: String,
    @SerialName("UserId") val userId: String,
    @SerialName("LineNumber") val lineNumber: Double,
    @SerialName("Action") val action: String,
    @SerialName("FinalQty") val finalQty: Double = 0.0,
    @SerialName("ReasonCode") val reasonCode: String? = null,
)

@Serializable
data class SupervisorServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
    @SerialName("Affected") val affected: Int = 0,
)

@Serializable
data class AccuracyServiceRequest(@SerialName("_request") val request: AccuracyRequestBody)

@Serializable
data class AccuracyRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("UserId") val userId: String,
    @SerialName("WarehouseId") val warehouseId: String,
    /** ISO date, yyyy-MM-dd, inclusive. */
    @SerialName("FromDate") val fromDate: String,
    @SerialName("ToDate") val toDate: String,
    @SerialName("GroupBy") val groupBy: String,
)

@Serializable
data class AccuracyRowDto(
    @SerialName("GroupKey") val groupKey: String = "",
    @SerialName("Label") val label: String? = null,
    @SerialName("CountedLines") val countedLines: Int = 0,
    @SerialName("AccurateLines") val accurateLines: Int = 0,
    @SerialName("AccuracyPct") val accuracyPct: Double = 0.0,
    @SerialName("VarianceValue") val varianceValue: Double = 0.0,
    @SerialName("Tolerance") val tolerance: String? = null,
)

@Serializable
data class AccuracyServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("CurrencyCode") val currencyCode: String? = null,
    @SerialName("CountedLines") val countedLines: Int = 0,
    @SerialName("AccurateLines") val accurateLines: Int = 0,
    @SerialName("OverallPct") val overallPct: Double = 0.0,
    @SerialName("Rows") val rows: List<AccuracyRowDto> = emptyList(),
)
