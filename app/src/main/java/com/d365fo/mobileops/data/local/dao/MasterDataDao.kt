package com.d365fo.mobileops.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.d365fo.mobileops.data.local.entity.BarcodeEntity
import com.d365fo.mobileops.data.local.entity.InventDimEntity
import com.d365fo.mobileops.data.local.entity.ItemEntity
import com.d365fo.mobileops.data.local.entity.LocationEntity
import com.d365fo.mobileops.data.local.entity.OnHandCacheEntity
import com.d365fo.mobileops.data.local.entity.SyncWatermarkEntity
import com.d365fo.mobileops.data.local.entity.TrackingRequirement
import com.d365fo.mobileops.data.local.entity.ValidationAuditEntity
import com.d365fo.mobileops.data.local.entity.WarehouseEntity
import com.d365fo.mobileops.util.Constants
import kotlinx.coroutines.flow.Flow

/** Resolved scan: the barcode row joined to its item in a single indexed hop. */
data class ScannedItem(
    val barcode: String,
    val itemId: String,
    val itemName: String,
    val inventoryUnit: String,
    val purchaseUnit: String?,
    val quantityPerScan: String,
    val scanUnit: String?,
    val trackingRequirement: TrackingRequirement,
    val locationMandatory: Boolean,
    val itemActive: Boolean,
    val barcodeActive: Boolean,
)

@Dao
interface ItemDao {

    /**
     * Layer-2 scan validation. One query, both indexes — this runs on every trigger pull and
     * must stay well under 50 ms with a 100k-item master.
     */
    @Query(
        """
        SELECT b.barcode        AS barcode,
               i.itemId         AS itemId,
               i.itemName       AS itemName,
               i.inventoryUnit  AS inventoryUnit,
               i.purchaseUnit   AS purchaseUnit,
               b.quantityPerScan AS quantityPerScan,
               b.unitId         AS scanUnit,
               i.trackingRequirement AS trackingRequirement,
               i.locationMandatory   AS locationMandatory,
               i.active         AS itemActive,
               b.active         AS barcodeActive
        FROM barcode b
        INNER JOIN item i ON i.itemId = b.itemId
        WHERE b.barcode = :barcode
        LIMIT 1
        """,
    )
    suspend fun resolveBarcode(barcode: String): ScannedItem?

    @Query("SELECT * FROM item WHERE itemId = :itemId")
    suspend fun getItem(itemId: String): ItemEntity?

    /** Manual-entry fallback when the label is damaged. */
    @Query(
        """
        SELECT * FROM item
        WHERE active = 1
          AND (itemId LIKE :query || '%' OR itemName LIKE '%' || :query || '%')
        ORDER BY CASE WHEN itemId LIKE :query || '%' THEN 0 ELSE 1 END, itemId
        LIMIT :limit
        """,
    )
    suspend fun searchItems(query: String, limit: Int = 25): List<ItemEntity>

    @Query("SELECT COUNT(*) FROM item")
    suspend fun itemCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<ItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBarcodes(barcodes: List<BarcodeEntity>)

    /** Master-data pull writes in bounded transactions so a 100k-row sync stays responsive. */
    @Transaction
    suspend fun upsertItemsChunked(items: List<ItemEntity>) =
        items.chunked(Constants.DB_BATCH_SIZE).forEach { upsertItems(it) }

    @Transaction
    suspend fun upsertBarcodesChunked(barcodes: List<BarcodeEntity>) =
        barcodes.chunked(Constants.DB_BATCH_SIZE).forEach { upsertBarcodes(it) }

    @Query("DELETE FROM item")
    suspend fun clearItems()

    @Query("DELETE FROM barcode")
    suspend fun clearBarcodes()
}

@Dao
interface LocationDao {

    /**
     * Looked up by warehouse and location only.
     *
     * The site is deliberately not part of the predicate: WarehouseLocations publishes no
     * site, so the stored siteId is blank, and a warehouse belongs to exactly one site
     * anyway - making the pair already unique.
     */
    @Query(
        """
        SELECT * FROM location
        WHERE warehouseId = :warehouseId AND locationId = :locationId
        """,
    )
    suspend fun find(warehouseId: String, locationId: String): LocationEntity?

    @Query(
        """
        SELECT * FROM location
        WHERE warehouseId = :warehouseId AND active = 1
          AND locationId LIKE :query || '%'
        ORDER BY locationId
        LIMIT :limit
        """,
    )
    suspend fun search(warehouseId: String, query: String, limit: Int = 25): List<LocationEntity>

    @Query("SELECT * FROM location WHERE warehouseId = :warehouseId AND active = 1 ORDER BY locationId")
    fun observeForWarehouse(warehouseId: String): Flow<List<LocationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(locations: List<LocationEntity>)

    @Transaction
    suspend fun upsertChunked(locations: List<LocationEntity>) =
        locations.chunked(Constants.DB_BATCH_SIZE).forEach { upsert(it) }

    @Query("DELETE FROM location")
    suspend fun clear()
}

@Dao
interface InventDimDao {

    /**
     * Finds an existing InventDimId for a dimension set. NULL-safe comparison: an unset
     * location must match a stored NULL, which `=` would not do.
     */
    @Query(
        """
        SELECT * FROM invent_dim
        WHERE itemId IS :itemId
          AND siteId IS :siteId
          AND warehouseId IS :warehouseId
          AND locationId IS :locationId
          AND batchId IS :batchId
          AND serialId IS :serialId
        LIMIT 1
        """,
    )
    suspend fun findMatching(
        itemId: String?,
        siteId: String?,
        warehouseId: String?,
        locationId: String?,
        batchId: String?,
        serialId: String?,
    ): InventDimEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(dims: List<InventDimEntity>)

    @Query("DELETE FROM invent_dim")
    suspend fun clear()
}

@Dao
interface OnHandDao {

    @Query(
        """
        SELECT * FROM onhand_cache
        WHERE itemId = :itemId
          AND (:siteId IS NULL OR siteId = :siteId)
          AND (:warehouseId IS NULL OR warehouseId = :warehouseId)
        ORDER BY warehouseId, locationId, batchId
        """,
    )
    fun observeForItem(
        itemId: String,
        siteId: String?,
        warehouseId: String?,
    ): Flow<List<OnHandCacheEntity>>

    @Query(
        """
        SELECT MIN(cachedAt) FROM onhand_cache
        WHERE itemId = :itemId
          AND (:siteId IS NULL OR siteId = :siteId)
          AND (:warehouseId IS NULL OR warehouseId = :warehouseId)
        """,
    )
    suspend fun oldestCacheStamp(itemId: String, siteId: String?, warehouseId: String?): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<OnHandCacheEntity>)

    @Query("DELETE FROM onhand_cache WHERE itemId = :itemId")
    suspend fun evictItem(itemId: String)

    @Query("DELETE FROM onhand_cache WHERE cachedAt < :olderThan")
    suspend fun evictStale(olderThan: Long): Int

    @Query("DELETE FROM onhand_cache")
    suspend fun clear()
}

@Dao
interface SyncWatermarkDao {

    @Query("SELECT * FROM sync_watermark WHERE entitySet = :entitySet")
    suspend fun get(entitySet: String): SyncWatermarkEntity?

    @Query("SELECT * FROM sync_watermark")
    fun observeAll(): Flow<List<SyncWatermarkEntity>>

    @Query("SELECT MAX(lastSyncAt) FROM sync_watermark")
    fun observeLastSyncAt(): Flow<Long?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(watermark: SyncWatermarkEntity)

    @Query("DELETE FROM sync_watermark")
    suspend fun clear()
}

@Dao
interface ValidationAuditDao {

    @Insert
    suspend fun log(entry: ValidationAuditEntity)

    @Query("SELECT * FROM validation_audit ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 200): List<ValidationAuditEntity>

    /** The audit log is capped locally; it is a triage aid, not the system of record. */
    @Query("DELETE FROM validation_audit WHERE occurredAt < :olderThan")
    suspend fun purge(olderThan: Long): Int
}

@Dao
interface WarehouseDao {

    @Query("SELECT * FROM warehouse ORDER BY warehouseId ASC")
    suspend fun all(): List<WarehouseEntity>

    @Query("SELECT COUNT(*) FROM warehouse")
    suspend fun count(): Int

    /** Newest cache timestamp, or null when the table is empty. */
    @Query("SELECT MAX(cachedAt) FROM warehouse")
    suspend fun cachedAt(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(warehouses: List<WarehouseEntity>)

    @Query("DELETE FROM warehouse")
    suspend fun clear()

    /**
     * Replaced wholesale rather than merged.
     *
     * A warehouse removed in D365FO has to disappear from the picker too; an upsert-only
     * refresh would leave it selectable for ever, and an enquiry filtered to a warehouse that
     * no longer exists returns nothing at all — which reads as "no stock" rather than as a
     * stale list.
     */
    @Transaction
    suspend fun replaceAll(warehouses: List<WarehouseEntity>) {
        clear()
        upsert(warehouses)
    }
}
