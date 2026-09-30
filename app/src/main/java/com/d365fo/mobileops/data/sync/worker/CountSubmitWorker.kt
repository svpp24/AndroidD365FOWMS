package com.d365fo.mobileops.data.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.d365fo.mobileops.data.counting.CountSubmitRunner
import com.d365fo.mobileops.data.counting.RunResult
import com.d365fo.mobileops.data.sync.SyncNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * End count, as a foreground WorkManager job. Replaces the old in-memory tracker run, which a
 * process kill left stuck at PENDING_POST; WorkManager restarts this job instead, and the
 * runner resumes from the persisted submission row (see [CountSubmitRunner]).
 */
@HiltWorker
class CountSubmitWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: CountSubmitRunner,
    private val notifier: SyncNotifier,
) : CoroutineWorker(context, params) {

    private val journalId: String? get() = inputData.getString(KEY_JOURNAL_ID)

    override suspend fun getForegroundInfo(): ForegroundInfo = notifier.countSubmitForegroundInfo(journalId.orEmpty())

    override suspend fun doWork(): Result {
        val id = journalId ?: return Result.failure()
        // Refused on some Android versions when the app is in the background - the job runs anyway.
        runCatching { setForeground(getForegroundInfo()) }
            .onFailure { Timber.w(it, "count submit for %s running without foreground", id) }
        return when (runner.run(id)) {
            RunResult.DONE -> Result.success()
            RunResult.RETRY -> Result.retry()
        }
    }

    companion object {
        const val KEY_JOURNAL_ID = "journalId"
    }
}
