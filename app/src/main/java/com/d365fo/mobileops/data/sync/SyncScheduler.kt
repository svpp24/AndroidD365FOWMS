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
import com.d365fo.mobileops.data.sync.worker.TransactionPushWorker
import com.d365fo.mobileops.util.Constants
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




    private companion object {
        const val WORK_PUSH_NOW = "d365.push.now"
    }
}
