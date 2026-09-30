package com.d365fo.mobileops.data.counting

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps claims alive in the background. While the process lives, a loop on the application scope
 * ticks every [TICK_MS] and heartbeats whatever is due at the interval the server asked for. If
 * the process is killed, [ClaimHeartbeatWorker] (WorkManager's 15-minute floor) takes over.
 * With no claims on the device both do nothing but read an empty table.
 */
@Singleton
class ClaimHeartbeat @Inject constructor(
    private val manager: CountClaimManager,
    private val scope: CoroutineScope,
    @ApplicationContext private val context: Context,
) {
    private var loop: Job? = null

    @Synchronized
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                runCatching { manager.heartbeatDue() }.onFailure { Timber.w(it, "claim heartbeat pass failed") }
                delay(TICK_MS)
            }
        }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ClaimHeartbeatWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    private companion object {
        const val TICK_MS = 15_000L
        const val WORK_NAME = "count-claim-heartbeat"
    }
}

@HiltWorker
class ClaimHeartbeatWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val manager: CountClaimManager,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { manager.heartbeatDue() }.onFailure { Timber.w(it, "background claim heartbeat failed") }
        return Result.success()
    }
}
