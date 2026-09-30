package com.d365fo.mobileops.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

// ---------------------------------------------------------------------------------------
// Inventory journals — movement, adjustment, counting, arrival
// ---------------------------------------------------------------------------------------

/**
 * Maps to InventJournalType. Arrival is kept in this family because D365FO models the
 * arrival journal as an InventJournalTable variant, unlike a purchase product receipt.
 */
enum class JournalType {
    MOVEMENT,
    ADJUSTMENT,
    COUNTING,
    TRANSFER,
    ARRIVAL,
}

enum class JournalStatus { DRAFT, PENDING_POST, POSTED, FAILED }

@Entity(
    tableName = "invent_journal",
    indices = [
        Index("type"),
        Index("status"),
        Index("sync_state"),
        Index("warehouseId"),
        Index("local_modified_at"),
    ],
)
data class JournalEntity(
    @PrimaryKey val journalId: String,
    val type: JournalType,
    /** InventJournalName.JournalNameId — the journal name configured in D365FO. */
    val journalNameId: String,
    /** Server-assigned InventJournalTable.JournalId, populated after posting. */
    val serverJournalNumber: String?,
    val description: String?,
    val journalDate: Long,
    val siteId: String?,
    val warehouseId: String?,
    val workerId: String,
    val deviceId: String,
    val status: JournalStatus,
    /** Counting only: hide expected on-hand until the count is submitted. */
    val blindCount: Boolean = false,
    /** Counting only: variance percentage above which a supervisor PIN is required. */
    val varianceThresholdPercent: String? = null,
    val postedAt: Long? = null,
    /** Counting only: when the worker pressed Start counting. Null until then. */
    val countStartedAt: Long? = null,
    @Embedded val sync: SyncMeta = SyncMeta(),
)

@Entity(
    tableName = "invent_journal_line",
    foreignKeys = [
        ForeignKey(
            entity = JournalEntity::class,
            parentColumns = ["journalId"],
            childColumns = ["journalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("journalId"),
        Index("itemId"),
        Index(value = ["journalId", "lineNumber"], unique = true),
        Index("locationId"),
    ],
)
data class JournalLineEntity(
    @PrimaryKey val journalLineId: String,
    val journalId: String,
    val lineNumber: Int,
    val itemId: String,
    val itemName: String,
    val quantity: String,
    val unitId: String,
    /** Counting journals carry both the counted quantity and the expected on-hand. */
    val countedQuantity: String? = null,
    val expectedQuantity: String? = null,
    val siteId: String?,
    val warehouseId: String?,
    val locationId: String?,
    val batchId: String?,
    val serialId: String?,
    val inventDimId: String?,
    /** Mandatory for adjustment journals (InventJournalTrans.InventTransIdReturn reason). */
    val reasonCode: String? = null,
    /** Movement journals post to a ledger offset account. */
    val offsetAccount: String? = null,
    /** Transfer journals need a destination dimension set. */
    val toSiteId: String? = null,
    val toWarehouseId: String? = null,
    val toLocationId: String? = null,
    val varianceOverridden: Boolean = false,
    /** Counting only: the worker marked this line counted without entering a quantity (e.g. the actual count is not available). A marker for progress tracking - nothing is written back to D365FO for it. */
    val markedCounted: Boolean = false,
    val capturedAt: Long,
)

data class JournalWithLines(
    @Embedded val journal: JournalEntity,
    @Relation(parentColumn = "journalId", entityColumn = "journalId")
    val lines: List<JournalLineEntity>,
)

// ---------------------------------------------------------------------------------------
// Transfer orders
// ---------------------------------------------------------------------------------------

/** InventTransferTable.TransferStatus. */
enum class TransferStatus {
    CREATED,
    SHIPPED,
    RECEIVED,
    CANCELED;

    val shippable: Boolean get() = this == CREATED
    val receivable: Boolean get() = this == SHIPPED
}

@Entity(
    tableName = "transfer_order",
    indices = [
        Index("status"),
        Index("fromWarehouseId"),
        Index("toWarehouseId"),
        Index("sync_state"),
        Index("modifiedAt"),
    ],
)
data class TransferOrderEntity(
    @PrimaryKey val transferId: String,
    val fromSiteId: String?,
    val fromWarehouseId: String,
    val toSiteId: String?,
    val toWarehouseId: String,
    val status: TransferStatus,
    val shipDate: Long?,
    val receiveDate: Long?,
    val modifiedAt: Long,
    @Embedded val sync: SyncMeta = SyncMeta(),
)

@Entity(
    tableName = "transfer_line",
    foreignKeys = [
        ForeignKey(
            entity = TransferOrderEntity::class,
            parentColumns = ["transferId"],
            childColumns = ["transferId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("transferId"),
        Index("itemId"),
        Index(value = ["transferId", "lineNumber"], unique = true),
    ],
)
data class TransferLineEntity(
    @PrimaryKey val transferLineId: String,
    val transferId: String,
    val lineNumber: Double,
    val itemId: String,
    val itemName: String,
    val unitId: String,
    val quantity: String,
    val shippedQuantity: String,
    val receivedQuantity: String,
    /** Captured on the device, not yet accepted by D365FO. */
    val pendingShipQuantity: String? = null,
    val pendingReceiveQuantity: String? = null,
    val batchId: String? = null,
    val serialId: String? = null,
    val fromLocationId: String? = null,
    val toLocationId: String? = null,
    val serverRecId: Long?,
    val modifiedAt: Long,
    @Embedded val sync: SyncMeta = SyncMeta(),
)

data class TransferOrderWithLines(
    @Embedded val order: TransferOrderEntity,
    @Relation(parentColumn = "transferId", entityColumn = "transferId")
    val lines: List<TransferLineEntity>,
)

// ---------------------------------------------------------------------------------------
// Sales orders
// ---------------------------------------------------------------------------------------

/** SalesTable.SalesStatus — only Backorder (confirmed, undelivered) is pickable. */
enum class SalesOrderStatus {
    BACKORDER,
    DELIVERED,
    INVOICED,
    CANCELED;

    val isPickable: Boolean get() = this == BACKORDER
}

@Entity(
    tableName = "sales_order",
    indices = [
        Index("customerAccount"),
        Index("status"),
        Index("warehouseId"),
        Index("modifiedAt"),
    ],
)
data class SalesOrderEntity(
    @PrimaryKey val salesId: String,
    val customerAccount: String,
    val customerName: String,
    val status: SalesOrderStatus,
    val confirmed: Boolean,
    val siteId: String?,
    val warehouseId: String?,
    val deliveryDate: Long?,
    val modifiedAt: Long,
    @Embedded val sync: SyncMeta = SyncMeta(),
)

@Entity(
    tableName = "sales_line",
    foreignKeys = [
        ForeignKey(
            entity = SalesOrderEntity::class,
            parentColumns = ["salesId"],
            childColumns = ["salesId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("salesId"),
        Index("itemId"),
        Index(value = ["salesId", "lineNumber"], unique = true),
    ],
)
data class SalesLineEntity(
    @PrimaryKey val salesLineId: String,
    val salesId: String,
    val lineNumber: Double,
    val itemId: String,
    val itemName: String,
    val salesUnit: String,
    val inventoryUnit: String,
    val unitConversionFactor: String,
    val orderedQuantity: String,
    /** SalesLine.RemainSalesPhysical — what is still to be delivered. */
    val remainingQuantity: String,
    val deliveredQuantity: String,
    val pickedQuantity: String? = null,
    val siteId: String?,
    val warehouseId: String?,
    val locationId: String?,
    val batchId: String? = null,
    val serialId: String? = null,
    val serverRecId: Long?,
    val modifiedAt: Long,
    @Embedded val sync: SyncMeta = SyncMeta(),
)

data class SalesOrderWithLines(
    @Embedded val order: SalesOrderEntity,
    @Relation(parentColumn = "salesId", entityColumn = "salesId")
    val lines: List<SalesLineEntity>,
)
