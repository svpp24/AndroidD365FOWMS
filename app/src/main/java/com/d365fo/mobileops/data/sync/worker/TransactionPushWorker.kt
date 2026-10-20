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
import timber.log.Timber

/**
 * Drains the outbound queue.
 *
 * Entries are processed strictly in order and one at a time. Parallelising them would be
 * faster but wrong: two receipts against the same purchase order must not race, and D365FO
 * takes row locks on PurchTable during a form-letter posting, so concurrent posts against
 * one order deadlock rather than finishing sooner.
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
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Settings > Data posting is off: every capture stays queued on the device, untouched -
        // not attempted, so not failed. Switching it on sends them (see SyncStatusProvider). An
        // admin's permission change is configuration, not a capture, so it still goes.
        val postingAllowed = connectionSettings.postingEnabled.value
        var processed = 0
        var deadLettered = 0

        while (processed < MAX_ENTRIES_PER_RUN) {
            val batch = queue.leaseBatch()
            if (batch.isEmpty()) break

            val held = batch.filter {
                !postingAllowed && it.operation != com.d365fo.mobileops.data.local.entity.SyncOperation.SET_WORKER_PERMISSION
            }
            if (held.isNotEmpty()) queue.releaseLease(held)
            val toSend = batch - held.toSet()
            toSend.forEachIndexed { index, entry ->
                if (isStopped) {
                    // WorkManager is reclaiming us. Release the untouched remainder of the
                    // lease so the next run picks them up at once rather than waiting for
                    // the lease to expire.
                    queue.releaseLease(toSend.drop(index))
                    return Result.success()
                }

                when (val result = dispatcher.dispatch(entry)) {
                    is NetworkResult.Success -> {
                        Timber.i("posted %s for %s", entry.operation, entry.aggregateId)
                        queue.onSuccess(entry)
                    }

                    is NetworkResult.Error -> {
                        val dropped = queue.onFailure(entry, result.failure)
                        if (dropped) {
                            deadLettered++
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
                            queue.releaseLease(toSend.drop(index + 1))
                            return finish(processed + 1, deadLettered)
                        }
                    }

                    NetworkResult.Loading -> Unit
                }
                processed++
            }
            // Held entries come straight back in the next lease: stop here instead of looping.
            if (held.isNotEmpty()) break
        }
        return finish(processed, deadLettered)
    }

    private fun finish(processed: Int, deadLettered: Int): Result {
        Timber.d("push run complete: %d processed, %d dead-lettered", processed, deadLettered)
        return Result.success()
    }

    companion object {
        /** Bounded so one run cannot exceed WorkManager's ten-minute execution window. */
        private const val MAX_ENTRIES_PER_RUN = 100
    }
}
