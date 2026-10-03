package com.d365fo.mobileops.data.ai

import android.content.Context
import android.util.Base64
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.AiSampleRequestBody
import com.d365fo.mobileops.data.remote.dto.AiSampleServiceRequest
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.util.NetworkResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Schedules the upload of waiting AI count samples - one unique job, only on a network. */
@Singleton
class AiSampleUploadScheduler @Inject constructor(@ApplicationContext context: Context) {
    private val workManager = WorkManager.getInstance(context)

    /** [now] = the worker asked for it (Settings > Send now): start at once, replacing a run in backoff. */
    fun enqueue(now: Boolean = false) {
        workManager.enqueueUniqueWork(
            WORK_NAME,
            // KEEP normally: a run already queued picks up the new sample too.
            if (now) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AiSampleUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                // A sample is never urgent: wait a little so a burst of counts goes in one run.
                .setInitialDelay(if (now) 0L else 1L, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build(),
        )
    }

    private companion object {
        const val WORK_NAME = "ai-sample-upload"
    }
}

/**
 * Uploads waiting samples one at a time, oldest first, deleting each once D365FO answers. Stops
 * at the first failure and retries later with backoff, so a down server costs one call per try.
 */
@HiltWorker
class AiSampleUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val store: TrainingSampleStore,
    private val service: MobileOpsServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val secureStore: SecureStore,
    private val workerSession: WorkerSessionRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val company = companyProvider()?.lowercase().orEmpty()
        if (company.isBlank()) return Result.retry()
        val session = workerSession.session.value
        for (p in store.pending().take(MAX_PER_RUN)) {
            val body = AiSampleRequestBody(
                dataAreaId = company,
                sampleId = p.id,
                itemId = p.json.optString("itemId"),
                quantity = p.json.optInt("count"),
                corrections = p.json.optInt("corrections"),
                annotation = p.json.toString(),
                imageBase64 = Base64.encodeToString(p.image.readBytes(), Base64.NO_WRAP),
                deviceId = secureStore.deviceId(),
                userName = session?.userName.orEmpty(),
                warehouseId = session?.selected?.warehouseId.orEmpty(),
            )
            when (val r = errorMapper.safeCall { service.uploadAiSample(AiSampleServiceRequest(body)) }) {
                is NetworkResult.Success -> if (r.data.errorCode.startsWith("ERROR")) {
                    // D365FO failed storing it - keep the sample and try again later.
                    Timber.w("ai sample not stored: %s", r.data.errorCode)
                    return Result.retry()
                } else {
                    // Stored, already there, item full, or refused as invalid: none improves by retrying.
                    store.done(p)
                }
                is NetworkResult.Error -> {
                    Timber.w("ai sample upload failed: %s", r.failure.message)
                    return Result.retry()
                }
                NetworkResult.Loading -> return Result.retry()
            }
        }
        return if (store.pending().isEmpty()) Result.success() else Result.retry()
    }

    private companion object {
        /** Bounds one run's network use; the rest goes next time. */
        const val MAX_PER_RUN = 10
    }
}
