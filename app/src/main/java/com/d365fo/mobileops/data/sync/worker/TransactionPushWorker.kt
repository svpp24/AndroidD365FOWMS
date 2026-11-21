package com.d365fo.mobileops.data.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.d365fo.mobileops.data.sync.PostingDispatcher
import com.d365fo.mobileops.data.sync.SyncNotifier
import com.d365fo.mobileops.data.sync.SyncOperationLabel
import com.d365fo.mobileops.data.sync.SyncQueueRepository
import com.d365fo.mobileops.util.NetworkResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drains the outbound queue.
 *
 * One order's entries are processed strictly in order and one at a time: two receipts against
 * the same purchase order must not race, and D365FO takes row locks on PurchTable during a
 * form-letter posting, so concurrent posts against one order deadlock rather than finishing
 * sooner. Different orders go side by side, up to [ORDER_LANES] at once (performance plan,
 * phase 4) - before, a slow posting held up every other order behind it.
 *
 * The worker always returns [Result.success] when it made progress, even if some entries
 * failed. Each entry carries its own retry schedule in the queue table; returning
 * [Result.retry] would apply WorkManager's backoff to the whole batch and let one poisoned
 * entry stall every other worker's transactions.
 */
@HiltWorker
class TransactionPushWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val queue: SyncQueueRepository,
    private val dispatcher: PostingDispatcher,
    private val notifier: SyncNotifier,
    private val connectionSettings: com.d365fo.mobileops.data.security.ConnectionSettings,
    private val followUp: com.d365fo.mobileops.data.sync.PostingFollowUp,
    private val heartbeat: com.d365fo.mobileops.data.sync.DeviceHeartbeat,
    private val scheduler: com.d365fo.mobileops.data.sync.SyncScheduler,
) : CoroutineWorker(context, params) {

    private val sentThisRun = AtomicInteger()

    override suspend fun doWork(): Result {
        val result = drain()
        // Sync health's check-in, at most every 15 minutes (16 Nov 2026). Never fails the run.
        try {
            heartbeat.sendIfDue()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.w(e, "device check-in failed")
        }
        // What became of what was sent, this run or before. Never fails the run: it is asked again.
        // A run that sent nothing asks at most every 5 minutes - the follow-up job asks every 2
        // while anything sent is recent (calls, 10 Nov).
        val now = System.currentTimeMillis()
        if (sentThisRun.get() > 0 || now - lastFollowUpAt >= IDLE_FOLLOW_UP_MS) {
            lastFollowUpAt = now
            try {
                followUp.check()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w(e, "posting follow-up failed")
            }
        }
        // Whatever still waits for a retry gets a run at its own time (17 Nov 2026), so the
        // time Activity shows is the time it goes. Last: when this run is that retry run, the
        // REPLACE stops it, and it used to stop it before the check-in and follow-up above.
        try {
            val at = System.currentTimeMillis()
            queue.observeWaiting().first().map { it.nextAttemptAt }.filter { it > at }.minOrNull()
                ?.let { scheduler.requestPushAt(it, at) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.w(e, "retry scheduling failed")
        }
        return result
    }

    private suspend fun drain(): Result {
        // Settings > Data posting is off: every capture stays queued on the device, untouched -
        // not attempted, so not failed. Switching it on sends them (see SyncStatusProvider). An
        // admin's permission change is configuration, not a capture, so it still goes.
        val postingAllowed = connectionSettings.postingEnabled.value
        val run = Run()
        sentThisRun.set(0)

        while (run.processed.get() < MAX_ENTRIES_PER_RUN) {
            val batch = queue.leaseBatch()
            if (batch.isEmpty()) break

            val held = batch.filter {
                !postingAllowed && it.operation != com.d365fo.mobileops.data.local.entity.SyncOperation.SET_WORKER_PERMISSION
            }
            if (held.isNotEmpty()) queue.releaseLease(held)
            val toSend = batch - held.toSet()
            // One lane per order, in queue order within it.
            val lanes = Semaphore(ORDER_LANES)
            coroutineScope {
                toSend.groupBy { it.aggregateId }.values.forEach { lane ->
                    launch { lanes.withPermit { sendLane(lane, run) } }
                }
            }
            if (isStopped) return Result.success()
            if (run.halted.get()) return finish(run)
            // Held entries come straight back in the next lease: stop here instead of looping.
            if (held.isNotEmpty()) break
        }
        return finish(run)
    }

    private class Run {
        val processed = AtomicInteger()
        val deadLettered = AtomicInteger()
        /** A retryable failure: the connection is most likely gone, so every lane stops. */
        val halted = AtomicBoolean(false)
    }

    /** One order's leased entries, in order. */
    private suspend fun sendLane(lane: List<com.d365fo.mobileops.data.local.entity.SyncQueueEntity>, run: Run) {
        lane.forEachIndexed { index, entry ->
            if (isStopped || run.halted.get()) {
                // WorkManager is reclaiming us, or another lane hit a dead connection.
                // Release the untouched remainder of the lease so the next run picks
                // them up at once rather than waiting for the lease to expire.
                queue.releaseLease(lane.drop(index))
                return
            }

            sentThisRun.incrementAndGet()

            when (val result = dispatcher.dispatch(entry)) {
                is NetworkResult.Success -> {
                    Timber.i("posted %s for %s", entry.operation, entry.aggregateId)
                    queue.onSuccess(entry)
                }

                is NetworkResult.Error -> {
                    val dropped = queue.onFailure(entry, result.failure)
                    if (dropped) {
                        run.deadLettered.incrementAndGet()
                        Timber.e(
                            "dead-lettered %s for %s: %s",
                            entry.operation, entry.aggregateId, result.failure.message,
                        )
                        // The one failure worth a system notification - see SyncNotifier.
                        // Every retryable attempt before this point stays silent on
                        // purpose; only the give-up moment does.
                        notifier.notifyDeadLetter(
                            entry.aggregateId,
                            SyncOperationLabel.of(entry.operation),
                        )
                    }
                    // A retryable failure almost always means the connection is gone.
                    // Stop the run rather than marching the rest of the queue into the
                    // same wall and burning everyone's retry budget.
                    if (result.failure.retryable) {
                        Timber.i("push halted: %s", result.failure.message)
                        run.halted.set(true)
                        run.processed.incrementAndGet()
                        queue.releaseLease(lane.drop(index + 1))
                        return
                    }
                }

                NetworkResult.Loading -> Unit
            }
            run.processed.incrementAndGet()
        }
    }

    private fun finish(run: Run): Result {
        Timber.d("push run complete: %d processed, %d dead-lettered", run.processed.get(), run.deadLettered.get())
        return Result.success()
    }

    companion object {
        /** Last follow-up check, any run - see doWork. */
        @Volatile private var lastFollowUpAt = 0L
        private const val IDLE_FOLLOW_UP_MS = 300_000L
        /** Bounded so one run cannot exceed WorkManager's ten-minute execution window. */
        private const val MAX_ENTRIES_PER_RUN = 100
        /** Orders posted at once. Small: D365FO's batch and row locks, and the 10-call cap. */
        private const val ORDER_LANES = 3
    }
}
