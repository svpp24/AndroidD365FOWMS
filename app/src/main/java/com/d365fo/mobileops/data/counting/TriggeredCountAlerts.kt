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
import com.d365fo.mobileops.data.repository.CountingJournalRepository
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.data.sync.SyncNotifier
import com.d365fo.mobileops.util.NetworkResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notifies a worker of new TRIGGERED counts (negative stock, a reported discrepancy) for their
 * last-used warehouse, every 15 minutes while the app is installed.
 *
 * This is the stand-in for push: FCM needs a Firebase project and google-services.json, which
 * this build does not have. TODO: once FCM is set up, have the server push "new triggered
 * count" and call [TriggeredCountAlerts.check] from the messaging service instead of polling.
 */
@Singleton
class TriggeredCountAlerts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val journals: CountingJournalRepository,
    private val secureStore: SecureStore,
    private val notifier: SyncNotifier,
) {
    fun schedule(enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TriggeredCountWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(
                    com.d365fo.mobileops.data.sync.FleetJitter.millisUpTo(TimeUnit.MINUTES.toMillis(15)),
                    TimeUnit.MILLISECONDS,
                )
                .build(),
        )
    }

    /** The warehouse the counting list was last opened for - what background checks look at. */
    fun rememberWarehouse(warehouseId: String) = secureStore.putString(KEY_WAREHOUSE, warehouseId)

    /** Notifies each TRIGGERED journal once. Returns how many were new. */
    suspend fun check(): Int {
        val warehouse = secureStore.getString(KEY_WAREHOUSE)?.takeIf { it.isNotBlank() } ?: return 0
        val list = journals.listAssignedJournals(warehouse) as? NetworkResult.Success ?: return 0
        val seen = secureStore.getString(KEY_SEEN).orEmpty().split(',').filter { it.isNotBlank() }.toMutableSet()
        val fresh = list.data.filter { it.originType == "TRIGGERED" && it.journalId !in seen }
        fresh.forEach { notifier.notifyTriggeredCount(it.journalId, it.description) }
        if (fresh.isNotEmpty()) {
            // Only what is still open is worth remembering - keeps the stored list short.
            val open = list.data.mapTo(HashSet()) { it.journalId }
            secureStore.putString(KEY_SEEN, (seen.filter { it in open } + fresh.map { it.journalId }).joinToString(","))
        }
        return fresh.size
    }

    private companion object {
        const val WORK_NAME = "count-triggered-alerts"
        const val KEY_WAREHOUSE = "count_alert_warehouse"
        const val KEY_SEEN = "count_alert_seen_triggered"
    }
}

@HiltWorker
class TriggeredCountWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val alerts: TriggeredCountAlerts,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { alerts.check() }
        return Result.success()
    }
}
