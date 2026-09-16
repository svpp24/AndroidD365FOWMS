package com.d365fo.mobileops.data.local.converter

import androidx.room.TypeConverter
import com.d365fo.mobileops.data.local.entity.JournalStatus
import com.d365fo.mobileops.data.local.entity.JournalType
import com.d365fo.mobileops.data.local.entity.PurchaseOrderStatus
import com.d365fo.mobileops.data.local.entity.ReceiptStatus
import com.d365fo.mobileops.data.local.entity.SalesOrderStatus
import com.d365fo.mobileops.data.local.entity.SyncOperation
import com.d365fo.mobileops.data.local.entity.SyncState
import com.d365fo.mobileops.data.local.entity.FlowType
import com.d365fo.mobileops.data.local.entity.TrackingRequirement
import com.d365fo.mobileops.data.local.entity.TransferStatus

/**
 * Enums are stored by name, never by ordinal: reordering an enum must not silently
 * reinterpret existing rows on a device that has not been wiped.
 *
 * Quantities are stored as plain-string BigDecimal in the entities themselves. SQLite REAL
 * is binary floating point and would round 0.1 kg-style quantities; a warehouse count that
 * drifts by 1e-9 fails reconciliation against D365FO.
 */
class Converters {

    @TypeConverter fun syncStateToString(v: SyncState): String = v.name
    @TypeConverter fun stringToSyncState(v: String): SyncState = SyncState.valueOf(v)

    @TypeConverter fun syncOperationToString(v: SyncOperation): String = v.name
    @TypeConverter fun stringToSyncOperation(v: String): SyncOperation = SyncOperation.valueOf(v)

    @TypeConverter fun purchStatusToString(v: PurchaseOrderStatus): String = v.name
    @TypeConverter fun stringToPurchStatus(v: String): PurchaseOrderStatus =
        PurchaseOrderStatus.valueOf(v)

    @TypeConverter fun receiptStatusToString(v: ReceiptStatus): String = v.name
    @TypeConverter fun stringToReceiptStatus(v: String): ReceiptStatus = ReceiptStatus.valueOf(v)

    @TypeConverter fun flowTypeToString(v: FlowType): String = v.name
    @TypeConverter fun stringToFlowType(v: String): FlowType = FlowType.valueOf(v)

    @TypeConverter fun trackingToString(v: TrackingRequirement): String = v.name
    @TypeConverter fun stringToTracking(v: String): TrackingRequirement =
        TrackingRequirement.valueOf(v)

    @TypeConverter fun journalTypeToString(v: JournalType): String = v.name
    @TypeConverter fun stringToJournalType(v: String): JournalType = JournalType.valueOf(v)

    @TypeConverter fun journalStatusToString(v: JournalStatus): String = v.name
    @TypeConverter fun stringToJournalStatus(v: String): JournalStatus = JournalStatus.valueOf(v)

    @TypeConverter fun transferStatusToString(v: TransferStatus): String = v.name
    @TypeConverter fun stringToTransferStatus(v: String): TransferStatus =
        TransferStatus.valueOf(v)

    @TypeConverter fun salesStatusToString(v: SalesOrderStatus): String = v.name
    @TypeConverter fun stringToSalesStatus(v: String): SalesOrderStatus =
        SalesOrderStatus.valueOf(v)
}
