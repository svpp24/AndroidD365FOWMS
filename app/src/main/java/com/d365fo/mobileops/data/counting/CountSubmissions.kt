package com.d365fo.mobileops.data.counting

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.d365fo.mobileops.data.local.dao.CountingDao
import com.d365fo.mobileops.data.local.dao.JournalDao
import com.d365fo.mobileops.data.local.entity.CountSubmissionEntity
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
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
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

    suspend fun endCount(journalId: String): EndCountResult {
        if (countingDao.getSubmission(journalId)?.state?.inProgress == true) {
            scheduler.enqueue(journalId)
            return EndCountResult.Queued
        }
        val journal = journalDao.getJournal(journalId)?.journal
            ?: return EndCountResult.Refused("This journal's lines are not on this device.")
        if (journal.status == JournalStatus.POSTED) {
            return EndCountResult.Refused("This journal has been posted in D365FO and can't be changed.")
        }
        claims.localClaim(journalId)?.takeIf { it.lost }?.let { lost ->
            return EndCountResult.Refused(
                (lost.lostReason ?: "Your claim on this journal was lost.") + " Claim it again before ending the count.",
            )
        }
        if (countingDao.getCountLines(journalId).none { it.isCounted }) {
            return EndCountResult.Refused("Nothing has been counted yet.")
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
            ),
        )
        journalDao.updateStatus(journalId, JournalStatus.PENDING_POST, SyncState.PENDING, null)
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
