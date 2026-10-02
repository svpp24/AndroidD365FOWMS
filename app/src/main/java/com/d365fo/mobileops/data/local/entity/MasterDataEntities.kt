package com.d365fo.mobileops.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Which tracking dimensions D365FO requires for an item, resolved from its tracking
 * dimension group. Cached locally so a scan can decide whether to prompt for batch/serial
 * without a round trip.
 */
enum class TrackingRequirement {
    NONE,
    BATCH,
    SERIAL,
    BATCH_AND_SERIAL;

    val requiresBatch: Boolean get() = this == BATCH || this == BATCH_AND_SERIAL
    val requiresSerial: Boolean get() = this == SERIAL || this == BATCH_AND_SERIAL
}

/** InventTable + InventTableModule (UoM) + tracking dimension group flags. */
@Entity(
    tableName = "item",
    indices = [
        Index("itemName"),
        Index("active"),
        Index("modifiedAt"),
    ],
)
data class ItemEntity(
    @PrimaryKey val itemId: String,
    val itemName: String,
    val searchName: String?,
    /** Inventory unit — captured quantities are converted into this before posting. */
    val inventoryUnit: String,
    val purchaseUnit: String?,
    val salesUnit: String?,
    val trackingRequirement: TrackingRequirement,
    /** True when the item's dimension group marks Location as active and mandatory. */
    val locationMandatory: Boolean,
    val active: Boolean,
    /** Server modifiedDateTime — the delta-sync watermark. */
    val modifiedAt: Long,
)

/** EcoResProductBarcode. One item can carry many barcodes (EAN, UPC, inner/outer pack). */
@Entity(
    tableName = "barcode",
    indices = [Index("itemId"), Index("modifiedAt")],
)
data class BarcodeEntity(
    @PrimaryKey val barcode: String,
    val itemId: String,
    /** Some barcodes encode a pack: one scan equals this many [unitId] units. */
    val quantityPerScan: String,
    val unitId: String?,
    val active: Boolean,
    val modifiedAt: Long,
    /** Product dimensions (variant) - see [com.d365fo.mobileops.domain.model.ProductVariant]. */
    val configId: String? = null,
    val sizeId: String? = null,
    val colorId: String? = null,
    val styleId: String? = null,
)

/** InventLocation (warehouse) joined with the non-WMS location master. */
@Entity(
    tableName = "location",
    primaryKeys = ["siteId", "warehouseId", "locationId"],
    indices = [
        Index("warehouseId"),
        Index("active"),
        Index("modifiedAt"),
    ],
)
data class LocationEntity(
    val siteId: String,
    val warehouseId: String,
    val locationId: String,
    val locationName: String?,
    val active: Boolean,
    val modifiedAt: Long,
)

/**
 * InventDim combinations already known to D365FO, so a posting can reuse an existing
 * InventDimId instead of asking the server to mint one on every transaction.
 */
@Entity(
    tableName = "invent_dim",
    indices = [
        Index(value = ["itemId", "siteId", "warehouseId", "locationId", "batchId", "serialId"]),
    ],
)
data class InventDimEntity(
    @PrimaryKey val inventDimId: String,
    val itemId: String?,
    val siteId: String?,
    val warehouseId: String?,
    val locationId: String?,
    val batchId: String?,
    val serialId: String?,
    val configId: String?,
    val colorId: String?,
    val sizeId: String?,
    val styleId: String?,
)

/** InventOnHandV2 snapshot. Treated as stale after five minutes. */
@Entity(
    tableName = "onhand_cache",
    primaryKeys = ["itemId", "inventDimId"],
    indices = [
        Index(value = ["itemId", "siteId", "warehouseId"]),
        Index("cachedAt"),
    ],
)
data class OnHandCacheEntity(
    val itemId: String,
    val inventDimId: String,
    val siteId: String?,
    val warehouseId: String?,
    val locationId: String?,
    val batchId: String?,
    val serialId: String?,
    val physicalInventory: String,
    val availablePhysical: String,
    val reservedPhysical: String,
    val orderedInTotal: String,
    val reservedOrdered: String,
    val availableOrdered: String,
    val cachedAt: Long,
)

/**
 * Delta-sync watermarks, one row per synced entity set. Holds the last successfully
 * consumed server modifiedDateTime so the next pull only asks for what changed.
 */
@Entity(tableName = "sync_watermark")
data class SyncWatermarkEntity(
    @PrimaryKey val entitySet: String,
    val lastModifiedAt: Long,
    val lastSyncAt: Long,
    val lastRecordCount: Int,
)

/**
 * Audit trail for every rejected scan and failed validation. Required for warehouse dispute
 * resolution: which worker, which device, and exactly what was physically scanned.
 */
@Entity(
    tableName = "validation_audit",
    indices = [Index("occurredAt"), Index("workerId")],
)
data class ValidationAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurredAt: Long,
    val workerId: String,
    val deviceId: String,
    val module: String,
    val field: String,
    /** Exactly what the scanner emitted, unmodified. */
    val rawValue: String,
    val reason: String,
    val overriddenBySupervisor: Boolean,
)

/**
 * InventLocation — the warehouse list behind the on-hand filter.
 *
 * Cached because it is stable: an estate of 143 warehouses changes a few times a year, while
 * the list is needed every time someone widens an enquiry. Holding it locally means the picker
 * opens instantly, works through a wifi dropout, and costs one round trip a day instead of one
 * per screen visit.
 *
 * [cachedAt] is stored rather than inferred so staleness is a fact the UI can show, not a guess.
 */
@Entity(
    tableName = "warehouse",
    indices = [Index("siteId")],
)
data class WarehouseEntity(
    @PrimaryKey val warehouseId: String,
    val warehouseName: String,
    val siteId: String,
    /**
     * True when the warehouse runs Advanced Warehouse Management. This app is non-WMS, so
     * receiving into one of these must not be offered — it goes through directed work and
     * license plates, not a product receipt against PurchLine.
     */
    val wmsEnabled: Boolean,
    val cachedAt: Long,
)
