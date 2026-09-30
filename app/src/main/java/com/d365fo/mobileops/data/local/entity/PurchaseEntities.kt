package com.d365fo.mobileops.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** PurchTable.PurchStatus. Only [BACKORDER] and [NONE] are receivable. */
enum class PurchaseOrderStatus {
    NONE,
    BACKORDER,
    RECEIVED,
    INVOICED,
    CANCELED;

    val isOpen: Boolean get() = this == NONE || this == BACKORDER
}

/** PurchTable header snapshot. Read-only locally — receipts never mutate the order itself. */
@Entity(
    tableName = "purch_order",
    indices = [
        Index("vendorAccount"),
        Index("status"),
        Index("warehouseId"),
        Index("deliveryDate"),
        Index("modifiedAt"),
    ],
)
data class PurchOrderEntity(
    @PrimaryKey val purchId: String,
    val vendorAccount: String,
    val vendorName: String,
    val status: PurchaseOrderStatus,
    val siteId: String?,
    val warehouseId: String?,
    val deliveryDate: Long?,
    val currencyCode: String?,
    /** PurchTable.DocumentState / approval — only Approved orders may be received. */
    val approved: Boolean,
    /**
     * Over/under delivery percentages from the order. D365FO stores these per line; the
     * header value is the fallback when a line does not override it.
     */
    val overDeliveryPercent: String?,
    val underDeliveryPercent: String?,
    val modifiedAt: Long,
    @Embedded val sync: SyncMeta = SyncMeta(),
)

/** PurchLine snapshot. */
@Entity(
    tableName = "purch_line",
    foreignKeys = [
        ForeignKey(
            entity = PurchOrderEntity::class,
            parentColumns = ["purchId"],
            childColumns = ["purchId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("purchId"),
        Index("itemId"),
        Index(value = ["purchId", "lineNumber"], unique = true),
        Index("warehouseId"),
    ],
)
data class PurchLineEntity(
    @PrimaryKey val lineId: String,
    val purchId: String,
    val lineNumber: Double,
    val itemId: String,
    val itemName: String,
    val purchaseUnit: String,
    val inventoryUnit: String,
    /** Purchase-unit to inventory-unit factor, pre-resolved on pull. */
    val unitConversionFactor: String,
    val orderedQuantity: String,
    /** Already posted on a product receipt in D365FO. */
    val receivedQuantity: String,
    val siteId: String?,
    val warehouseId: String?,
    val locationId: String?,
    val overDeliveryPercent: String?,
    val underDeliveryPercent: String?,
    val lineStatus: PurchaseOrderStatus,
    val serverRecId: Long?,
    val modifiedAt: Long,
) {
    /** Remaining is derived, never stored — it must not drift from the server snapshot. */
    val remainingQuantityRaw: String
        get() = (orderedQuantity.toBigDecimal() - receivedQuantity.toBigDecimal()).toPlainString()
}

data class PurchOrderWithLines(
    @Embedded val order: PurchOrderEntity,
    @Relation(parentColumn = "purchId", entityColumn = "purchId")
    val lines: List<PurchLineEntity>,
)
