package com.d365fo.mobileops.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.d365fo.mobileops.data.local.entity.FailedSyncEntity
import com.d365fo.mobileops.data.local.entity.SyncOperation
import com.d365fo.mobileops.data.local.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncQueueDao {

    /**
     * Claims the next batch of due work and leases it, in one transaction. Leasing rather
     * than plain selection means a worker killed mid-post cannot leave an entry invisible
     * forever — the lease expires and the row becomes claimable again.
     */
    @Transaction
    suspend fun leaseBatch(
        limit: Int,
        now: Long = System.currentTimeMillis(),
        leaseMs: Long = 5 * 60 * 1000L,
    ): List<SyncQueueEntity> {
        val due = selectDue(now, limit)
        if (due.isNotEmpty()) {
            lease(due.map { it.queueId }, now + leaseMs)
        }
        return due
    }

    @Query(
        """
        SELECT * FROM sync_queue
        WHERE nextAttemptAt <= :now AND leasedUntil <= :now
        ORDER BY createdAt ASC
        LIMIT :limit
        """,
    )
    suspend fun selectDue(now: Long, limit: Int): List<SyncQueueEntity>

    /**
     * Claims one specific, just-enqueued entry for an immediate inline post attempt - see
     * [com.d365fo.mobileops.presentation.modules.stepflow.ReceivingFlowViewModel.postReceipt]'s
     * "tried live, right here" step. Same due-and-unleased condition as [leaseBatch], scoped to
     * one row: whichever caller reaches this row first, inline attempt or a background worker
     * run racing it after [enqueue]'s own commit made the row visible to both, wins it, and the
     * other sees leasedUntil already in the future and backs off. Without this, the inline
     * attempt dispatched an entry it never claimed, which a concurrent worker run could claim
     * and dispatch at the same time - confirmed live: two postProductReceipt calls, 151ms apart,
     * identical payload size, matching a caller and a worker run each unaware of the other.
     */
    @Transaction
    suspend fun leaseOne(
        queueId: String,
        now: Long = System.currentTimeMillis(),
        leaseMs: Long = 5 * 60 * 1000L,
    ): SyncQueueEntity? {
        val entry = selectOneIfDue(queueId, now) ?: return null
        lease(listOf(entry.queueId), now + leaseMs)
        return entry
    }

    @Query("SELECT * FROM sync_queue WHERE queueId = :queueId AND nextAttemptAt <= :now AND leasedUntil <= :now LIMIT 1")
    suspend fun selectOneIfDue(queueId: String, now: Long): SyncQueueEntity?

    @Query("UPDATE sync_queue SET leasedUntil = :until WHERE queueId IN (:queueIds)")
    suspend fun lease(queueIds: List<String>, until: Long)

    @Query("UPDATE sync_queue SET leasedUntil = 0 WHERE queueId IN (:queueIds)")
    suspend fun releaseLease(queueIds: List<String>)

    /**
     * Idempotent enqueue. IGNORE on the unique dedupeKey index means a double-tapped Post
     * button, or a retried enqueue after a crash, cannot produce two product receipts.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(entry: SyncQueueEntity): Long

    @Query("SELECT * FROM sync_queue WHERE dedupeKey = :dedupeKey LIMIT 1")
    suspend fun findByDedupeKey(dedupeKey: String): SyncQueueEntity?

    @Query("SELECT * FROM sync_queue WHERE aggregateId = :aggregateId ORDER BY createdAt")
    suspend fun findForAggregate(aggregateId: String): List<SyncQueueEntity>

    @Query("SELECT COUNT(*) FROM sync_queue")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE operation = :operation")
    suspend fun countFor(operation: SyncOperation): Int

    @Query("SELECT MIN(createdAt) FROM sync_queue")
    fun observeOldestQueuedAt(): Flow<Long?>

    @Query(
        """
        UPDATE sync_queue
        SET retryCount = :retryCount, nextAttemptAt = :nextAttemptAt,
            lastError = :error, leasedUntil = 0
        WHERE queueId = :queueId
        """,
    )
    suspend fun scheduleRetry(queueId: String, retryCount: Int, nextAttemptAt: Long, error: String?)

    @Query("DELETE FROM sync_queue WHERE queueId = :queueId")
    suspend fun remove(queueId: String)

    @Query("DELETE FROM sync_queue WHERE aggregateId = :aggregateId")
    suspend fun removeForAggregate(aggregateId: String)

    @Query("DELETE FROM sync_queue")
    suspend fun clear()

    // -- Dead letter ---------------------------------------------------------------------

    /** Moving to the dead-letter queue must be atomic with removal from the live queue. */
    @Transaction
    suspend fun deadLetter(queueId: String, failure: FailedSyncEntity) {
        insertFailure(failure)
        remove(queueId)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFailure(failure: FailedSyncEntity)

    @Query("SELECT * FROM failed_sync ORDER BY failedAt DESC")
    fun observeFailures(): Flow<List<FailedSyncEntity>>

    @Query("SELECT COUNT(*) FROM failed_sync WHERE acknowledged = 0")
    fun observeUnacknowledgedFailureCount(): Flow<Int>

    @Query("SELECT * FROM failed_sync WHERE failureId = :failureId")
    suspend fun getFailure(failureId: String): FailedSyncEntity?

    @Query("UPDATE failed_sync SET acknowledged = 1 WHERE failureId = :failureId")
    suspend fun acknowledgeFailure(failureId: String)

    /** Supervisor resubmit: back onto the live queue, out of the dead-letter table. */
    @Transaction
    suspend fun resubmit(failureId: String, entry: SyncQueueEntity) {
        enqueue(entry)
        removeFailure(failureId)
    }

    @Query("DELETE FROM failed_sync WHERE failureId = :failureId")
    suspend fun removeFailure(failureId: String)

    @Query("DELETE FROM failed_sync")
    suspend fun clearFailures()
}
