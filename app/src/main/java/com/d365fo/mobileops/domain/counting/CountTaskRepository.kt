package com.d365fo.mobileops.domain.counting

import com.d365fo.mobileops.domain.model.CountRange
import com.d365fo.mobileops.util.NetworkResult

/** Why the server refused a claim or heartbeat. Every value is a definite answer, never retried. */
enum class ClaimRefusal {
    CLAIMED_BY_OTHER,
    EXPIRED,
    LOST,
    JOURNAL_POSTED,
    JOURNAL_NOT_FOUND,

    /** A recount claimed by the person who counted before - the server requires someone else. */
    SAME_COUNTER,

    /** The server does not offer claims yet (NOT_IMPLEMENTED, or the service is not deployed). */
    NOT_AVAILABLE,
    OTHER,
}

sealed interface ClaimOutcome {
    data class Granted(
        val claimToken: String,
        val claimedBy: String,
        val rangeId: String?,
        val locationFrom: String?,
        val locationTo: String?,
        val expiresAt: Long?,
        val heartbeatSeconds: Int,
    ) : ClaimOutcome

    data class Refused(val reason: ClaimRefusal, val claimedBy: String?, val message: String?) : ClaimOutcome

    /** Could not reach the server (offline, timeout, 429, 5xx). Says nothing about the claim itself. */
    data class Unreachable(val message: String) : ClaimOutcome
}

enum class JournalRemoteState { OPEN, POSTED, NOT_FOUND }

/** A one-line count the server offers after a pick or putaway - counted blind, DIRECT mode. */
data class OpportunityTask(
    val journalId: String,
    val journalNameId: String,
    val description: String?,
    val lineNumber: Int,
    val itemId: String,
    val itemName: String,
    val locationId: String?,
    val batchId: String?,
    val serialId: String?,
    val siteId: String?,
    val warehouseId: String?,
)

/** A definite answer or a connection failure, for one-off requests (spot, discrepancy). */
sealed interface TaskResult<out T> {
    data class Ok<T>(val value: T) : TaskResult<T>
    data class Refused(val code: String?, val message: String) : TaskResult<Nothing>
    data class Unreachable(val message: String) : TaskResult<Nothing>
}

/** What kind of counter the signed-in worker is - from the server, on entering counting. */
data class CounterProfile(
    val isExternalCounter: Boolean,
    val counterTeam: String?,
    val isCycleCountSupervisor: Boolean,
)

/** Location type, owner and verify-only for one line, as the server describes it. */
data class LineAttributes(
    val lineNumber: Int,
    val locationType: String,
    val owner: String?,
    val verifyOnly: Boolean,
)

enum class DiscrepancyReason(val label: String) {
    SHORT_PICK("Less than expected"),
    DAMAGED("Damaged stock"),
    WRONG_ITEM("Wrong item in location"),
    OTHER("Other"),
}

/**
 * Claims and journal state against `HSMobileOpsCountTaskSvc`. Every rule (TTL, who may hold a
 * range, when a claim is lost) is the server's; this only carries requests and answers.
 */
interface CountTaskRepository {
    suspend fun claim(journalId: String, range: CountRange?): ClaimOutcome
    suspend fun heartbeat(journalId: String, claimToken: String): ClaimOutcome

    /** Best effort; true when the server confirmed it (or the claim was already gone). */
    suspend fun release(journalId: String, claimToken: String): Boolean
    suspend fun journalState(journalId: String): NetworkResult<JournalRemoteState>

    suspend fun counterProfile(): NetworkResult<CounterProfile>

    /** Lines not listed are PHYSICAL, own stock, counted normally. */
    suspend fun lineAttributes(journalId: String): NetworkResult<List<LineAttributes>>

    /** Creates a spot count on the server; returns the new journal id. */
    suspend fun createSpotCount(warehouseId: String, itemId: String?, locationId: String?): TaskResult<String>

    /** Null when the server has nothing to offer here - including when it can't be reached. */
    suspend fun opportunityCount(warehouseId: String, locationId: String, itemId: String?): OpportunityTask?

    /** Returns the triggered journal id when the server created one, null when it didn't. */
    suspend fun reportDiscrepancy(
        warehouseId: String,
        locationId: String,
        itemId: String?,
        reason: DiscrepancyReason,
        note: String?,
    ): TaskResult<String?>
}
