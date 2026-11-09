package com.d365fo.mobileops.presentation.common

import com.d365fo.mobileops.data.local.entity.FailedSyncEntity
import com.d365fo.mobileops.data.local.entity.SyncQueueEntity
import com.d365fo.mobileops.data.repository.CountingActivity
import com.d365fo.mobileops.data.sync.PostQueueEntry

/*
 * The Activity tab, in the order the 9 Nov redesign asks for (brief §4, phase 8): problems first,
 * each with its one-tap fix where there is one; then what is still on its way; then what D365FO
 * has, with green ticks. Pure, so the order is a plain unit test.
 */

/** Something that needs the worker. */
sealed interface ActivityProblem {
    val key: String

    /** A posting this device couldn't hand to D365FO, or D365FO refused - Retry / Dismiss. */
    data class Posting(val failure: FailedSyncEntity) : ActivityProblem {
        override val key get() = "posting:${failure.failureId}"
    }

    /**
     * A row D365FO's own queue marks Failed with no local failure behind it (refused after a
     * reinstall, or from another device) - nothing on this device to retry.
     */
    data class Refused(val entry: PostQueueEntry) : ActivityProblem {
        override val key get() = "refused:${entry.documentType}:${entry.sourceId}:${entry.receivedUtc.orEmpty()}"
    }

    /** A counting download or submission that failed, or finished with a warning - Dismiss. */
    data class Count(val activity: CountingActivity) : ActivityProblem {
        override val key get() = "count:${activity.key}"
        /** A warning (some lines refused) is a Warn band; a failure is a Bad one. */
        val warningOnly: Boolean get() = !activity.failed
    }
}

/** Something D365FO has, or has finished - listed with a green tick (pending ones are Info). */
sealed interface ActivitySent {
    val key: String

    data class Queue(val entry: PostQueueEntry) : ActivitySent {
        override val key get() = "queue:${entry.documentType}:${entry.sourceId}:${entry.receivedUtc.orEmpty()}:${entry.idempotencyKey}"
        /** Still waiting for D365FO's batch job - not sent-and-done yet. */
        val pending: Boolean get() = !entry.status.equals("Registered", ignoreCase = true)
    }

    data class Count(val activity: CountingActivity) : ActivitySent {
        override val key get() = "count:${activity.key}"
    }
}

data class ActivityModel(
    val problems: List<ActivityProblem>,
    /** Captured here, not yet handed to D365FO. */
    val waiting: List<SyncQueueEntity>,
    /** Counting runs still going. */
    val running: List<CountingActivity>,
    /** D365FO's queue (minus refusals, which are problems) and finished counting runs. */
    val sent: List<ActivitySent>,
    /** Failed postings the worker dismissed - kept, Retry still works. */
    val dismissed: List<FailedSyncEntity>,
)

fun activityModel(
    failures: List<FailedSyncEntity>,
    waiting: List<SyncQueueEntity>,
    queue: List<PostQueueEntry>,
    counting: List<CountingActivity>,
): ActivityModel {
    val (dismissed, open) = failures.partition { it.acknowledged }
    // A refusal D365FO reports is moved into the device's own failures (PostingFollowUp): shown
    // once, as the failure that can be retried.
    val failedIds = failures.map { it.aggregateId.uppercase() }.toSet()
    val (refused, accepted) = queue.partition { it.status.equals("Failed", ignoreCase = true) }
    val problems = open.sortedByDescending { it.failedAt }.map(ActivityProblem::Posting) +
        refused.filter { it.sourceId.uppercase() !in failedIds }.map(ActivityProblem::Refused) +
        counting.filter { !it.inProgress && (it.failed || it.warning) }.map(ActivityProblem::Count)
    return ActivityModel(
        problems = problems,
        waiting = waiting,
        running = counting.filter { it.inProgress },
        sent = accepted.map(ActivitySent::Queue) +
            counting.filter { !it.inProgress && !it.failed && !it.warning }.map(ActivitySent::Count),
        dismissed = dismissed,
    )
}
