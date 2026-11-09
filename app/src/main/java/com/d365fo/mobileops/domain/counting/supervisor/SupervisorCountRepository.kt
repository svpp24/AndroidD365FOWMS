package com.d365fo.mobileops.domain.counting.supervisor

import com.d365fo.mobileops.util.NetworkResult

/**
 * A counting line as a supervisor sees it - counted, system and variance quantity and value and
 * the server's tolerance verdict. Supervisor-only: no counter-facing screen or model ever holds
 * one of these. Nothing here is computed on the device; every number comes from D365FO.
 */
data class VarianceLine(
    val lineNumber: Double,
    val itemNumber: String,
    val itemName: String,
    val locationId: String?,
    val batchId: String?,
    val serialId: String?,
    val counted: Boolean,
    val countedQty: Double,
    val systemQty: Double,
    val varianceQty: Double,
    val varianceValue: Double,
    /**
     * The line's count status, decided in X++ (HSMobileOpsCountSupervisorService::lineState) -
     * see [CountStatus]. Older servers send WITHIN / OVER / UNKNOWN.
     */
    val toleranceStatus: String,
    val abcClass: String?,
    val countedBy: String?,
    /** The last decision: PENDING, APPROVED, REJECTED, RECOUNT or INVESTIGATE. */
    val reviewStatus: String,
    /** The first count, kept once a decision has been made (else the current count). */
    val initialQty: Double = countedQty,
    /** Whether the recount asked for has been done, and its quantity. */
    val recounted: Boolean = false,
    val recountQty: Double = 0.0,
    /** Standard D365 counting reason code on the line, and the supervisor's comment. */
    val reasonCode: String? = null,
    val comment: String? = null,
    val reviewedBy: String? = null,
) {
    val status: CountStatus get() = CountStatus.from(toleranceStatus)

    /** Still needs a supervisor decision before the journal can post. */
    val overTolerance: Boolean get() = status.needsDecision
}

/** The count status lifecycle. Every value comes from the server; nothing is derived here. */
enum class CountStatus(val label: String, val needsDecision: Boolean) {
    NOT_COUNTED("Not counted", false),
    MATCH("Match", false),
    MINOR_VARIANCE("Minor variance", false),
    RECOUNT_REQUIRED("Recount required", true),
    CONFIRMATION_REQUIRED("Confirmation required", true),
    INVESTIGATE("Investigate", true),
    APPROVED("Approved", false),
    CLOSED("Closed", false),
    UNKNOWN("Unknown", false),
    ;

    companion object {
        fun from(code: String?): CountStatus = when (code) {
            "OVER" -> RECOUNT_REQUIRED
            "WITHIN" -> MINOR_VARIANCE
            else -> entries.firstOrNull { it.name == code } ?: UNKNOWN
        }
    }
}

/** A standard D365 counting reason code (Inventory management > Setup > Counting reason codes). */
data class CountReasonCode(val code: String, val description: String)

data class JournalVariance(
    val currencyCode: String?,
    val lines: List<VarianceLine>,
    /** The company's tolerance, as the server words it. */
    val toleranceText: String? = null,
    val reasonCodes: List<CountReasonCode> = emptyList(),
)

enum class ReviewAction { APPROVE, REJECT, RECOUNT, INVESTIGATE }

enum class AccuracyGroupBy(val label: String) {
    LOCATION("Location"),
    ITEM("Item"),
    ABC_CLASS("ABC class"),
    COUNTER("Counter"),
}

/** One group of the accuracy report - every number from the server. */
data class AccuracyRow(
    val key: String,
    val label: String,
    val countedLines: Int,
    val accurateLines: Int,
    val accuracyPct: Double,
    val varianceValue: Double,
    /** The tolerance that applies to this group (per ABC class), as the server words it. */
    val tolerance: String?,
)

data class AccuracyReport(
    val currencyCode: String?,
    val countedLines: Int,
    val accurateLines: Int,
    val overallPct: Double,
    val rows: List<AccuracyRow>,
)

/** A refusal from a supervisor call, with the code the screen explains. */
enum class SupervisorRefusal {
    NOT_SUPERVISOR,
    ALREADY_POSTED,
    JOURNAL_NOT_FOUND,
    LINES_NEED_APPROVAL,
    INVALID_REASON_CODE,
    RECOUNT_SAME_USER,
    POST_FAILED,
    NOT_AVAILABLE,
    OTHER,
}

sealed interface SupervisorResult {
    /** [message]: what the server said it did, when it is more than done - "posting in batch". */
    data class Done(val affected: Int, val message: String? = null) : SupervisorResult
    data class Refused(val reason: SupervisorRefusal, val message: String?) : SupervisorResult
    data class Unreachable(val message: String) : SupervisorResult
}

/** `HSMobileOpsCountSupervisorSvc`. The server checks the caller is a supervisor; the app's gate is cosmetic. */
/** One count of a line. Supervisor only: no counter ever sees another person's count. */
data class CountAttempt(val attemptNo: Int, val countedQty: Double, val userId: String)

/** A dual count line that disagrees. */
data class DualCountLine(
    val lineNumber: Double,
    val itemNumber: String,
    val itemName: String,
    /** Location, batch, serial and product dimensions, as far as the line has them. */
    val where: String,
    val status: String,
    val systemQty: Double,
    /** Three counts disagree: only a supervisor can settle it. Otherwise a tiebreak can still. */
    val needsApproval: Boolean,
    val attempts: List<CountAttempt>,
)

data class DualCountReview(
    val total: Int,
    val notCounted: Int,
    val firstCounted: Int,
    val matched: Int,
    val mismatch: Int,
    val recount: Int,
    val approved: Int,
    val truncated: Boolean,
    val lines: List<DualCountLine>,
    /** Settled at 0 by "Location is empty" (wall-to-wall). */
    val emptyConfirmed: Int = 0,
) {
    val settled: Int get() = matched + approved + emptyConfirmed
    val canPost: Boolean get() = total > 0 && settled == total
}

interface SupervisorCountRepository {
    suspend fun dualCountReview(journalId: String): NetworkResult<DualCountReview> =
        NetworkResult.Error(com.d365fo.mobileops.util.AppFailure.Fatal("Not available"))

    /** [finalQty] null sends the line back for a recount; otherwise it becomes the count. */
    suspend fun settleDualLine(journalId: String, lineNumber: Double, finalQty: Double?): SupervisorResult =
        SupervisorResult.Unreachable("Not available")

    suspend fun variance(journalId: String): NetworkResult<JournalVariance>
    suspend fun review(
        journalId: String,
        lineNumbers: List<Double>,
        action: ReviewAction,
        assignTo: String?,
        note: String?,
        reasonCode: String?,
    ): SupervisorResult
    suspend fun post(journalId: String): SupervisorResult

    /** [from] / [to] are ISO dates (yyyy-MM-dd), inclusive. */
    suspend fun accuracy(warehouseId: String, from: String, to: String, groupBy: AccuracyGroupBy): NetworkResult<AccuracyReport>
}
