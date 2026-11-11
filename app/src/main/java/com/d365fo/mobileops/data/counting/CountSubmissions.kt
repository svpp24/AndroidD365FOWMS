package com.d365fo.mobileops.data.counting

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.d365fo.mobileops.data.local.dao.CountingDao
import com.d365fo.mobileops.data.local.dao.JournalDao
import com.d365fo.mobileops.data.local.entity.CountLineAttemptEntity
import com.d365fo.mobileops.data.local.entity.CountSubmissionEntity
import com.d365fo.mobileops.data.local.entity.TransferMode
import com.d365fo.mobileops.domain.model.COUNT_MODE_DOUBLE_BLIND
import com.d365fo.mobileops.data.local.entity.CountSubmissionState
import com.d365fo.mobileops.data.local.entity.JournalStatus
import com.d365fo.mobileops.data.local.entity.SyncState
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.data.sync.worker.CountSubmitWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Enqueues End count on WorkManager - one unique job per journal. */
@Singleton
class CountSubmitScheduler @Inject constructor(@ApplicationContext context: Context) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueue(journalId: String) {
        workManager.enqueueUniqueWork(
            workName(journalId),
            // KEEP: a submission already running is never interrupted by a second press.
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CountSubmitWorker>()
                .setInputData(workDataOf(CountSubmitWorker.KEY_JOURNAL_ID to journalId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    /**
     * Runs a submission now that is only waiting out its retry backoff - D365FO answers again,
     * so there is no reason to sit out a backoff that can have grown to minutes. A job that is
     * running is left alone (REPLACE would cancel it mid-import), as is one already finished.
     */
    fun nudge(journalId: String) {
        val waiting = runCatching {
            workManager.getWorkInfosForUniqueWork(workName(journalId)).get()
                .any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }
        }.getOrDefault(false)
        if (!waiting) return
        workManager.enqueueUniqueWork(
            workName(journalId),
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CountSubmitWorker>()
                .setInputData(workDataOf(CountSubmitWorker.KEY_JOURNAL_ID to journalId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    private fun workName(journalId: String) = "count-submit-$journalId"
}

sealed interface EndCountResult {
    data object Queued : EndCountResult
    data class Refused(val message: String) : EndCountResult
}

/**
 * End count's entry point. Fixes the audit (who, which device, when, which pass) at the moment
 * the button is pressed, persists the submission and hands it to WorkManager. Offline is not a
 * refusal: the job waits for a connection and every count stays in Room.
 */
@Singleton
class CountSubmissions @Inject constructor(
    private val countingDao: CountingDao,
    private val journalDao: JournalDao,
    private val claims: CountClaimManager,
    private val scheduler: CountSubmitScheduler,
    private val secureStore: SecureStore,
    private val workerSession: WorkerSessionRepository,
) {
    fun observe() = countingDao.observeSubmissions()

    /**
     * Posting switched back on: every End count held while it was off goes now. Its job may have
     * finished doing nothing (see CountSubmitWorker), so it is enqueued again, not just nudged.
     */
    suspend fun resumeWaiting() {
        countingDao.observeSubmissions().first()
            .filter { it.state.inProgress }
            .forEach {
                scheduler.nudge(it.journalId)
                scheduler.enqueue(it.journalId)
            }
    }

    /** D365FO answers again: every End count still waiting goes now - see CountSubmitScheduler.nudge. */
    suspend fun nudgeWaiting() {
        countingDao.observeSubmissions().first()
            .filter { it.state.inProgress }
            .forEach { scheduler.nudge(it.journalId) }
    }

    suspend fun endCount(journalId: String): EndCountResult {
        if (countingDao.getSubmission(journalId)?.state?.inProgress == true) {
            scheduler.enqueue(journalId)
            return EndCountResult.Queued
        }
        val journal = journalDao.getJournal(journalId)?.journal
            ?: return EndCountResult.Refused("This journal's lines are not on this device.")
        if (journal.status == JournalStatus.POSTED) {
            return EndCountResult.Refused("Posted - can't change.")
        }
        claims.localClaim(journalId)?.takeIf { it.lost }?.let { lost ->
            return EndCountResult.Refused(
                (lost.lostReason ?: "Your claim on this journal was lost.") + " Claim it again before ending the count.",
            )
        }
        // Items found that are not on the journal are a count too (14 Oct): they become its lines.
        if (countingDao.getCountLines(journalId).none { it.isCounted } && countingDao.unsentExceptions(journalId).isEmpty()) {
            return EndCountResult.Refused("Nothing has been counted yet.")
        }

        val now = System.currentTimeMillis()
        // The pass as it stands now, kept as this line's attempt history (audit only).
        countingDao.upsertAttempts(
            countingDao.getCountLines(journalId).filter { it.isCounted }.map {
                CountLineAttemptEntity(
                    journalLineId = it.journalLineId,
                    attemptNo = journal.passNumber,
                    journalId = journalId,
                    countedBy = it.countedBy,
                    countedAt = it.countedAt,
                    quantity = it.countedQuantity,
                    markedOnly = it.countedQuantity == null && it.markedCounted,
                )
            },
        )
        countingDao.upsertSubmission(
            CountSubmissionEntity(
                journalId = journalId,
                submissionId = UUID.randomUUID().toString(),
                // A double-blind pass is the counter's own pass, kept by the server in its attempt
                // table - it never goes through the DMF import, which would overwrite the line.
                transferMode = if (journal.countMode == COUNT_MODE_DOUBLE_BLIND) TransferMode.DIRECT else journal.transferMode,
                state = CountSubmissionState.QUEUED,
                userId = workerSession.session.value?.userName
                    ?: secureStore.getString(SecureStore.KEY_WORKER_ID).orEmpty(),
                deviceId = secureStore.deviceId(),
                originType = journal.originType,
                passNumber = journal.passNumber,
                startedAt = journal.countStartedAt,
                submittedAt = now,
                updatedAt = now,
            ),
        )
        journalDao.updateStatus(journalId, JournalStatus.PENDING_POST, SyncState.PENDING, null)
        scheduler.enqueue(journalId)
        return EndCountResult.Queued
    }

    /**
     * Sends a submitted journal's unexpected items / empty locations again after a failure. The
     * counts are not re-sent - the new submission starts at the exceptions stage.
     */
    suspend fun resendExceptions(journalId: String): EndCountResult {
        if (countingDao.getSubmission(journalId)?.state?.inProgress == true) return EndCountResult.Queued
        val journal = journalDao.getJournal(journalId)?.journal
            ?: return EndCountResult.Refused("This journal's lines are not on this device.")
        if (countingDao.unsentExceptions(journalId).isEmpty() && countingDao.unsentLocationChecks(journalId).isEmpty()) {
            return EndCountResult.Refused("Nothing is waiting to be sent.")
        }
        val now = System.currentTimeMillis()
        countingDao.upsertSubmission(
            CountSubmissionEntity(
                journalId = journalId,
                submissionId = UUID.randomUUID().toString(),
                transferMode = journal.transferMode,
                state = CountSubmissionState.QUEUED,
                userId = workerSession.session.value?.userName
                    ?: secureStore.getString(SecureStore.KEY_WORKER_ID).orEmpty(),
                deviceId = secureStore.deviceId(),
                originType = journal.originType,
                passNumber = journal.passNumber,
                startedAt = journal.countStartedAt,
                submittedAt = now,
                updatedAt = now,
                countsDone = true,
                exceptionsOnly = true,
            ),
        )
        scheduler.enqueue(journalId)
        return EndCountResult.Queued
    }

    /** App start: make sure every unfinished submission has a job behind it. */
    suspend fun resumePending() {
        observe().first().filter { it.state.inProgress }.forEach { scheduler.enqueue(it.journalId) }
    }

    /** Removes a finished submission from the Activity page. A running one is left alone. */
    suspend fun dismiss(journalId: String) {
        if (countingDao.getSubmission(journalId)?.state?.inProgress == false) countingDao.deleteSubmission(journalId)
    }
}
