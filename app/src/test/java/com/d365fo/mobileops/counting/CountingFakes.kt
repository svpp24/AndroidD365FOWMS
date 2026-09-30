package com.d365fo.mobileops.counting

import com.d365fo.mobileops.data.counting.ClaimStore
import com.d365fo.mobileops.data.counting.CountCaptureStore
import com.d365fo.mobileops.data.local.dao.CountLine
import com.d365fo.mobileops.data.local.entity.CountExceptionEntity
import com.d365fo.mobileops.data.local.entity.CountLocationCheckEntity
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
    val exceptions = mutableListOf<CountExceptionEntity>()
    val locationChecks = mutableListOf<CountLocationCheckEntity>()

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
    override suspend fun markCountsDone(journalId: String, warning: String?) {
        rows[journalId] = rows.getValue(journalId).copy(countsDone = true, countsWarning = warning)
    }
    override suspend fun unsentExceptions(journalId: String) = exceptions.filter { it.journalId == journalId && it.sentAt == null }
    override suspend fun unsentLocationChecks(journalId: String) = locationChecks.filter { it.journalId == journalId && it.sentAt == null }
    override suspend fun markExceptionsSent(journalId: String) {
        exceptions.replaceAll { if (it.journalId == journalId && it.sentAt == null) it.copy(sentAt = 1L, sendError = null) else it }
        locationChecks.replaceAll { if (it.journalId == journalId) it.copy(sentAt = 1L) else it }
    }
    override suspend fun markExceptionsFailed(journalId: String, error: String) {
        exceptions.replaceAll { if (it.journalId == journalId && it.sentAt == null) it.copy(sendError = error) else it }
    }

    fun addException(journalId: String, itemId: String = "UNEXPECTED-1") {
        exceptions += CountExceptionEntity(
            clientId = "ex-${exceptions.size + 1}",
            journalId = journalId,
            itemId = itemId,
            locationId = "A-01",
            quantity = "3",
            batchId = null,
            serialId = null,
            note = null,
            capturedBy = "counter1",
            capturedAt = 1_500L,
        )
    }
}

class FakeCountCaptureStore(lines: List<CountLine>) : CountCaptureStore {
    val lines = lines.toMutableList()
    val checks = mutableListOf<CountLocationCheckEntity>()
    val exceptions = mutableListOf<CountExceptionEntity>()

    override suspend fun lines(journalId: String) = lines.filter { it.journalId == journalId }
    override suspend fun recordCount(journalLineId: String, quantity: String, countedBy: String?, at: Long) {
        lines.replaceAll {
            if (it.journalLineId == journalLineId) it.copy(countedQuantity = quantity, countedBy = countedBy, countedAt = at) else it
        }
    }
    override suspend fun saveLocationCheck(check: CountLocationCheckEntity) { checks += check }
    override suspend fun saveException(exception: CountExceptionEntity) { exceptions += exception }
    override suspend fun deleteUnsentException(clientId: String) { exceptions.removeAll { it.clientId == clientId && it.sentAt == null } }
}

fun countLine(id: Int, item: String, location: String?, counted: String? = null) = CountLine(
    journalLineId = "CNT-1-$id",
    journalId = "CNT-1",
    lineNumber = id,
    itemId = item,
    itemName = item,
    locationId = location,
    batchId = null,
    serialId = null,
    countedQuantity = counted,
    markedCounted = false,
    countedBy = null,
    countedAt = null,
)

class FakeCountTransport : CountTransport {
    var start: TransportResult<String> = TransportResult.Ok("exec-1")
    val polls = ArrayDeque<TransportResult<ImportJobStatus>>()
    var direct: TransportResult<Int> = TransportResult.Ok(1)
    var exceptions: TransportResult<Int> = TransportResult.Ok(1)
    val sentExceptions = mutableListOf<List<CountExceptionEntity>>()
    val sentEmptyLocations = mutableListOf<List<CountLocationCheckEntity>>()

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

    override suspend fun submitExceptions(
        journalId: String,
        audit: SubmissionAudit,
        claimToken: String?,
        exceptions: List<CountExceptionEntity>,
        emptyLocations: List<CountLocationCheckEntity>,
    ): TransportResult<Int> {
        sentExceptions += exceptions
        sentEmptyLocations += emptyLocations
        return this.exceptions
    }
}

/** What the live transport would produce for these failures - see AppFailure.toTransport. */
fun offline() = AppFailure.Offline()
fun tooManyRequests() = AppFailure.Transient("HTTP 429", httpCode = 429, retryAfterSeconds = 5)
fun businessRule(code: String) = AppFailure.BusinessRule(code, d365ErrorCode = code)
