package com.d365fo.mobileops.data.sync

/**
 * One row of D365FO's own `HSMobileOpsPostQueue` — a request the device has handed to the AOS,
 * distinct from [com.d365fo.mobileops.data.local.entity.SyncQueueEntity] which is this device's
 * own local outbound queue. A row here can outlive the local one by a lot: the device's queue
 * entry is cleared the moment D365FO accepts the request (`Success` or `AlreadyPosted`), but the
 * real registration or transfer posting this row represents may still be sitting at `Pending`,
 * waiting for someone to trigger [com.d365fo.mobileops.data.sync.PostingDispatcher.processPending].
 *
 * [status] is one of "Pending", "Registered" or "Failed" — the exact tokens
 * `HSMobileOpsPostQueue::statusPending`/`statusRegistered`/`statusFailed` return, passed through
 * rather than mapped to a local enum so a new status added on the AOS side shows up as a string
 * on screen instead of silently falling through a `when`.
 */
data class PostQueueEntry(
    val sourceId: String,
    val documentType: String,
    val status: String,
    val errorMessage: String?,
    val receivedUtc: String?,
    val processedUtc: String?,
    val userName: String?,
    /** Same shape this device sends when posting - a JSON array of [com.d365fo.mobileops.data.remote.dto.PostLineDto]. */
    val linesJson: String?,
    val idempotencyKey: String = "",
    /** Auto GRN: the product receipt posted with this registration. */
    val grnId: String? = null,
    val lineCount: Int = 0,
)
