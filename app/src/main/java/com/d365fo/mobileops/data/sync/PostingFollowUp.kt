package com.d365fo.mobileops.data.sync

import com.d365fo.mobileops.util.NetworkResult
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Follows a posting from D365FO taking it to D365FO's final answer.
 *
 * Taking it is not the end: it may sit staged, or the call may have timed out while it was still
 * being worked. Refused after that, nothing on the device knew - the work was gone from the queue
 * and the worker had to scan it all again. Now a refusal lands in Failed postings with its reason,
 * where Resubmit sends it again with nothing to rescan (6 Nov).
 *
 * Asked after every push run and when Activity opens; while something is young, again in a couple
 * of minutes. After that the push worker's 15-minute safety net keeps asking.
 *
 * Failed postings are asked about too: Retry or Change quantity on the Mobile posting queue form
 * posts one without this device, and Cancel posting closes it. Its card must not go on saying it
 * failed.
 */
@Singleton
class PostingFollowUp @Inject constructor(
    private val queue: SyncQueueRepository,
    private val dispatcher: PostingDispatcher,
    private val notifier: SyncNotifier,
    private val scheduler: SyncScheduler,
) {

    suspend fun check(now: Long = System.currentTimeMillis()) {
        clearPostedFailures(now)
        val sent = queue.sentPostings()
        if (sent.isEmpty()) return
        sent.groupBy { it.dataAreaId }.forEach { (company, postings) ->
            postings.chunked(KEYS_PER_CALL).forEach { page ->
                val rows = when (val result = dispatcher.postingResults(company, page.map { it.idempotencyKey })) {
                    is NetworkResult.Success -> result.data.associateBy { it.idempotencyKey }
                    // Not reachable: everything stays on the list, and is asked about next time.
                    else -> return@forEach
                }
                page.forEach { posting ->
                    val row = rows[posting.idempotencyKey]
                    when {
                        row == null -> if (now - posting.sentAt > FORGET_AFTER_MS) queue.removeSent(posting.idempotencyKey)
                        row.status == REGISTERED || row.status == CANCELLED -> queue.removeSent(posting.idempotencyKey)
                        row.status == FAILED -> {
                            val reason = row.errorMessage?.takeIf { it.isNotBlank() } ?: "D365FO could not post it"
                            Timber.w("%s for %s refused after sending: %s", posting.operation, posting.aggregateId, reason)
                            queue.sentFailed(posting, reason)
                            notifier.notifyDeadLetter(posting.aggregateId, SyncOperationLabel.of(posting.operation))
                        }
                    }
                }
            }
        }
        if (queue.sentPostings().any { now - it.sentAt < ASK_OFTEN_FOR_MS }) scheduler.requestPostingFollowUp()
    }

    /** A key D365FO has no row for was refused before it was staged: that card stays. */
    private suspend fun clearPostedFailures(now: Long) {
        if (now - lastFailuresAsked < ASK_ABOUT_FAILURES_EVERY_MS) return
        lastFailuresAsked = now
        queue.followedFailures(KEYS_PER_CALL).groupBy { (_, payload) -> payload.dataAreaId }.forEach { (company, failures) ->
            failures.chunked(KEYS_PER_CALL).forEach { page ->
                val rows = when (val result = dispatcher.postingResults(company, page.map { (_, p) -> p.idempotencyKey })) {
                    is NetworkResult.Success -> result.data.associateBy { it.idempotencyKey }
                    else -> return@forEach
                }
                page.forEach { (failure, payload) ->
                    // Retry or Change quantity posted it, or a supervisor cancelled it.
                    val status = rows[payload.idempotencyKey]?.status
                    if (status == REGISTERED || status == CANCELLED) queue.removeFailure(failure.failureId)
                }
            }
        }
    }

    private var lastFailuresAsked = 0L

    private companion object {
        const val KEYS_PER_CALL = 40
        val ASK_ABOUT_FAILURES_EVERY_MS = TimeUnit.MINUTES.toMillis(2)
        const val REGISTERED = "Registered"
        const val CANCELLED = "Cancelled"
        const val FAILED = "Failed"
        val ASK_OFTEN_FOR_MS = TimeUnit.MINUTES.toMillis(30)

        /** D365FO keeps its rows far longer than this (Mobile clean-up), so this is a lost cause. */
        val FORGET_AFTER_MS = TimeUnit.DAYS.toMillis(14)
    }
}
