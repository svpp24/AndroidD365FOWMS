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
    val configId: String? = null,
    val sizeId: String? = null,
    val colorId: String? = null,
    val styleId: String? = null,
) {
    /** The variant this barcode identifies - empty for an item-level barcode. */
    val variant: com.d365fo.mobileops.domain.model.ProductVariant
        get() = com.d365fo.mobileops.domain.model.ProductVariant.of(configId, sizeId, colorId, styleId)
}

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
               b.active         AS barcodeActive,
               b.configId       AS configId,
               b.sizeId         AS sizeId,
               b.colorId        AS colorId,
               b.styleId        AS styleId
        FROM barcode b
        INNER JOIN item i ON i.itemId = b.itemId
        WHERE b.barcode = :barcode
        LIMIT 1
        """,
    )
    suspend fun resolveBarcode(barcode: String): ScannedItem?

    @Query("SELECT * FROM item WHERE itemId = :itemId")
    suspend fun getItem(itemId: String): ItemEntity?

    @Query("SELECT COUNT(*) FROM item")
    suspend fun itemCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<ItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBarcodes(barcodes: List<BarcodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVariants(variants: List<com.d365fo.mobileops.data.local.entity.ItemVariantEntity>)

    @Query("SELECT * FROM item_variant WHERE itemId = :itemId ORDER BY configId, sizeId, colorId, styleId")
    suspend fun variantsOf(itemId: String): List<com.d365fo.mobileops.data.local.entity.ItemVariantEntity>

    // After a full SKU refresh: rows it did not see are no longer in D365FO.
    @Query("DELETE FROM item WHERE modifiedAt < :before")
    suspend fun deleteItemsNotSeenSince(before: Long): Int

    @Query("DELETE FROM barcode WHERE modifiedAt < :before")
    suspend fun deleteBarcodesNotSeenSince(before: Long): Int

    @Query("DELETE FROM item_variant WHERE syncedAt < :before")
    suspend fun deleteVariantsNotSeenSince(before: Long): Int

    /**
     * Master-data pull writes in bounded transactions so a large sync stays responsive.
     *
     * `chunkSize` defaults to the shared interactive-write size but is a real parameter, not
     * just documentation - `SkuSyncWorker` calls this with `Constants.SKU_SYNC_BATCH_SIZE`
     * (5000) for a 1M-row master load, where `DB_BATCH_SIZE` (100) would mean 10,000+
     * individual transactions.
     */
    @Transaction
    suspend fun upsertItemsChunked(items: List<ItemEntity>, chunkSize: Int = Constants.DB_BATCH_SIZE) =
        items.chunked(chunkSize).forEach { upsertItems(it) }

    @Transaction
    suspend fun upsertBarcodesChunked(barcodes: List<BarcodeEntity>, chunkSize: Int = Constants.DB_BATCH_SIZE) =
        barcodes.chunked(chunkSize).forEach { upsertBarcodes(it) }

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(locations: List<LocationEntity>)

    @Query("DELETE FROM location")
    suspend fun clear()
}

@Dao
interface InventDimDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(dims: List<InventDimEntity>)

    @Query("DELETE FROM invent_dim")
    suspend fun clear()
}

@Dao
interface OnHandDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<OnHandCacheEntity>)

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
