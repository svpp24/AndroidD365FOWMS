package com.d365fo.mobileops.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.d365fo.mobileops.data.local.entity.GoodsReceiptEntity
import com.d365fo.mobileops.data.local.entity.GoodsReceiptLineEntity
import com.d365fo.mobileops.data.local.entity.GoodsReceiptWithLines
import com.d365fo.mobileops.data.local.entity.ReceiptStatus
import com.d365fo.mobileops.data.local.entity.SyncState
import com.d365fo.mobileops.util.Constants
import kotlinx.coroutines.flow.Flow

@Dao
interface GoodsReceiptDao {

    // -- Reads ---------------------------------------------------------------------------

    @Transaction
    @Query("SELECT * FROM goods_receipt WHERE receiptId = :receiptId")
    fun observeReceipt(receiptId: String): Flow<GoodsReceiptWithLines?>

    @Transaction
    @Query("SELECT * FROM goods_receipt WHERE receiptId = :receiptId")
    suspend fun getReceipt(receiptId: String): GoodsReceiptWithLines?

    /**
     * The worker's in-progress session for an order. At most one draft per PO per device —
     * reopening the order resumes it rather than starting a second receipt.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM goods_receipt
        WHERE purchId = :purchId AND workerId = :workerId AND status = 'DRAFT'
        ORDER BY local_modified_at DESC
        LIMIT 1
        """,
    )
    suspend fun findDraftFor(purchId: String, workerId: String): GoodsReceiptWithLines?

    @Query("SELECT * FROM goods_receipt_line WHERE receiptId = :receiptId ORDER BY capturedAt ASC")
    fun observeLines(receiptId: String): Flow<List<GoodsReceiptLineEntity>>

    /**
     * Quantity already captured on this device for a PO line but not yet posted. Added to
     * the server-side received quantity when computing what is still receivable, so two
     * offline sessions cannot together over-receive the line.
     */
    @Query(
        """
        SELECT COALESCE(SUM(CAST(l.inventoryQuantity AS REAL)), 0)
        FROM goods_receipt_line l
        INNER JOIN goods_receipt r ON r.receiptId = l.receiptId
        WHERE l.purchLineId = :purchLineId
          AND r.status IN ('DRAFT', 'PENDING_POST')
        """,
    )
    suspend fun uncommittedQuantityForLine(purchLineId: String): Double

    @Query(
        """
        SELECT COUNT(*) FROM goods_receipt
        WHERE status = 'PENDING_POST' OR sync_state = 'PENDING'
        """,
    )
    fun observePendingCount(): Flow<Int>

    /** Serial numbers captured on-device but not yet posted — local uniqueness guard. */
    @Query(
        """
        SELECT COUNT(*) FROM goods_receipt_line l
        INNER JOIN goods_receipt r ON r.receiptId = l.receiptId
        WHERE l.itemId = :itemId AND l.serialId = :serialId AND r.status != 'FAILED'
        """,
    )
    suspend fun countLocalSerialUses(itemId: String, serialId: String): Int

    // -- Writes --------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReceipt(receipt: GoodsReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLines(lines: List<GoodsReceiptLineEntity>)

    @Update
    suspend fun updateReceipt(receipt: GoodsReceiptEntity)

    @Delete
    suspend fun deleteLine(line: GoodsReceiptLineEntity)

    @Query("DELETE FROM goods_receipt WHERE receiptId = :receiptId")
    suspend fun deleteReceipt(receiptId: String)

    /**
     * Adds a captured line and bumps the header's local modification stamp in one
     * transaction, so the header timestamp can never lag its lines.
     */
    @Transaction
    suspend fun addLine(line: GoodsReceiptLineEntity, header: GoodsReceiptEntity) {
        upsertLines(listOf(line))
        updateReceipt(header.copy(sync = header.sync.copy(localModifiedAt = System.currentTimeMillis())))
    }

    @Transaction
    suspend fun addLines(lines: List<GoodsReceiptLineEntity>, header: GoodsReceiptEntity) {
        lines.chunked(Constants.DB_BATCH_SIZE).forEach { upsertLines(it) }
        updateReceipt(header.copy(sync = header.sync.copy(localModifiedAt = System.currentTimeMillis())))
    }

    @Query(
        """
        UPDATE goods_receipt
        SET status = :status, sync_state = :syncState, last_error = :error
        WHERE receiptId = :receiptId
        """,
    )
    suspend fun updateStatus(
        receiptId: String,
        status: ReceiptStatus,
        syncState: SyncState,
        error: String?,
    )

    /** Applied when D365FO confirms the posting and returns the packing slip number. */
    @Query(
        """
        UPDATE goods_receipt
        SET status = 'POSTED',
            sync_state = 'SYNCED',
            serverPackingSlipId = :serverPackingSlipId,
            server_rec_id = :serverRecId,
            postedAt = :postedAt,
            retry_count = 0,
            last_error = NULL
        WHERE receiptId = :receiptId
        """,
    )
    suspend fun markPosted(
        receiptId: String,
        serverPackingSlipId: String?,
        serverRecId: Long?,
        postedAt: Long,
    )

    /** Posted receipts older than the retention window are purged to bound database growth. */
    @Query("DELETE FROM goods_receipt WHERE status = 'POSTED' AND postedAt < :olderThan")
    suspend fun purgePosted(olderThan: Long): Int

    @Query("DELETE FROM goods_receipt")
    suspend fun clear()
}
