package com.d365fo.mobileops.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d365fo.mobileops.data.local.entity.CountClaimEntity
import com.d365fo.mobileops.data.local.entity.CountSubmissionEntity
import com.d365fo.mobileops.data.local.entity.CountSubmissionError
import com.d365fo.mobileops.data.local.entity.CountSubmissionState
import com.d365fo.mobileops.data.local.entity.CountingLineReferenceEntity
import kotlinx.coroutines.flow.Flow

/**
 * A counting line as a counter sees it. Deliberately has no on-hand, expected or variance field:
 * every counter-facing screen reads lines through this projection, so blind counting holds by
 * construction rather than by each screen remembering not to show a column.
 */
data class CountLine(
    val journalLineId: String,
    val journalId: String,
    val lineNumber: Int,
    val itemId: String,
    val itemName: String,
    val locationId: String?,
    val batchId: String?,
    val serialId: String?,
    val countedQuantity: String?,
    val markedCounted: Boolean,
    val countedBy: String?,
    val countedAt: Long?,
) {
    /** Counted once it has an entered quantity, or was marked counted. */
    val isCounted: Boolean get() = countedQuantity != null || markedCounted
}

@Dao
interface CountingDao {

    // -- Counter-facing lines ---------------------------------------------------------------

    @Query(
        """
        SELECT journalLineId, journalId, lineNumber, itemId, itemName, locationId, batchId,
               serialId, countedQuantity, markedCounted, countedBy, countedAt
        FROM invent_journal_line WHERE journalId = :journalId ORDER BY lineNumber ASC
        """,
    )
    fun observeCountLines(journalId: String): Flow<List<CountLine>>

    @Query(
        """
        SELECT journalLineId, journalId, lineNumber, itemId, itemName, locationId, batchId,
               serialId, countedQuantity, markedCounted, countedBy, countedAt
        FROM invent_journal_line WHERE journalId = :journalId ORDER BY lineNumber ASC
        """,
    )
    suspend fun getCountLines(journalId: String): List<CountLine>

    /** A null [countedQuantity] clears the count. Written the moment the worker confirms it. */
    @Query(
        """
        UPDATE invent_journal_line
        SET countedQuantity = :countedQuantity, countedBy = :countedBy, countedAt = :countedAt
        WHERE journalLineId = :journalLineId
        """,
    )
    suspend fun recordCount(journalLineId: String, countedQuantity: String?, countedBy: String?, countedAt: Long)

    @Query(
        """
        UPDATE invent_journal_line
        SET markedCounted = :marked, countedBy = :countedBy, countedAt = :countedAt
        WHERE journalLineId = :journalLineId
        """,
    )
    suspend fun recordMarked(journalLineId: String, marked: Boolean, countedBy: String?, countedAt: Long)

    /** Only the first open counts. */
    @Query(
        "UPDATE invent_journal_line SET lineCountStartedAt = :at WHERE journalLineId = :journalLineId AND lineCountStartedAt IS NULL",
    )
    suspend fun markLineStarted(journalLineId: String, at: Long)

    // -- On-hand reference (submit path only) ------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReferences(rows: List<CountingLineReferenceEntity>)

    @Query("SELECT * FROM counting_line_reference WHERE journalId = :journalId")
    suspend fun getReferences(journalId: String): List<CountingLineReferenceEntity>

    // -- Claims ------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertClaim(claim: CountClaimEntity)

    @Query("SELECT * FROM count_claim WHERE journalId = :journalId")
    suspend fun getClaim(journalId: String): CountClaimEntity?

    @Query("SELECT * FROM count_claim")
    fun observeClaims(): Flow<List<CountClaimEntity>>

    @Query("SELECT * FROM count_claim WHERE lost = 0")
    suspend fun activeClaims(): List<CountClaimEntity>

    @Query("UPDATE count_claim SET expiresAt = :expiresAt, lastHeartbeatAt = :at WHERE journalId = :journalId")
    suspend fun recordHeartbeat(journalId: String, expiresAt: Long?, at: Long)

    @Query("UPDATE count_claim SET lost = 1, lostReason = :reason WHERE journalId = :journalId")
    suspend fun markClaimLost(journalId: String, reason: String)

    @Query("DELETE FROM count_claim WHERE journalId = :journalId")
    suspend fun deleteClaim(journalId: String)

    // -- Submissions -------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSubmission(submission: CountSubmissionEntity)

    @Query("SELECT * FROM count_submission WHERE journalId = :journalId")
    suspend fun getSubmission(journalId: String): CountSubmissionEntity?

    @Query("SELECT * FROM count_submission")
    fun observeSubmissions(): Flow<List<CountSubmissionEntity>>

    @Query(
        """
        UPDATE count_submission
        SET state = :state, error = :error, message = :message, percent = :percent, updatedAt = :at
        WHERE journalId = :journalId
        """,
    )
    suspend fun updateSubmission(
        journalId: String,
        state: CountSubmissionState,
        error: CountSubmissionError?,
        message: String?,
        percent: Int?,
        at: Long,
    )

    @Query("UPDATE count_submission SET executionId = :executionId, state = 'IMPORTING', updatedAt = :at WHERE journalId = :journalId")
    suspend fun recordExecutionId(journalId: String, executionId: String, at: Long)

    @Query("UPDATE count_submission SET attempts = attempts + 1 WHERE journalId = :journalId")
    suspend fun countAttempt(journalId: String)

    @Query("DELETE FROM count_submission WHERE journalId = :journalId")
    suspend fun deleteSubmission(journalId: String)
}
