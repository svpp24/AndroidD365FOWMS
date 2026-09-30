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
}
