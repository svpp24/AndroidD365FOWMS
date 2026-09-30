package com.d365fo.mobileops.counting

import com.d365fo.mobileops.data.counting.ClaimStore
import com.d365fo.mobileops.data.counting.CountTransport
import com.d365fo.mobileops.data.counting.SubmissionAudit
import com.d365fo.mobileops.data.counting.SubmissionStore
import com.d365fo.mobileops.data.counting.TransportResult
import com.d365fo.mobileops.data.local.entity.CountClaimEntity
import com.d365fo.mobileops.data.local.entity.CountSubmissionEntity
import com.d365fo.mobileops.data.local.entity.CountSubmissionError
import com.d365fo.mobileops.data.local.entity.CountSubmissionState
import com.d365fo.mobileops.data.local.entity.TransferMode
import com.d365fo.mobileops.domain.counting.ClaimOutcome
import com.d365fo.mobileops.domain.counting.CountTaskRepository
import com.d365fo.mobileops.domain.counting.JournalRemoteState
import com.d365fo.mobileops.domain.model.CountRange
import com.d365fo.mobileops.domain.model.ImportJobStatus
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Server side of claims, scripted per test. */
class FakeCountTaskRepository : CountTaskRepository {
    var claimAnswer: (String, CountRange?) -> ClaimOutcome = { _, range -> granted(range) }
    var heartbeatAnswer: (String) -> ClaimOutcome = { granted(null) }
    var state: NetworkResult<JournalRemoteState> = NetworkResult.Success(JournalRemoteState.OPEN)

    val claimCalls = mutableListOf<Pair<String, CountRange?>>()
    val heartbeatCalls = mutableListOf<String>()
    val released = mutableListOf<String>()

    override suspend fun claim(journalId: String, range: CountRange?): ClaimOutcome {
        claimCalls += journalId to range
        return claimAnswer(journalId, range)
    }

    override suspend fun heartbeat(journalId: String, claimToken: String): ClaimOutcome {
        heartbeatCalls += journalId
        return heartbeatAnswer(journalId)
    }

    override suspend fun release(journalId: String, claimToken: String): Boolean {
        released += journalId
        return true
    }

    override suspend fun journalState(journalId: String) = state

    companion object {
        fun granted(range: CountRange?, expiresAt: Long? = 10_000_000L, token: String = "token-1") =
            ClaimOutcome.Granted(
                claimToken = token,
                claimedBy = "counter1",
                rangeId = range?.rangeId,
                locationFrom = range?.locationFrom,
                locationTo = range?.locationTo,
                expiresAt = expiresAt,
                heartbeatSeconds = 60,
            )
    }
}

class FakeClaimStore : ClaimStore {
    private val rows = MutableStateFlow<Map<String, CountClaimEntity>>(emptyMap())
    override fun observe(): Flow<List<CountClaimEntity>> = MutableStateFlow(rows.value.values.toList())
    override suspend fun get(journalId: String) = rows.value[journalId]
    override suspend fun active() = rows.value.values.filter { !it.lost }
    override suspend fun save(claim: CountClaimEntity) { rows.value = rows.value + (claim.journalId to claim) }
    override suspend fun recordHeartbeat(journalId: String, expiresAt: Long?, at: Long) {
        rows.value[journalId]?.let { save(it.copy(expiresAt = expiresAt, lastHeartbeatAt = at)) }
    }
    override suspend fun markLost(journalId: String, reason: String) {
        rows.value[journalId]?.let { save(it.copy(lost = true, lostReason = reason)) }
    }
    override suspend fun delete(journalId: String) { rows.value = rows.value - journalId }
}

class FakeSubmissionStore : SubmissionStore {
    val rows = mutableMapOf<String, CountSubmissionEntity>()
    val journalStatus = mutableMapOf<String, String>()

    fun queue(journalId: String, mode: TransferMode = TransferMode.DMF, executionId: String? = null) {
        rows[journalId] = CountSubmissionEntity(
            journalId = journalId,
            submissionId = "sub-$journalId",
            transferMode = mode,
            state = if (executionId == null) CountSubmissionState.QUEUED else CountSubmissionState.IMPORTING,
            executionId = executionId,
            userId = "counter1",
            deviceId = "device-1",
            originType = "SCHEDULED",
            passNumber = 1,
            startedAt = 1_000L,
            submittedAt = 2_000L,
            updatedAt = 2_000L,
        )
    }

    override suspend fun get(journalId: String) = rows[journalId]
    override suspend fun update(journalId: String, state: CountSubmissionState, error: CountSubmissionError?, message: String?, percent: Int?) {
        rows[journalId] = rows.getValue(journalId).copy(state = state, error = error, message = message, percent = percent)
    }
    override suspend fun recordExecutionId(journalId: String, executionId: String) {
        rows[journalId] = rows.getValue(journalId).copy(executionId = executionId, state = CountSubmissionState.IMPORTING)
    }
    override suspend fun countAttempt(journalId: String) {
        rows[journalId] = rows.getValue(journalId).let { it.copy(attempts = it.attempts + 1) }
    }
    override suspend fun markJournalSubmitted(journalId: String, warning: String?) { journalStatus[journalId] = "SUBMITTED" }
    override suspend fun markJournalFailed(journalId: String, message: String) { journalStatus[journalId] = "FAILED" }
}

class FakeCountTransport : CountTransport {
    var start: TransportResult<String> = TransportResult.Ok("exec-1")
    val polls = ArrayDeque<TransportResult<ImportJobStatus>>()
    var direct: TransportResult<Int> = TransportResult.Ok(1)

    var startCalls = 0
    val audits = mutableListOf<SubmissionAudit>()
    val claimTokens = mutableListOf<String?>()

    override suspend fun startDmfImport(journalId: String, audit: SubmissionAudit, claimToken: String?): TransportResult<String> {
        startCalls++
        audits += audit
        claimTokens += claimToken
        return start
    }

    override suspend fun pollDmfImport(executionId: String): TransportResult<ImportJobStatus> =
        polls.removeFirstOrNull() ?: TransportResult.Ok(ImportJobStatus.Running(null))

    override suspend fun submitDirect(journalId: String, audit: SubmissionAudit, claimToken: String?): TransportResult<Int> {
        audits += audit
        claimTokens += claimToken
        return direct
    }
}

/** What the live transport would produce for these failures - see AppFailure.toTransport. */
fun offline() = AppFailure.Offline()
fun tooManyRequests() = AppFailure.Transient("HTTP 429", httpCode = 429, retryAfterSeconds = 5)
fun businessRule(code: String) = AppFailure.BusinessRule(code, d365ErrorCode = code)
