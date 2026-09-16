package com.d365fo.mobileops.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.d365fo.mobileops.data.local.entity.JournalEntity
import com.d365fo.mobileops.data.local.entity.JournalLineEntity
import com.d365fo.mobileops.data.local.entity.JournalStatus
import com.d365fo.mobileops.data.local.entity.JournalType
import com.d365fo.mobileops.data.local.entity.JournalWithLines
import com.d365fo.mobileops.data.local.entity.SalesLineEntity
import com.d365fo.mobileops.data.local.entity.SalesOrderEntity
import com.d365fo.mobileops.data.local.entity.SalesOrderWithLines
import com.d365fo.mobileops.data.local.entity.SyncState
import com.d365fo.mobileops.data.local.entity.TransferLineEntity
import com.d365fo.mobileops.data.local.entity.TransferOrderEntity
import com.d365fo.mobileops.data.local.entity.TransferOrderWithLines
import com.d365fo.mobileops.util.Constants
import kotlinx.coroutines.flow.Flow

/**
 * Serves the arrival, movement/adjustment, and cycle-count modules — all three are
 * InventJournalTable postings and differ only by [JournalType] and which columns the UI
 * collects.
 */
@Dao
interface JournalDao {

    @Transaction
    @Query("SELECT * FROM invent_journal WHERE journalId = :journalId")
    fun observeJournal(journalId: String): Flow<JournalWithLines?>

    @Transaction
    @Query("SELECT * FROM invent_journal WHERE journalId = :journalId")
    suspend fun getJournal(journalId: String): JournalWithLines?

    @Transaction
    @Query(
        """
        SELECT * FROM invent_journal
        WHERE type = :type AND workerId = :workerId AND status = 'DRAFT'
        ORDER BY local_modified_at DESC
        """,
    )
    fun observeDrafts(type: JournalType, workerId: String): Flow<List<JournalWithLines>>

    /**
     * Lines are paged rather than loaded whole: a counting journal for a large aisle can
     * exceed a thousand rows, and the screen only ever shows a window of them.
     */
    @Query(
        """
        SELECT * FROM invent_journal_line
        WHERE journalId = :journalId
        ORDER BY lineNumber ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun observeLinesPaged(journalId: String, limit: Int, offset: Int): Flow<List<JournalLineEntity>>

    @Query("SELECT COUNT(*) FROM invent_journal_line WHERE journalId = :journalId")
    fun observeLineCount(journalId: String): Flow<Int>

    @Query("SELECT COALESCE(MAX(lineNumber), 0) FROM invent_journal_line WHERE journalId = :journalId")
    suspend fun maxLineNumber(journalId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJournal(journal: JournalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLines(lines: List<JournalLineEntity>)

    @Update
    suspend fun updateJournal(journal: JournalEntity)

    @Transaction
    suspend fun upsertLinesChunked(lines: List<JournalLineEntity>) =
        lines.chunked(Constants.DB_BATCH_SIZE).forEach { upsertLines(it) }

    @Query("DELETE FROM invent_journal_line WHERE journalLineId = :journalLineId")
    suspend fun deleteLine(journalLineId: String)

    @Query(
        """
        UPDATE invent_journal
        SET status = :status, sync_state = :syncState, last_error = :error
        WHERE journalId = :journalId
        """,
    )
    suspend fun updateStatus(
        journalId: String,
        status: JournalStatus,
        syncState: SyncState,
        error: String?,
    )

    @Query(
        """
        UPDATE invent_journal
        SET status = 'POSTED', sync_state = 'SYNCED',
            serverJournalNumber = :serverJournalNumber, postedAt = :postedAt,
            retry_count = 0, last_error = NULL
        WHERE journalId = :journalId
        """,
    )
    suspend fun markPosted(journalId: String, serverJournalNumber: String?, postedAt: Long)

    @Query("DELETE FROM invent_journal WHERE status = 'POSTED' AND postedAt < :olderThan")
    suspend fun purgePosted(olderThan: Long): Int

    @Query("DELETE FROM invent_journal")
    suspend fun clear()
}

@Dao
interface TransferOrderDao {

    @Transaction
    @Query(
        """
        SELECT * FROM transfer_order
        WHERE fromWarehouseId = :warehouseId AND status = 'CREATED'
        ORDER BY shipDate ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun observeShippable(
        warehouseId: String,
        limit: Int = Constants.ORDER_PAGE_SIZE,
        offset: Int = 0,
    ): Flow<List<TransferOrderWithLines>>

    @Transaction
    @Query(
        """
        SELECT * FROM transfer_order
        WHERE toWarehouseId = :warehouseId AND status = 'SHIPPED'
        ORDER BY receiveDate ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun observeReceivable(
        warehouseId: String,
        limit: Int = Constants.ORDER_PAGE_SIZE,
        offset: Int = 0,
    ): Flow<List<TransferOrderWithLines>>

    @Transaction
    @Query("SELECT * FROM transfer_order WHERE transferId = :transferId")
    suspend fun get(transferId: String): TransferOrderWithLines?

    @Query("SELECT MAX(modifiedAt) FROM transfer_order")
    suspend fun latestModifiedAt(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrders(orders: List<TransferOrderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLines(lines: List<TransferLineEntity>)

    @Query("DELETE FROM transfer_order")
    suspend fun clear()
}

@Dao
interface SalesOrderDao {

    @Transaction
    @Query(
        """
        SELECT * FROM sales_order
        WHERE warehouseId = :warehouseId AND status = 'BACKORDER' AND confirmed = 1
        ORDER BY deliveryDate ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun observePickable(
        warehouseId: String,
        limit: Int = Constants.ORDER_PAGE_SIZE,
        offset: Int = 0,
    ): Flow<List<SalesOrderWithLines>>

    @Transaction
    @Query("SELECT * FROM sales_order WHERE salesId = :salesId")
    suspend fun get(salesId: String): SalesOrderWithLines?

    @Query("SELECT MAX(modifiedAt) FROM sales_order WHERE warehouseId = :warehouseId")
    suspend fun latestModifiedAt(warehouseId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrders(orders: List<SalesOrderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLines(lines: List<SalesLineEntity>)

    @Query("DELETE FROM sales_order")
    suspend fun clear()
}
