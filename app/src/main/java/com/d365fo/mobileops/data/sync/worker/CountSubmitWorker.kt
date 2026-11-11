package com.d365fo.mobileops.data.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.d365fo.mobileops.data.counting.CountSubmitRunner
import com.d365fo.mobileops.data.counting.RunResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * End count, as a WorkManager job (ordinary background work - no foreground service since
 * 11 Nov 2026). Replaces the old in-memory tracker run, which a
 * process kill left stuck at PENDING_POST; WorkManager restarts this job instead, and the
 * runner resumes from the persisted submission row (see [CountSubmitRunner]).
 */
@HiltWorker
class CountSubmitWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: CountSubmitRunner,
    private val connectionSettings: com.d365fo.mobileops.data.security.ConnectionSettings,
) : CoroutineWorker(context, params) {

    private val journalId: String? get() = inputData.getString(KEY_JOURNAL_ID)

    override suspend fun doWork(): Result {
        val id = journalId ?: return Result.failure()
        // Posting off: the submission stays QUEUED on the device, untouched. Switching posting on
        // enqueues it again - see CountSubmissions.resumeWaiting.
        if (!connectionSettings.postingEnabled.value) return Result.success()
        return when (runner.run(id)) {
            RunResult.DONE -> Result.success()
            RunResult.RETRY -> Result.retry()
        }
    }

    companion object {
        const val KEY_JOURNAL_ID = "journalId"
    }
}
