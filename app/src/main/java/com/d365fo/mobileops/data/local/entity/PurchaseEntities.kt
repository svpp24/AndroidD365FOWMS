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

// ---------------------------------------------------------------------------------------
// Locally captured goods receipt (GRN)
// ---------------------------------------------------------------------------------------

enum class ReceiptStatus { DRAFT, PENDING_POST, POSTED, FAILED }

/**
 * A worker's receiving session against one purchase order.
 *
 * Modelled as its own aggregate rather than folded into the inventory-journal tables: in
 * D365FO a product receipt is a PurchFormLetter posting against PurchLine, not an
 * InventJournal. Sharing one table would force every journal line to carry nullable
 * purchase-order columns and would make the posting dispatcher ambiguous.
 */
@Entity(
    tableName = "goods_receipt",
    indices = [
        Index("purchId"),
        Index("status"),
        Index("sync_state"),
        Index("local_modified_at"),
    ],
)
data class GoodsReceiptEntity(
    @PrimaryKey val receiptId: String,
    val purchId: String,
    val workerId: String,
    val deviceId: String,
    /** Legal entity the receipt posts into. Captured at draft time, not at post time. */
    val dataAreaId: String,
    /** D365FO personnel number, so the posting is attributed to a real worker. */
    val personnelNumber: String?,
    /** Worker-entered packing slip / vendor delivery-note number. */
    val vendorPackingSlipId: String,
    val receiptDate: Long,
    val status: ReceiptStatus,
    /** Journal/voucher number returned by D365FO after a successful post. */
    val serverPackingSlipId: String?,
    val postedAt: Long?,
    @Embedded val sync: SyncMeta = SyncMeta(),
)

@Entity(
    tableName = "goods_receipt_line",
    foreignKeys = [
        ForeignKey(
            entity = GoodsReceiptEntity::class,
            parentColumns = ["receiptId"],
            childColumns = ["receiptId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("receiptId"),
        Index("purchLineId"),
        Index("itemId"),
        Index(value = ["receiptId", "purchLineId"]),
    ],
)
data class GoodsReceiptLineEntity(
    @PrimaryKey val receiptLineId: String,
    val receiptId: String,
    val purchLineId: String,
    val purchId: String,
    val lineNumber: Double,
    val itemId: String,
    /** Denormalised from the PO line so the receipt renders without a join. */
    val itemName: String,
    /** Quantity as the worker entered it, in [enteredUnit]. */
    val enteredQuantity: String,
    val enteredUnit: String,
    /** Converted to the inventory unit — this is what actually posts. */
    val inventoryQuantity: String,
    val inventoryUnit: String,
    val siteId: String?,
    val warehouseId: String?,
    val locationId: String?,
    val batchId: String?,
    val serialId: String?,
    /** True when a supervisor PIN was used to push past a tolerance warning. */
    val toleranceOverridden: Boolean,
    val overrideReason: String?,
    val capturedAt: Long,
)

data class GoodsReceiptWithLines(
    @Embedded val receipt: GoodsReceiptEntity,
    @Relation(parentColumn = "receiptId", entityColumn = "receiptId")
    val lines: List<GoodsReceiptLineEntity>,
)
