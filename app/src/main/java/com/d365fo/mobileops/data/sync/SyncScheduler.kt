package com.d365fo.mobileops.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.d365fo.mobileops.data.sync.worker.SkuSyncWorker
import com.d365fo.mobileops.data.sync.worker.TransactionPushWorker
import com.d365fo.mobileops.util.Constants
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(context: Context) {

    private val workManager = WorkManager.getInstance(context)

    private val connectedOnly = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * First constraint of this kind in this app - see `SkuSyncWorker`'s own doc comment. A 1M+
     * row master-data download is exactly the bandwidth-contention risk that got the old
     * `WORK_PULL_MASTER_DATA` worker removed (see the doc below); Wi-Fi-and-charging means this
     * only ever runs while the handheld is docked, never mid-shift on the same radio a scan is
     * waiting on.
     */
    private val wifiAndCharging = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresCharging(true)
        .build()

    /**
     * Registers the recurring jobs. Safe to call on every app start — KEEP means an
     * existing schedule is not disturbed, so a mid-shift restart does not reset the
     * hourly order refresh.
     *
     * **Only the outbound push is scheduled here.** The two *pull* workers used to start with
     * the app, and on the on-demand screens that was pure cost: the step flow and the on-hand
     * enquiry read nothing from Room, so a full order pull and a full master-data preload were
     * fetching data nothing would look at — while competing for the same connection, the same
     * rate-limiter permits and the same slow AOS as the operator's scan. A batched line query
     * measured 3.7 seconds on this tenant, and a scan issued behind it waits.
     *
     * The pulls are now requested by the screens that actually need them; see [schedulePulls].
     * The push worker stays unconditional, because a captured transaction has to leave the
     * device whatever screen the operator is on.
     */
    fun schedulePeriodicWork() {
        // Cancel any pull schedule left behind by an older install.
        //
        // WorkManager persists unique periodic work in its own database, so deleting the enqueue
        // call does not stop it - an upgraded device would keep running the master-data preload
        // and the hourly order pull for ever, against code that no longer exists to consume them.
        // Nothing re-arms these now; the pull architecture is gone.
        workManager.cancelUniqueWork(Constants.WORK_PULL_MASTER_DATA)
        workManager.cancelUniqueWork(Constants.WORK_PULL_ORDER_STATUS)

        // WorkManager's floor for a periodic job is 15 minutes, well short of the 60-second
        // cadence the queue needs. The periodic job is therefore a safety net that guarantees
        // the queue drains even if the app is killed; the minute-by-minute cadence comes from
        // [requestImmediatePush], triggered by connectivity regain and by each new enqueue.
        workManager.enqueueUniquePeriodicWork(
            Constants.WORK_PUSH_TRANSACTIONS,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TransactionPushWorker>(15, TimeUnit.MINUTES)
                .setConstraints(connectedOnly)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                // A random start so a fleet's safety-net runs do not line up - see FleetJitter.
                .setInitialDelay(FleetJitter.millisUpTo(TimeUnit.MINUTES.toMillis(15)), TimeUnit.MILLISECONDS)
                .build(),
        )

        // Debug builds never sync the SKU master: with the debug and the release app on one
        // handheld, both ran it - every export was done twice in D365FO, at the same moment.
        if (com.d365fo.mobileops.BuildConfig.DEBUG) {
            workManager.cancelUniqueWork(Constants.WORK_SYNC_SKU_MASTER)
            workManager.cancelUniqueWork(SKU_CONTINUE)
            return
        }

        // Once a day is enough for master data that changes on the order of days, not minutes -
        // and WorkManager only runs it at all once the Wi-Fi-and-charging constraint above is
        // actually met, so a device that never sits on Wi-Fi while charging simply never
        // triggers this and keeps serving whatever it cached last, same as before this existed.
        workManager.enqueueUniquePeriodicWork(
            Constants.WORK_SYNC_SKU_MASTER,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SkuSyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(wifiAndCharging)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                // The heaviest call the app makes (a full SKU export): spread over hours, so a
                // fleet put on charge at the end of a shift does not ask for it all at once.
                .setInitialDelay(FleetJitter.millisUpTo(TimeUnit.HOURS.toMillis(4)), TimeUnit.MILLISECONDS)
                .build(),
        )
    }


    /**
     * The SKU sync's next step, a few minutes from now, while its export is still running in
     * D365FO. Appended, so the run that asks for it finishes first; Wi-Fi-and-charging as the
     * daily run.
     */
    fun scheduleSkuContinuation(delayMs: Long = TimeUnit.MINUTES.toMillis(2), replace: Boolean = false) {
        if (com.d365fo.mobileops.BuildConfig.DEBUG) return
        workManager.enqueueUniqueWork(
            SKU_CONTINUE,
            // At app start (replace): a fresh attempt instead of queuing behind one still waiting
            // out a long retry backoff. From the worker itself: appended, so it finishes first.
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SkuSyncWorker>()
                .setConstraints(wifiAndCharging)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build(),
        )
    }

    /**
     * Called right after a transaction is enqueued and when connectivity returns.
     *
     * APPEND_OR_REPLACE rather than REPLACE: if a push is already running, this queues
     * behind it instead of cancelling it mid-post, which would leave a leased entry to
     * wait out its lease.
     */
    fun requestImmediatePush() {
        workManager.enqueueUniqueWork(
            WORK_PUSH_NOW,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<TransactionPushWorker>()
                .setConstraints(connectedOnly)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
    }




    /**
     * A push run in a couple of minutes, for PostingFollowUp. One per two-minute slot: the run
     * asking is itself the follow-up work, so keeping a single name would keep nothing new.
     */
    fun requestPostingFollowUp(now: Long = System.currentTimeMillis()) {
        val slot = TimeUnit.MILLISECONDS.toMinutes(now) / 2
        workManager.enqueueUniqueWork(
            "$POSTING_FOLLOW_UP.$slot",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<TransactionPushWorker>()
                .setConstraints(connectedOnly)
                .setInitialDelay(2, TimeUnit.MINUTES)
                .build(),
        )
    }

    /**
     * Settings > Sync masters now (30 Oct): the item, barcode and variant sync at once, on any
     * connection - not waiting for Wi-Fi and charging. Debug builds too: it is asked for.
     * Replaces a manual sync already queued; the daily one is left as it is.
     */
    fun syncMastersNow(sync: com.d365fo.mobileops.data.repository.ManualSync) {
        workManager.cancelUniqueWork(SKU_CONTINUE)
        workManager.enqueueUniqueWork(
            WORK_SYNC_MASTERS_NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SkuSyncWorker>()
                .setConstraints(connectedOnly)
                .setInputData(
                    androidx.work.workDataOf(
                        SkuSyncWorker.KEY_MANUAL to sync.name,
                        SkuSyncWorker.KEY_MANUAL_CHAIN to true,
                    ),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
    }

    /** The next step of a manual sync - appended to the same chain, so its state stays visible. */
    fun continueMasterSyncNow() {
        workManager.enqueueUniqueWork(
            WORK_SYNC_MASTERS_NOW,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SkuSyncWorker>()
                .setConstraints(connectedOnly)
                .setInputData(androidx.work.workDataOf(SkuSyncWorker.KEY_MANUAL_CHAIN to true))
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
    }

    /** Where the last "Sync masters now" is: running (queued too), failed with D365FO's reason, or neither. */
    val mastersSync: kotlinx.coroutines.flow.Flow<MasterSyncState> =
        workManager.getWorkInfosForUniqueWorkFlow(WORK_SYNC_MASTERS_NOW)
            .map { infos ->
                val failed = infos.firstOrNull { it.state == androidx.work.WorkInfo.State.FAILED }
                when {
                    infos.any { !it.state.isFinished } -> MasterSyncState.Running
                    failed != null -> MasterSyncState.Failed(
                        failed.outputData.getString(SkuSyncWorker.KEY_ERROR) ?: "D365FO could not be reached",
                    )
                    else -> MasterSyncState.Idle
                }
            }

    private companion object {
        const val WORK_PUSH_NOW = "d365.push.now"
        const val SKU_CONTINUE = "d365.sync.skumaster.continue"
        const val WORK_SYNC_MASTERS_NOW = "d365.sync.skumaster.now"
        const val POSTING_FOLLOW_UP = "d365.posting.followup"
    }
}

/** See [SyncScheduler.mastersSync]. */
sealed interface MasterSyncState {
    data object Idle : MasterSyncState
    data object Running : MasterSyncState
    data class Failed(val reason: String) : MasterSyncState
}
