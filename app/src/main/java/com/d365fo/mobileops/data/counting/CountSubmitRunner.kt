package com.d365fo.mobileops.data.counting

import com.d365fo.mobileops.data.local.dao.CountingDao
import com.d365fo.mobileops.data.local.dao.JournalDao
import com.d365fo.mobileops.data.local.entity.CountExceptionEntity
import com.d365fo.mobileops.data.local.entity.CountLocationCheckEntity
import com.d365fo.mobileops.data.local.entity.CountSubmissionEntity
import com.d365fo.mobileops.data.local.entity.CountSubmissionError
import com.d365fo.mobileops.data.local.entity.CountSubmissionState
import com.d365fo.mobileops.data.local.entity.JournalStatus
import com.d365fo.mobileops.data.local.entity.SyncState
import com.d365fo.mobileops.data.local.entity.TransferMode
import com.d365fo.mobileops.domain.model.ImportJobStatus
import com.d365fo.mobileops.util.AppFailure
import kotlinx.coroutines.delay
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Who submitted what, fixed when End count was pressed. Sent with every submission. */
data class SubmissionAudit(
    val submissionId: String,
    val userId: String,
    val deviceId: String,
    val startedAt: Long?,
    val submittedAt: Long,
    val passNumber: Int,
    val originType: String,
)

fun CountSubmissionEntity.audit() = SubmissionAudit(
    submissionId = submissionId,
    userId = userId,
    deviceId = deviceId,
    startedAt = startedAt,
    submittedAt = submittedAt,
    passNumber = passNumber,
    originType = originType,
)

sealed interface TransportResult<out T> {
    data class Ok<T>(val value: T) : TransportResult<T>

    /** Nothing definite happened (offline, timeout, 429, 5xx) - try again later. */
    data class Retry(val reason: String) : TransportResult<Nothing>

    /** A definite answer - never retried automatically. */
    data class Fail(val error: CountSubmissionError, val message: String) : TransportResult<Nothing>
}

/** The wire for End count, one implementation per transfer mode. Faked in tests. */
interface CountTransport {
    suspend fun startDmfImport(journalId: String, audit: SubmissionAudit, claimToken: String?): TransportResult<String>
    suspend fun pollDmfImport(executionId: String): TransportResult<ImportJobStatus>
    suspend fun submitDirect(journalId: String, audit: SubmissionAudit, claimToken: String?): TransportResult<DirectSubmitResult>
    suspend fun submitExceptions(
        journalId: String,
        audit: SubmissionAudit,
        claimToken: String?,
        exceptions: List<CountExceptionEntity>,
        emptyLocations: List<CountLocationCheckEntity>,
        confirmations: List<com.d365fo.mobileops.data.local.entity.JournalLineEntity>,
    ): TransportResult<Int>
}

/** Submission rows plus the journal status they drive. */
interface SubmissionStore {
    suspend fun get(journalId: String): CountSubmissionEntity?
    suspend fun update(journalId: String, state: CountSubmissionState, error: CountSubmissionError? = null, message: String? = null, percent: Int? = null)
    suspend fun recordExecutionId(journalId: String, executionId: String)
    suspend fun countAttempt(journalId: String)
    suspend fun markJournalSubmitted(journalId: String, warning: String?)
    suspend fun markJournalFailed(journalId: String, message: String)
    suspend fun markCountsDone(journalId: String, warning: String?)
    suspend fun recordPassResult(journalId: String, note: String?, thirdCountNeeded: Boolean)
    suspend fun unsentExceptions(journalId: String): List<CountExceptionEntity>
    suspend fun unsentLocationChecks(journalId: String): List<CountLocationCheckEntity>
    suspend fun unsentConfirmations(journalId: String): List<com.d365fo.mobileops.data.local.entity.JournalLineEntity>
    suspend fun markExceptionsSent(journalId: String)
    suspend fun markExceptionsFailed(journalId: String, error: String)
}

@Singleton
class RoomSubmissionStore @Inject constructor(
    private val countingDao: CountingDao,
    private val journalDao: JournalDao,
) : SubmissionStore {
    private fun now() = System.currentTimeMillis()
    override suspend fun get(journalId: String) = countingDao.getSubmission(journalId)
    override suspend fun update(journalId: String, state: CountSubmissionState, error: CountSubmissionError?, message: String?, percent: Int?) =
        countingDao.updateSubmission(journalId, state, error, message, percent, now())
    override suspend fun recordExecutionId(journalId: String, executionId: String) =
        countingDao.recordExecutionId(journalId, executionId, now())
    override suspend fun countAttempt(journalId: String) = countingDao.countAttempt(journalId)
    override suspend fun markJournalSubmitted(journalId: String, warning: String?) =
        journalDao.markCountingSubmitted(journalId, warning)
    override suspend fun markJournalFailed(journalId: String, message: String) =
        journalDao.updateStatus(journalId, JournalStatus.FAILED, SyncState.ERROR, message)
    override suspend fun markCountsDone(journalId: String, warning: String?) =
        countingDao.markCountsDone(journalId, warning, now())
    override suspend fun recordPassResult(journalId: String, note: String?, thirdCountNeeded: Boolean) =
        countingDao.recordPassResult(journalId, note, thirdCountNeeded)
    override suspend fun unsentExceptions(journalId: String) = countingDao.unsentExceptions(journalId)
    override suspend fun unsentLocationChecks(journalId: String) = countingDao.unsentLocationChecks(journalId)
    override suspend fun unsentConfirmations(journalId: String) = countingDao.unsentConfirmations(journalId)
    override suspend fun markExceptionsSent(journalId: String) {
        countingDao.markExceptionsSent(journalId, now())
        countingDao.markLocationChecksSent(journalId, now())
        countingDao.markConfirmationsSent(journalId, now())
    }
    override suspend fun markExceptionsFailed(journalId: String, error: String) =
        countingDao.markExceptionsFailed(journalId, error)
}

/** What the worker should tell WorkManager. */
enum class RunResult { DONE, RETRY }

/**
 * One End count, from wherever it last got to. Idempotent across process death: a DMF import
 * that was already enqueued (its execution id is stored) is polled, never re-imported, and a
 * DIRECT submit carries the same client submission id every time, so the server can dedupe it.
 * A failure that says something definite (posted, closed, claim lost, DMF refused the run) ends
 * the submission; anything transient hands back [RunResult.RETRY] and keeps every count.
 */
@Singleton
class CountSubmitRunner @Inject constructor(
    private val transport: CountTransport,
    private val store: SubmissionStore,
    private val claims: CountClaimManager,
) {
    internal var pollIntervalMs: Long = 3_000L
    internal var maxPollsPerRun: Int = 100

    suspend fun run(journalId: String): RunResult {
        val submission = store.get(journalId) ?: return RunResult.DONE
        if (!submission.state.inProgress) return RunResult.DONE

        store.countAttempt(journalId)
        if (submission.attempts + 1 > MAX_ATTEMPTS) {
            return fail(journalId, CountSubmissionError.OTHER, "Gave up after $MAX_ATTEMPTS attempts. Tap End count to try again.")
        }

        val claim = claims.localClaim(journalId)
        if (claim?.lost == true) {
            return fail(
                journalId,
                CountSubmissionError.CLAIM_LOST,
                (claim.lostReason ?: "Your claim on this journal was lost.") + " Claim it again to submit.",
            )
        }

        var countsWarning = submission.countsWarning
        if (!submission.countsDone && !submission.exceptionsOnly) {
            val counts = when (submission.transferMode) {
                TransferMode.DMF -> runDmf(submission, claim?.claimToken)
                TransferMode.DIRECT -> runDirect(submission, claim?.claimToken)
            }
            when (counts) {
                is Stage.Stop -> return counts.result
                is Stage.Continue -> {
                    countsWarning = counts.warning
                    store.markCountsDone(journalId, countsWarning)
                }
            }
        }
        return sendExceptions(submission, claim?.claimToken, countsWarning)
    }

    /** Continue: the counts landed (maybe with D365FO refusing some lines). Stop: end this run. */
    private sealed interface Stage {
        data class Continue(val warning: String?) : Stage
        data class Stop(val result: RunResult) : Stage
    }

    /**
     * Unexpected items and verified-empty locations go after the counts. A definite refusal does
     * not undo the counts: the journal is SUBMITTED with a warning and the exceptions stay on the
     * device, marked failed, ready to send again.
     */
    private suspend fun sendExceptions(submission: CountSubmissionEntity, claimToken: String?, warning: String?): RunResult {
        val journalId = submission.journalId
        val exceptions = store.unsentExceptions(journalId)
        val emptyLocations = store.unsentLocationChecks(journalId)
        val confirmations = store.unsentConfirmations(journalId)
        if (exceptions.isEmpty() && emptyLocations.isEmpty() && confirmations.isEmpty()) return succeed(journalId, warning)

        store.update(journalId, CountSubmissionState.UPLOADING, message = "Sending unexpected items and confirmations…")
        return when (
            val result = transport.submitExceptions(journalId, submission.audit(), claimToken, exceptions, emptyLocations, confirmations)
        ) {
            is TransportResult.Ok -> {
                store.markExceptionsSent(journalId)
                succeed(journalId, warning)
            }
            is TransportResult.Retry -> {
                store.update(journalId, CountSubmissionState.QUEUED, message = "Waiting to retry: ${result.reason}")
                RunResult.RETRY
            }
            is TransportResult.Fail -> {
                store.markExceptionsFailed(journalId, result.message)
                val what = listOfNotNull(
                    exceptions.size.takeIf { it > 0 }?.let { "$it unexpected item${if (it == 1) "" else "s"}" },
                    emptyLocations.size.takeIf { it > 0 }?.let { "$it empty location${if (it == 1) "" else "s"}" },
                    confirmations.size.takeIf { it > 0 }?.let { "$it verified line${if (it == 1) "" else "s"}" },
                ).joinToString(" and ")
                succeed(
                    journalId,
                    warning = listOfNotNull(warning, "Counts submitted, but $what could not be sent: ${result.message}")
                        .joinToString(" "),
                    error = CountSubmissionError.EXCEPTIONS_FAILED,
                )
            }
        }
    }

    private suspend fun runDmf(submission: CountSubmissionEntity, claimToken: String?): Stage {
        val journalId = submission.journalId
        var executionId = submission.executionId

        if (executionId == null) {
            store.update(journalId, CountSubmissionState.UPLOADING)
            when (val started = transport.startDmfImport(journalId, submission.audit(), claimToken)) {
                is TransportResult.Ok -> {
                    executionId = started.value
                    store.recordExecutionId(journalId, executionId)
                }
                is TransportResult.Retry -> {
                    store.update(journalId, CountSubmissionState.QUEUED, message = "Waiting to retry: ${started.reason}")
                    return Stage.Stop(RunResult.RETRY)
                }
                is TransportResult.Fail ->
                    // Only verify-only confirmations / exceptions to send - nothing for the import.
                    return if (started.error == CountSubmissionError.NOTHING_COUNTED && hasExtras(journalId)) {
                        Stage.Continue(warning = null)
                    } else {
                        Stage.Stop(fail(journalId, started.error, started.message))
                    }
            }
        }

        repeat(maxPollsPerRun) {
            delay(pollIntervalMs)
            when (val poll = transport.pollDmfImport(executionId)) {
                is TransportResult.Retry -> return Stage.Stop(RunResult.RETRY)
                is TransportResult.Fail -> return Stage.Stop(fail(journalId, poll.error, poll.message))
                is TransportResult.Ok -> when (val status = poll.value) {
                    ImportJobStatus.Enqueued -> store.update(journalId, CountSubmissionState.IMPORTING)
                    is ImportJobStatus.Running -> store.update(journalId, CountSubmissionState.IMPORTING, percent = status.percent)
                    ImportJobStatus.Succeeded -> return Stage.Continue(warning = null)
                    is ImportJobStatus.PartiallySucceeded -> return Stage.Continue(warning = status.warning)
                    is ImportJobStatus.Failed ->
                        return Stage.Stop(fail(journalId, CountSubmissionError.DMF_FAILED, status.reason))
                }
            }
        }
        // Still running in D365FO. The execution id is stored, so the next run just keeps polling.
        return Stage.Stop(RunResult.RETRY)
    }

    private suspend fun runDirect(submission: CountSubmissionEntity, claimToken: String?): Stage {
        val journalId = submission.journalId
        store.update(journalId, CountSubmissionState.UPLOADING)
        return when (val result = transport.submitDirect(journalId, submission.audit(), claimToken)) {
            is TransportResult.Ok -> {
                val pass = result.value
                if (pass.thirdCountNeeded || pass.passResult != null) {
                    store.recordPassResult(
                        journalId,
                        pass.passResult ?: "D365FO needs a third count for this journal.",
                        pass.thirdCountNeeded,
                    )
                }
                Stage.Continue(warning = null)
            }
            is TransportResult.Retry -> {
                store.update(journalId, CountSubmissionState.QUEUED, message = "Waiting to retry: ${result.reason}")
                Stage.Stop(RunResult.RETRY)
            }
            is TransportResult.Fail ->
                if (result.error == CountSubmissionError.NOTHING_COUNTED && hasExtras(journalId)) Stage.Continue(warning = null)
                else Stage.Stop(fail(journalId, result.error, result.message))
        }
    }

    private suspend fun hasExtras(journalId: String): Boolean =
        store.unsentConfirmations(journalId).isNotEmpty() ||
            store.unsentExceptions(journalId).isNotEmpty() ||
            store.unsentLocationChecks(journalId).isNotEmpty()

    private suspend fun succeed(journalId: String, warning: String?, error: CountSubmissionError? = null): RunResult {
        store.update(
            journalId,
            if (warning == null) CountSubmissionState.SUCCEEDED else CountSubmissionState.PARTIAL,
            error = error,
            message = warning,
        )
        store.markJournalSubmitted(journalId, warning)
        // End count gives the claim back. Best effort - the server's own expiry covers a miss.
        runCatching { claims.release(journalId) }.onFailure { Timber.w(it, "claim release failed for %s", journalId) }
        return RunResult.DONE
    }

    private suspend fun fail(journalId: String, error: CountSubmissionError, message: String): RunResult {
        store.update(journalId, CountSubmissionState.FAILED, error = error, message = message)
        store.markJournalFailed(journalId, message)
        return RunResult.DONE
    }

    companion object {
        const val MAX_ATTEMPTS = 20
    }
}

/** How a failed call reads to End count: retry the transient, stop on the definite. */
fun AppFailure.toTransport(): TransportResult<Nothing> = when (this) {
    is AppFailure.Offline, is AppFailure.Transient, is AppFailure.Authentication -> TransportResult.Retry(message)
    is AppFailure.BusinessRule -> when (d365ErrorCode) {
        "JOURNAL_POSTED" -> TransportResult.Fail(
            CountSubmissionError.ALREADY_POSTED,
            "This journal has already been posted in D365FO - counts can no longer be sent to it.",
        )
        "JOURNAL_NOT_FOUND" -> TransportResult.Fail(
            CountSubmissionError.JOURNAL_NOT_FOUND,
            "This journal is closed or no longer exists in D365FO.",
        )
        "CLAIM_LOST", "CLAIM_EXPIRED" -> TransportResult.Fail(
            CountSubmissionError.CLAIM_LOST,
            "Your claim on this journal is no longer valid. Claim it again to submit.",
        )
        "NOTHING_COUNTED" -> TransportResult.Fail(CountSubmissionError.NOTHING_COUNTED, message)
        "RECOUNT_SAME_USER" -> TransportResult.Fail(
            CountSubmissionError.SAME_COUNTER,
            "This recount must be done by someone other than the previous counter.",
        )
        "NOT_IMPLEMENTED" -> TransportResult.Fail(
            CountSubmissionError.NOT_AVAILABLE,
            "This server does not accept this kind of submission yet.",
        )
        else -> if (httpCode == 404) {
            TransportResult.Fail(CountSubmissionError.NOT_AVAILABLE, "The counting service is not deployed on this environment.")
        } else {
            TransportResult.Fail(CountSubmissionError.OTHER, message)
        }
    }
    else -> TransportResult.Fail(CountSubmissionError.OTHER, message)
}
