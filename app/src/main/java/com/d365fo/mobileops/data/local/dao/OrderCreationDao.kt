package com.d365fo.mobileops.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.d365fo.mobileops.data.local.entity.CreatedOrderEntity
import com.d365fo.mobileops.data.local.entity.CreatedOrderStatus
import com.d365fo.mobileops.data.local.entity.OrderDraftEntity
import com.d365fo.mobileops.data.local.entity.OrderKind
import com.d365fo.mobileops.data.local.entity.VendorEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OrderCreationDao {

    // -- Vendors ----------------------------------------------------------------------------

    @Query(
        "SELECT * FROM order_vendor WHERE accountNum LIKE '%' || :query || '%' OR name LIKE '%' || :query || '%' " +
            // The account typed in full or from its start first (19721 above 21961 and 31995).
            "ORDER BY CASE WHEN accountNum = :query THEN 0 WHEN accountNum LIKE :query || '%' THEN 1 ELSE 2 END, name LIMIT :limit",
    )
    suspend fun searchVendors(query: String, limit: Int = 100): List<VendorEntity>

    @Query("SELECT * FROM order_vendor WHERE accountNum = :accountNum")
    suspend fun vendor(accountNum: String): VendorEntity?

    @Query("SELECT COUNT(*) FROM order_vendor")
    suspend fun vendorCount(): Int

    @Query("SELECT MAX(savedAt) FROM order_vendor")
    suspend fun vendorsSavedAt(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVendors(vendors: List<VendorEntity>)

    @Query("DELETE FROM order_vendor WHERE savedAt < :before")
    suspend fun deleteVendorsSavedBefore(before: Long)

    /** A full refresh: the new list in, vendors no longer in it (put on hold since) out. */
    @Transaction
    suspend fun replaceVendors(vendors: List<VendorEntity>, savedAt: Long) {
        vendors.chunked(500).forEach { upsertVendors(it) }
        deleteVendorsSavedBefore(savedAt)
    }

    // -- Created orders -----------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrder(order: CreatedOrderEntity)

    @Query("SELECT * FROM created_order WHERE id = :id")
    suspend fun order(id: String): CreatedOrderEntity?

    @Query("SELECT * FROM created_order WHERE kind = :kind ORDER BY createdAt DESC LIMIT :limit")
    fun observeOrders(kind: OrderKind, limit: Int = 20): Flow<List<CreatedOrderEntity>>

    @Query("SELECT * FROM created_order WHERE id = :id")
    fun observeOrder(id: String): Flow<CreatedOrderEntity?>

    @Query(
        "UPDATE created_order SET status = :status, documentNumber = :documentNumber, total = :total, " +
            "currencyCode = :currencyCode, message = :message, pricedLinesJson = :pricedLinesJson, updatedAt = :now " +
            "WHERE id = :id",
    )
    suspend fun markOrder(
        id: String,
        status: CreatedOrderStatus,
        documentNumber: String?,
        total: Double?,
        currencyCode: String?,
        message: String?,
        pricedLinesJson: String?,
        now: Long,
    )

    // -- Drafts -------------------------------------------------------------------------------

    @Query("SELECT * FROM order_draft WHERE kind = :kind AND userName = :userName")
    suspend fun draft(kind: OrderKind, userName: String): OrderDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDraft(draft: OrderDraftEntity)

    @Query("DELETE FROM order_draft WHERE kind = :kind AND userName = :userName")
    suspend fun deleteDraft(kind: OrderKind, userName: String)

    /** Drafts with lines count as unsent work - see UnsentWork. */
    @Query("SELECT COUNT(*) FROM order_draft")
    suspend fun draftCount(): Int
}
