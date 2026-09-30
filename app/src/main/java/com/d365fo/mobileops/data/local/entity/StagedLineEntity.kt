package com.d365fo.mobileops.data.local.entity

import androidx.room.Entity

/**
 * One line already handed to D365FO for registration, kept until that registration is confirmed
 * actually done - not merely accepted.
 *
 * Closes a real gap: [com.d365fo.mobileops.domain.model.PurchaseOrderLine.uncommittedQuantity]
 * claims to net out exactly this, but the AOS side (`HSMobileOpsReceivingService`) never computes
 * it - confirmed by reading that source, no reference to the post queue exists there at all. So
 * landing a receipt in `HSMobileOpsPostQueue` as Pending does not reduce what a fresh
 * `getPurchaseOrder` reports as outstanding, and AOS deliberately runs no automatic batch job to
 * turn Pending into Registered (see aos/HSMobileOps/AxTable/HSMobileOpsPostQueue.xml) - so without
 * this table, the same line stays scannable, and stageable again, for however long it sits
 * Pending. A row here closes that window without a network round trip: written the moment a line
 * is staged, read once per order lookup and netted against the server's own count from then on,
 * and cleared once the Activity screen's own queue-status poll confirms the row it came from has
 * actually left Pending (see FailedSyncViewModel).
 */
@Entity(tableName = "staged_line", primaryKeys = ["flowType", "orderId", "lineNumber", "queueId"])
data class StagedLineEntity(
    val flowType: FlowType,
    /** Purchase order number or transfer order number - whichever [flowType] means. */
    val orderId: String,
    val lineNumber: Double,
    /** Plain-string quantity, same convention as [com.d365fo.mobileops.data.repository.StoredLine.quantity]. */
    val quantity: String,
    /**
     * The sync-queue entry this line was staged under - see [com.d365fo.mobileops.data.local.entity.SyncQueueEntity.queueId].
     * That row is gone from the local queue by the time this one is written (removed the moment
     * AOS accepts it - see SyncQueueRepository.onSuccess), so this is the only surviving link
     * back to which submission this line belongs to.
     */
    val queueId: String,
    val stagedAtEpochMs: Long,
)
