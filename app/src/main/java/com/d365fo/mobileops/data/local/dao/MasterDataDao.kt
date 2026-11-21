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

/** A category path and how many active items sit directly in it. */
data class CategoryCount(val category: String, val items: Int)

/** Turns what was typed into an FTS4 query for [com.d365fo.mobileops.data.local.entity.ItemFtsEntity]. */
object ItemSearch {
    /** At most this many words are matched; more only narrows an already short list. */
    private const val MAX_WORDS = 6
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /**
     * Each run of letters and digits in [query] as a word prefix, all of them required
     * ("ibu 200" -> `ibu* 200*`); null when [query] has none (only punctuation - the item-number
     * match still runs). Lower-cased so a typed AND, OR, NOT or NEAR is a word, not an operator.
     */
    fun matchExpression(query: String): String? {
        val words = WORD.findAll(query.lowercase(java.util.Locale.ROOT)).map { it.value }.take(MAX_WORDS).toList()
        return words.takeIf { it.isNotEmpty() }?.joinToString(" ") { "$it*" }
    }
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

    /**
     * Home search's suggestions: items with a word of their name or search name starting with
     * each word of [query], or whose number contains [query]; numbers that start with it first.
     *
     * Was `LIKE '%q%'` on all three columns: a read of every row, up to a million, per search
     * (performance plan, phase 3). Names now go through [ItemFtsEntity]; the number match reads
     * only the item-number index (`SELECT rowid ... WHERE itemId LIKE`), not the rows.
     */
    suspend fun searchItems(query: String, limit: Int = 20): List<ItemEntity> {
        val match = ItemSearch.matchExpression(query)
        return if (match == null) searchItemsByNumber(query, limit) else searchItemsByWord(match, query, limit)
    }

    @Query(
        """
        SELECT * FROM item
        WHERE rowid IN (SELECT docid FROM item_fts WHERE item_fts MATCH :match
                        UNION SELECT rowid FROM item WHERE itemId LIKE '%' || :query || '%')
        ORDER BY CASE WHEN itemId LIKE :query || '%' THEN 0 ELSE 1 END, itemId
        LIMIT :limit
        """,
    )
    suspend fun searchItemsByWord(match: String, query: String, limit: Int): List<ItemEntity>

    @Query(
        """
        SELECT * FROM item
        WHERE rowid IN (SELECT rowid FROM item WHERE itemId LIKE '%' || :query || '%')
        ORDER BY CASE WHEN itemId LIKE :query || '%' THEN 0 ELSE 1 END, itemId
        LIMIT :limit
        """,
    )
    suspend fun searchItemsByNumber(query: String, limit: Int): List<ItemEntity>

    @Query("SELECT COUNT(*) FROM item")
    suspend fun itemCount(): Int

    /** Active items per category path ("" = none), from the stored counts - see [rebuildCategoryCounts]. */
    @Query("SELECT category, items FROM category_count")
    suspend fun storedCategoryCounts(): List<CategoryCount>

    @Query("DELETE FROM category_count")
    suspend fun clearCategoryCounts()

    @Query(
        """
        INSERT INTO category_count (category, items)
        SELECT IFNULL(category, ''), COUNT(*) FROM item WHERE active = 1 GROUP BY IFNULL(category, '')
        """,
    )
    suspend fun insertCategoryCounts()

    /** Recounts the item list into category_count - once per SKU sync, not per screen. */
    @androidx.room.Transaction
    suspend fun rebuildCategoryCounts() {
        clearCategoryCounts()
        insertCategoryCounts()
    }

    /**
     * Active items per category path ("" = none) - Items by category builds its tree from this.
     * Counted once and kept; an empty store with items on the device (first open after the
     * update) is filled here.
     */
    @androidx.room.Transaction
    suspend fun categoryCounts(): List<CategoryCount> {
        val stored = storedCategoryCounts()
        if (stored.isNotEmpty() || itemCount() == 0) return stored
        rebuildCategoryCounts()
        return storedCategoryCounts()
    }

    /** Active items whose category is exactly [path] ("" = items with none); uses the category index. */
    suspend fun itemsInCategory(path: String): List<ItemEntity> =
        if (path.isEmpty()) itemsWithoutCategory() else itemsWithCategory(path)

    /**
     * Items by category's search (user, 13 Nov): active items in the category or below it whose
     * number or name contains [query], or with [query] as a barcode. [all] searches every item,
     * [none] the items without a category; otherwise [path] and [below] ("path > %", LIKE-escaped).
     */
    suspend fun searchInCategory(all: Boolean, none: Boolean, path: String, below: String, query: String, limit: Int): List<ItemEntity> {
        // Names by word start, numbers anywhere - as [searchItems] (performance plan, phase 3).
        val match = ItemSearch.matchExpression(query)
        return if (match == null) {
            searchInCategoryByNumber(all, none, path, below, query, limit)
        } else {
            searchInCategoryByWord(all, none, path, below, match, query, limit)
        }
    }

    @Query(
        """
        SELECT * FROM item
        WHERE active = 1
          AND (:all OR (:none AND (category IS NULL OR category = ''))
               OR category = :path OR category LIKE :below ESCAPE '\')
          AND (rowid IN (SELECT docid FROM item_fts WHERE item_fts MATCH :match
                         UNION SELECT rowid FROM item WHERE itemId LIKE '%' || :query || '%')
               OR itemId IN (SELECT itemId FROM barcode WHERE barcode = :query))
        ORDER BY CASE WHEN itemId LIKE :query || '%' THEN 0 ELSE 1 END, itemName, itemId
        LIMIT :limit
        """,
    )
    suspend fun searchInCategoryByWord(all: Boolean, none: Boolean, path: String, below: String, match: String, query: String, limit: Int): List<ItemEntity>

    @Query(
        """
        SELECT * FROM item
        WHERE active = 1
          AND (:all OR (:none AND (category IS NULL OR category = ''))
               OR category = :path OR category LIKE :below ESCAPE '\')
          AND (rowid IN (SELECT rowid FROM item WHERE itemId LIKE '%' || :query || '%')
               OR itemId IN (SELECT itemId FROM barcode WHERE barcode = :query))
        ORDER BY CASE WHEN itemId LIKE :query || '%' THEN 0 ELSE 1 END, itemName, itemId
        LIMIT :limit
        """,
    )
    suspend fun searchInCategoryByNumber(all: Boolean, none: Boolean, path: String, below: String, query: String, limit: Int): List<ItemEntity>

    @Query("SELECT * FROM item_unit WHERE itemId = :itemId ORDER BY unitId")
    suspend fun unitsOf(itemId: String): List<com.d365fo.mobileops.data.local.entity.ItemUnitEntity>

    @Query("DELETE FROM item_unit WHERE itemId IN (:itemIds)")
    suspend fun deleteUnitsOf(itemIds: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUnits(units: List<com.d365fo.mobileops.data.local.entity.ItemUnitEntity>)

    @Query("UPDATE item SET inventoryUnit = :invent, purchaseUnit = :purch, salesUnit = :sales WHERE itemId = :itemId")
    suspend fun setItemUnits(itemId: String, invent: String, purch: String?, sales: String?)

    /**
     * One UNITS page: each item's units replace what the device had, and the item's own
     * inventory, purchase and sales units are brought up to date (the ITEMS delta misses those).
     */
    @Transaction
    suspend fun applyUnitRows(rows: List<SkuUnitRow>) {
        if (rows.isEmpty()) return
        deleteUnitsOf(rows.map { it.itemId })
        insertUnits(rows.flatMap { it.units })
        rows.forEach { if (it.inventUnit.isNotBlank()) setItemUnits(it.itemId, it.inventUnit, it.purchUnit, it.salesUnit) }
    }

    @Query("DELETE FROM item_unit WHERE modifiedAt < :before")
    suspend fun deleteUnitsNotSeenSince(before: Long): Int

    @Query("SELECT * FROM item WHERE category = :path AND active = 1 ORDER BY itemName, itemId")
    suspend fun itemsWithCategory(path: String): List<ItemEntity>

    @Query("SELECT * FROM item WHERE (category IS NULL OR category = '') AND active = 1 ORDER BY itemName, itemId")
    suspend fun itemsWithoutCategory(): List<ItemEntity>

    /** The CATEGORIES feed's change - the item's category only, nothing else on the row. */
    @Query("UPDATE item SET category = :category WHERE itemId = :itemId")
    suspend fun setCategory(itemId: String, category: String?)

    /** A CATEGORIES page in one transaction - was a commit per item, up to 5,000 a page. */
    @Transaction
    suspend fun setCategories(rows: List<Pair<String, String?>>) {
        rows.forEach { (itemId, category) -> setCategory(itemId, category) }
    }

    @Query("SELECT * FROM item WHERE itemId IN (:itemIds)")
    suspend fun itemsByIds(itemIds: List<String>): List<ItemEntity>

    @Query("SELECT * FROM barcode WHERE itemId = :itemId ORDER BY active DESC, barcode")
    suspend fun barcodesOf(itemId: String): List<BarcodeEntity>

    @Query("SELECT COUNT(*) FROM barcode")
    suspend fun barcodeCount(): Int

    /**
     * Replaces [items] by number. Deletes first, then inserts: a `REPLACE` removes the old row
     * without firing delete triggers, which would leave the old name in [ItemFtsEntity]'s index
     * under a row id a later item could be given.
     */
    @Transaction
    suspend fun upsertItems(items: List<ItemEntity>) {
        // 900: under the 999 bound variables SQLite allows before 3.32 (Android 8-10).
        items.map { it.itemId }.chunked(900).forEach { deleteItems(it) }
        insertItems(items)
    }

    @Query("DELETE FROM item WHERE itemId IN (:itemIds)")
    suspend fun deleteItems(itemIds: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<ItemEntity>)

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

/** One UNITS feed row - see SkuRows.units. */
data class SkuUnitRow(
    val itemId: String,
    val inventUnit: String,
    val purchUnit: String?,
    val salesUnit: String?,
    val units: List<com.d365fo.mobileops.data.local.entity.ItemUnitEntity>,
)
