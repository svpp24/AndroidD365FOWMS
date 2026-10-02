package com.d365fo.mobileops.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d365fo.mobileops.data.local.entity.CountClaimEntity
import com.d365fo.mobileops.data.local.entity.CountExceptionEntity
import com.d365fo.mobileops.data.local.entity.CountLineAttemptEntity
import com.d365fo.mobileops.data.local.entity.CountLocationCheckEntity
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
    val locationType: String = "PHYSICAL",
    val locationOwner: String? = null,
    val verifyOnly: Boolean = false,
    val confirmedAt: Long? = null,
    val configId: String? = null,
    val sizeId: String? = null,
    val colorId: String? = null,
    val styleId: String? = null,
) {
    val variant: com.d365fo.mobileops.domain.model.ProductVariant
        get() = com.d365fo.mobileops.domain.model.ProductVariant.of(configId, sizeId, colorId, styleId)

    /** Counted once it has an entered quantity, was marked counted, or (verify-only) was confirmed. */
    val isCounted: Boolean get() = countedQuantity != null || markedCounted || (verifyOnly && confirmedAt != null)
}

@Dao
interface CountingDao {

    // -- Counter-facing lines ---------------------------------------------------------------

    @Query(
        """
        SELECT journalLineId, journalId, lineNumber, itemId, itemName, locationId, batchId,
               serialId, countedQuantity, markedCounted, countedBy, countedAt,
               locationType, locationOwner, verifyOnly, confirmedAt,
               configId, sizeId, colorId, styleId
        FROM invent_journal_line WHERE journalId = :journalId ORDER BY lineNumber ASC
        """,
    )
    fun observeCountLines(journalId: String): Flow<List<CountLine>>

    @Query(
        """
        SELECT journalLineId, journalId, lineNumber, itemId, itemName, locationId, batchId,
               serialId, countedQuantity, markedCounted, countedBy, countedAt,
               locationType, locationOwner, verifyOnly, confirmedAt,
               configId, sizeId, colorId, styleId
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

    /** Location type, owner and verify-only, as the server described each line. */
    @Query(
        """
        UPDATE invent_journal_line SET locationType = :locationType, locationOwner = :owner, verifyOnly = :verifyOnly
        WHERE journalId = :journalId AND lineNumber = :lineNumber
        """,
    )
    suspend fun setLineAttributes(journalId: String, lineNumber: Int, locationType: String, owner: String?, verifyOnly: Boolean)

    /** A verify-only line confirmed present (null [at] withdraws the confirmation). */
    @Query("UPDATE invent_journal_line SET confirmedBy = :by, confirmedAt = :at WHERE journalLineId = :journalLineId")
    suspend fun confirmLine(journalLineId: String, by: String?, at: Long?)

    @Query(
        "SELECT * FROM invent_journal_line WHERE journalId = :journalId AND verifyOnly = 1 AND confirmedAt IS NOT NULL AND confirmationSentAt IS NULL",
    )
    suspend fun unsentConfirmations(journalId: String): List<com.d365fo.mobileops.data.local.entity.JournalLineEntity>

    @Query(
        "UPDATE invent_journal_line SET confirmationSentAt = :at WHERE journalId = :journalId AND verifyOnly = 1 AND confirmedAt IS NOT NULL AND confirmationSentAt IS NULL",
    )
    suspend fun markConfirmationsSent(journalId: String, at: Long)

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

    @Query("UPDATE count_submission SET countsDone = 1, countsWarning = :warning, updatedAt = :at WHERE journalId = :journalId")
    suspend fun markCountsDone(journalId: String, warning: String?, at: Long)

    @Query("UPDATE count_submission SET resultNote = :note, thirdCountNeeded = :thirdCountNeeded WHERE journalId = :journalId")
    suspend fun recordPassResult(journalId: String, note: String?, thirdCountNeeded: Boolean)

    // -- Attempt history (audit only) ---------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttempts(attempts: List<CountLineAttemptEntity>)

    @Query("SELECT * FROM count_line_attempt WHERE journalId = :journalId ORDER BY journalLineId, attemptNo")
    suspend fun attempts(journalId: String): List<CountLineAttemptEntity>

    // -- Unexpected items and location checks -------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertException(exception: CountExceptionEntity)

    @Query("DELETE FROM count_exception WHERE clientId = :clientId AND sentAt IS NULL")
    suspend fun deleteUnsentException(clientId: String)

    @Query("SELECT * FROM count_exception WHERE journalId = :journalId ORDER BY capturedAt")
    fun observeExceptions(journalId: String): Flow<List<CountExceptionEntity>>

    @Query("SELECT * FROM count_exception WHERE journalId = :journalId AND sentAt IS NULL ORDER BY capturedAt")
    suspend fun unsentExceptions(journalId: String): List<CountExceptionEntity>

    @Query("UPDATE count_exception SET sentAt = :at, sendError = NULL WHERE journalId = :journalId AND sentAt IS NULL")
    suspend fun markExceptionsSent(journalId: String, at: Long)

    @Query("UPDATE count_exception SET sendError = :error WHERE journalId = :journalId AND sentAt IS NULL")
    suspend fun markExceptionsFailed(journalId: String, error: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocationCheck(check: CountLocationCheckEntity)

    @Query("SELECT * FROM count_location_check WHERE journalId = :journalId")
    fun observeLocationChecks(journalId: String): Flow<List<CountLocationCheckEntity>>

    @Query("SELECT * FROM count_location_check WHERE journalId = :journalId AND sentAt IS NULL")
    suspend fun unsentLocationChecks(journalId: String): List<CountLocationCheckEntity>

    @Query("UPDATE count_location_check SET sentAt = :at WHERE journalId = :journalId AND sentAt IS NULL")
    suspend fun markLocationChecksSent(journalId: String, at: Long)

    /** Per journal: unexpected items and verified-empty locations, for the list card and End count. */
    @Query(
        """
        SELECT j.journalId AS journalId,
               (SELECT COUNT(*) FROM count_exception e WHERE e.journalId = j.journalId) AS exceptions,
               (SELECT COUNT(*) FROM count_exception e WHERE e.journalId = j.journalId AND e.sentAt IS NULL AND e.sendError IS NOT NULL) AS failedExceptions,
               (SELECT COUNT(*) FROM count_location_check c WHERE c.journalId = j.journalId AND c.verifiedEmpty = 1) AS emptyLocations
        FROM invent_journal j WHERE j.type = 'COUNTING'
        """,
    )
    fun observeCaptureExtras(): Flow<List<CaptureExtrasRow>>
}

data class CaptureExtrasRow(
    val journalId: String,
    val exceptions: Int,
    val failedExceptions: Int,
    val emptyLocations: Int,
)
