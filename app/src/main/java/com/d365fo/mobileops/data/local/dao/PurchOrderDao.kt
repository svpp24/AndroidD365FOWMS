package com.d365fo.mobileops.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.d365fo.mobileops.data.local.entity.PurchLineEntity
import com.d365fo.mobileops.data.local.entity.PurchOrderEntity
import com.d365fo.mobileops.data.local.entity.PurchOrderWithLines
import com.d365fo.mobileops.util.Constants
import kotlinx.coroutines.flow.Flow

@Dao
interface PurchOrderDao {

    // -- Reads ---------------------------------------------------------------------------

    /**
     * The receiving worklist. Filtered to the worker's warehouse and to receivable statuses
     * so a device never renders the tenant's whole open-PO book.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM purch_order
        WHERE warehouseId = :warehouseId
          AND status IN ('NONE', 'BACKORDER')
          AND approved = 1
        ORDER BY deliveryDate ASC, purchId ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun observeOpenOrders(
        warehouseId: String,
        limit: Int = Constants.ORDER_PAGE_SIZE,
        offset: Int = 0,
    ): Flow<List<PurchOrderWithLines>>

    /**
     * Worker search. Matches PO number or vendor, prefix-anchored on the PO number so the
     * index is usable; vendor match is a contains-search, which is acceptable because the
     * candidate set is already narrowed to one warehouse.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM purch_order
        WHERE warehouseId = :warehouseId
          AND status IN ('NONE', 'BACKORDER')
          AND approved = 1
          AND (purchId LIKE :query || '%'
               OR vendorAccount LIKE :query || '%'
               OR vendorName LIKE '%' || :query || '%')
        ORDER BY
          CASE WHEN purchId LIKE :query || '%' THEN 0 ELSE 1 END,
          deliveryDate ASC
        LIMIT :limit
        """,
    )
    fun searchOpenOrders(
        warehouseId: String,
        query: String,
        limit: Int = Constants.ORDER_PAGE_SIZE,
    ): Flow<List<PurchOrderWithLines>>

    @Transaction
    @Query("SELECT * FROM purch_order WHERE purchId = :purchId")
    fun observeOrder(purchId: String): Flow<PurchOrderWithLines?>

    @Transaction
    @Query("SELECT * FROM purch_order WHERE purchId = :purchId")
    suspend fun getOrder(purchId: String): PurchOrderWithLines?

    @Query("SELECT * FROM purch_line WHERE lineId = :lineId")
    suspend fun getLine(lineId: String): PurchLineEntity?

    /** Scan resolution: which lines on this order carry the scanned item and still owe stock. */
    @Query(
        """
        SELECT * FROM purch_line
        WHERE purchId = :purchId
          AND itemId = :itemId
          AND (CAST(orderedQuantity AS REAL) - CAST(receivedQuantity AS REAL)) > 0
        ORDER BY lineNumber ASC
        """,
    )
    suspend fun findOpenLinesForItem(purchId: String, itemId: String): List<PurchLineEntity>

    @Query("SELECT COUNT(*) FROM purch_order WHERE warehouseId = :warehouseId AND status IN ('NONE','BACKORDER')")
    fun observeOpenOrderCount(warehouseId: String): Flow<Int>

    // -- Delta sync ----------------------------------------------------------------------

    /** Watermark for the next `$filter=ModifiedDateTime gt ...` pull. */
    @Query("SELECT MAX(modifiedAt) FROM purch_order WHERE warehouseId = :warehouseId")
    suspend fun latestModifiedAt(warehouseId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrders(orders: List<PurchOrderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLines(lines: List<PurchLineEntity>)

    /**
     * Replaces an order's line set atomically. A partial line list from a failed pull must
     * never be observable by the receiving screen.
     */
    @Transaction
    suspend fun replaceOrderWithLines(order: PurchOrderEntity, lines: List<PurchLineEntity>) {
        upsertOrders(listOf(order))
        deleteLinesFor(order.purchId)
        lines.chunked(Constants.DB_BATCH_SIZE).forEach { upsertLines(it) }
    }

    @Transaction
    suspend fun upsertOrdersWithLines(
        orders: List<PurchOrderEntity>,
        linesByOrder: Map<String, List<PurchLineEntity>>,
    ) {
        orders.chunked(Constants.DB_BATCH_SIZE).forEach { upsertOrders(it) }
        linesByOrder.forEach { (purchId, lines) ->
            deleteLinesFor(purchId)
            lines.chunked(Constants.DB_BATCH_SIZE).forEach { upsertLines(it) }
        }
    }

    /** Applied after a successful GRN post so the worklist reflects the new remaining qty. */
    @Query("UPDATE purch_line SET receivedQuantity = :receivedQuantity WHERE lineId = :lineId")
    suspend fun updateReceivedQuantity(lineId: String, receivedQuantity: String)

    @Query("DELETE FROM purch_line WHERE purchId = :purchId")
    suspend fun deleteLinesFor(purchId: String)

    @Query("DELETE FROM purch_order")
    suspend fun clear()
}
