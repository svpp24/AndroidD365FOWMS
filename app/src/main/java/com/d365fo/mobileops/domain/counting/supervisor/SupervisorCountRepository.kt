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
    /** WITHIN, OVER or UNKNOWN - the server's verdict against the item's tolerance. */
    val toleranceStatus: String,
    val abcClass: String?,
    val countedBy: String?,
    /** PENDING, APPROVED, REJECTED or RECOUNT. */
    val reviewStatus: String,
) {
    val overTolerance: Boolean get() = toleranceStatus == "OVER"
}

data class JournalVariance(val currencyCode: String?, val lines: List<VarianceLine>)

enum class ReviewAction { APPROVE, REJECT, RECOUNT }

/** A refusal from a supervisor call, with the code the screen explains. */
enum class SupervisorRefusal {
    NOT_SUPERVISOR,
    ALREADY_POSTED,
    JOURNAL_NOT_FOUND,
    LINES_NEED_APPROVAL,
    RECOUNT_SAME_USER,
    POST_FAILED,
    NOT_AVAILABLE,
    OTHER,
}

sealed interface SupervisorResult {
    data class Done(val affected: Int) : SupervisorResult
    data class Refused(val reason: SupervisorRefusal, val message: String?) : SupervisorResult
    data class Unreachable(val message: String) : SupervisorResult
}

/** `HSMobileOpsCountSupervisorSvc`. The server checks the caller is a supervisor; the app's gate is cosmetic. */
interface SupervisorCountRepository {
    suspend fun variance(journalId: String): NetworkResult<JournalVariance>
    suspend fun review(journalId: String, lineNumbers: List<Double>, action: ReviewAction, assignTo: String?, note: String?): SupervisorResult
    suspend fun post(journalId: String): SupervisorResult
}
