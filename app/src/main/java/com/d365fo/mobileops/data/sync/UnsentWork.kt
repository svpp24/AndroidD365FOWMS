package com.d365fo.mobileops.data.sync

import androidx.room.Dao
import androidx.room.Query
import com.d365fo.mobileops.data.local.D365Database
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What is on this device and not yet safely in D365FO. */
data class UnsentSummary(
    /** Captured and posted, waiting to be sent (offline, or between tries). */
    val queued: Int,
    /** Refused by D365FO and not yet dealt with on the Activity screen. */
    val refused: Int,
    /** Scanned lines not posted yet - the "Continue where you left off" jobs. */
    val drafts: Int,
    /** Journals with counts D365FO hasn't accepted yet. */
    val counts: Int,
) {
    val isEmpty: Boolean get() = queued + refused + drafts + counts == 0

    /** "2 postings waiting to send, 1 unfinished job" - for a confirmation message. */
    fun describe(): String = listOfNotNull(
        plural(queued, "posting", "postings").takeIf { queued > 0 }?.let { "$it waiting to send" },
        plural(refused, "posting", "postings").takeIf { refused > 0 }?.let { "$it refused by D365FO and not yet resolved" },
        plural(drafts, "unfinished job", "unfinished jobs").takeIf { drafts > 0 },
        plural(counts, "counting journal", "counting journals").takeIf { counts > 0 }?.let { "$it with counts not yet in D365FO" },
    ).joinToString(", ")

    private fun plural(n: Int, one: String, many: String) = "$n " + if (n == 1) one else many
}

@Dao
interface UnsentWorkDao {
    @Query("SELECT COUNT(*) FROM sync_queue")
    suspend fun queued(): Int

    @Query("SELECT COUNT(*) FROM failed_sync WHERE acknowledged = 0")
    suspend fun refused(): Int

    @Query("SELECT COUNT(*) FROM flow_session")
    suspend fun drafts(): Int

    /**
     * Journals with a count D365FO has not accepted: never submitted, submission not finished
     * or failed, or counted again after the last submission.
     */
    @Query(
        """
        SELECT COUNT(DISTINCT l.journalId) FROM invent_journal_line l
        LEFT JOIN count_submission s ON s.journalId = l.journalId
        WHERE (l.countedQuantity IS NOT NULL OR l.markedCounted = 1)
          AND (s.journalId IS NULL
               OR s.state NOT IN ('SUCCEEDED', 'PARTIAL')
               OR COALESCE(l.countedAt, 0) > s.submittedAt)
        """,
    )
    suspend fun counts(): Int
}

/**
 * Asked before anything that wipes the local database - a connection change wipes it, since
 * orders and items from one D365FO environment mean nothing in another. Work captured here is
 * the one thing that must never go with it.
 */
@Singleton
class UnsentWork @Inject constructor(
    private val database: D365Database,
    private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun summary(): UnsentSummary = withContext(ioDispatcher) {
        val dao = database.unsentWorkDao()
        UnsentSummary(queued = dao.queued(), refused = dao.refused(), drafts = dao.drafts(), counts = dao.counts())
    }
}
