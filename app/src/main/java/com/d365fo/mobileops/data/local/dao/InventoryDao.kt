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

/** What [JournalDao.mergeCountingLines] did. */
data class MergeResult(val added: Int, val removed: Int)

/** One counting journal's line tallies - see [JournalDao.observeCountingProgress]. */
data class CountingProgressRow(
    val journalId: String,
    val total: Int,
    val withQuantity: Int,
    val markedOnly: Int,
)

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

    /** Persisted as soon as the worker types it, not held only in view state - counting a
     *  200+ line journal is easily interrupted by a shift change or a dropped app. */
    @Query(
        "UPDATE invent_journal_line SET countedQuantity = :countedQuantity WHERE journalLineId = :journalLineId",
    )
    suspend fun setCountedQuantity(journalLineId: String, countedQuantity: String?)

    @Query("SELECT COUNT(*) FROM invent_journal_line WHERE journalId = :journalId AND countedQuantity IS NOT NULL")
    fun observeCountedLineCount(journalId: String): Flow<Int>

    // -- Counting workflow -----------------------------------------------------------------

    /** Every counting journal that has data on this device - the journal list's "has data" and
     *  count-state source, so it survives the app being restarted (unlike the in-memory
     *  download tracker). */
    @Query("SELECT * FROM invent_journal WHERE type = 'COUNTING'")
    fun observeCountingJournals(): Flow<List<JournalEntity>>

    /** Total / with-a-quantity / marked-only line counts per counting journal. */
    @Query(
        """
        SELECT journalId,
               COUNT(*) AS total,
               SUM(CASE WHEN countedQuantity IS NOT NULL THEN 1 ELSE 0 END) AS withQuantity,
               SUM(CASE WHEN countedQuantity IS NULL AND markedCounted = 1 THEN 1 ELSE 0 END) AS markedOnly
        FROM invent_journal_line
        WHERE journalId IN (SELECT journalId FROM invent_journal WHERE type = 'COUNTING')
        GROUP BY journalId
        """,
    )
    fun observeCountingProgress(): Flow<List<CountingProgressRow>>

    /** All of a journal's lines, for the lines screen's own search/filter - a counting journal
     *  is a few hundred lines, and filtering in memory keeps search instant and offline. */
    @Query("SELECT * FROM invent_journal_line WHERE journalId = :journalId ORDER BY lineNumber ASC")
    fun observeAllLines(journalId: String): Flow<List<JournalLineEntity>>

    @Query("SELECT * FROM invent_journal_line WHERE journalId = :journalId")
    suspend fun getLinesOnce(journalId: String): List<JournalLineEntity>

    /**
     * "Get data again": folds a fresh export of a counting journal into what is already on the
     * device WITHOUT losing any counting work. Lines are matched on `lineNumber`; for one that is
     * already here the counted quantity, the marker, the reason code and an already-resolved item
     * name are kept, while what D365FO owns (on-hand, location, batch, serial, site) is refreshed.
     * A line that is new in the export is added. A line that has disappeared from the export is
     * removed only if nothing was done to it - a line with a quantity or a marker is kept rather
     * than silently discarding a count. One transaction, so a count typed while a refresh is
     * landing is never overwritten by it.
     */
    @Transaction
    suspend fun mergeCountingLines(journalId: String, incoming: List<JournalLineEntity>): MergeResult {
        val existing = getLinesOnce(journalId).associateBy { it.lineNumber }
        val incomingNumbers = incoming.mapTo(HashSet()) { it.lineNumber }

        val merged = incoming.map { fresh ->
            val old = existing[fresh.lineNumber] ?: return@map fresh
            fresh.copy(
                journalLineId = old.journalLineId,
                itemName = if (old.itemName != old.itemId) old.itemName else fresh.itemName,
                // What is on the device wins (it may be newer than what was submitted); if the
                // worker has not counted this line here, take whatever D365FO holds for it.
                countedQuantity = old.countedQuantity ?: fresh.countedQuantity,
                markedCounted = old.markedCounted,
                reasonCode = old.reasonCode,
                varianceOverridden = old.varianceOverridden,
                capturedAt = old.capturedAt,
                countedBy = old.countedBy ?: fresh.countedBy,
                countedAt = old.countedAt ?: fresh.countedAt,
                lineCountStartedAt = old.lineCountStartedAt,
            )
        }
        upsertLinesChunked(merged)

        val removable = existing.values.filter {
            it.lineNumber !in incomingNumbers && it.countedQuantity == null && !it.markedCounted
        }
        removable.forEach { deleteLine(it.journalLineId) }

        return MergeResult(
            added = incoming.count { it.lineNumber !in existing },
            removed = removable.size,
        )
    }

    @Query("UPDATE invent_journal_line SET markedCounted = :marked WHERE journalLineId = :journalLineId")
    suspend fun setMarkedCounted(journalLineId: String, marked: Boolean)

    /** Only the first press counts - re-pressing Start counting must not reset the time. */
    @Query("UPDATE invent_journal SET countStartedAt = :at WHERE journalId = :journalId AND countStartedAt IS NULL")
    suspend fun markCountStarted(journalId: String, at: Long)

    /** Item ids whose name was never resolved (the export has no item name, so a line's
     *  itemName starts out equal to its itemId). */
    @Query("SELECT DISTINCT itemId FROM invent_journal_line WHERE journalId = :journalId AND itemName = itemId")
    suspend fun unnamedItemIds(journalId: String): List<String>

    @Query("UPDATE invent_journal_line SET itemName = :itemName WHERE journalId = :journalId AND itemId = :itemId")
    suspend fun setItemName(journalId: String, itemId: String, itemName: String)

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

    /** Counting: the counts reached D365FO. Not "posted" - the journal is still open there. */
    @Query(
        """
        UPDATE invent_journal
        SET status = 'SUBMITTED', sync_state = 'SYNCED', retry_count = 0, last_error = :warning
        WHERE journalId = :journalId
        """,
    )
    suspend fun markCountingSubmitted(journalId: String, warning: String?)

    /** What the server says about a counting journal, refreshed on every Get data. */
    @Query(
        """
        UPDATE invent_journal
        SET transferMode = :transferMode, locationFirst = :locationFirst, zeroStockCheck = :zeroStockCheck,
            countMode = :countMode, passNumber = :passNumber, recountOf = :recountOf,
            originType = :originType, priority = :priority, toleranceInfo = :toleranceInfo, abcClass = :abcClass
        WHERE journalId = :journalId
        """,
    )
    suspend fun updateCountingAttributes(
        journalId: String,
        transferMode: com.d365fo.mobileops.data.local.entity.TransferMode,
        locationFirst: Boolean,
        zeroStockCheck: Boolean,
        countMode: String,
        passNumber: Int,
        recountOf: String?,
        originType: String,
        priority: Int,
        toleranceInfo: String?,
        abcClass: String?,
    )

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
