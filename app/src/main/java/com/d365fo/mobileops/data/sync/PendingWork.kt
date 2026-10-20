package com.d365fo.mobileops.data.sync

import com.d365fo.mobileops.data.local.dao.FlowSessionDao
import com.d365fo.mobileops.data.local.dao.JournalDao
import javax.inject.Inject
import javax.inject.Singleton

/** A worker's unfinished work on one device: jobs not yet registered, counts not yet submitted. */
data class PendingWork(val jobs: Int, val counts: Int) {
    val isEmpty: Boolean get() = jobs <= 0 && counts <= 0

    /** "2 unfinished jobs and 1 count in progress". */
    fun describe(): String = listOfNotNull(
        jobs.takeIf { it > 0 }?.let { "$it unfinished job${if (it == 1) "" else "s"}" },
        counts.takeIf { it > 0 }?.let { "$it count${if (it == 1) "" else "s"} in progress" },
    ).joinToString(" and ")
}

/**
 * Counts what this device still holds for a worker - for the session service, so another
 * device can warn before taking the session over, and for the "signed in on another device"
 * notice here. Counting journals belong to the device, not one worker, so all of them count.
 */
@Singleton
class PendingWorkCounter @Inject constructor(
    private val flowSessions: FlowSessionDao,
    private val journals: JournalDao,
) {
    suspend fun forUser(userName: String): PendingWork = PendingWork(
        jobs = runCatching { flowSessions.countFor(userName) }.getOrDefault(0),
        counts = runCatching { journals.countUnsubmittedCounts() }.getOrDefault(0),
    )
}
