package com.d365fo.mobileops.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Ignore

/**
 * Lifecycle of a locally captured transaction:
 *
 *   PENDING  -> queued, waiting for the push worker
 *   SYNCED   -> D365FO accepted it; serverRecId / server document number are populated
 *   CONFLICT -> the server record moved under us (ETag mismatch, order already received)
 *   ERROR    -> D365FO rejected it, or retries were exhausted; sits in the dead-letter queue
 */
enum class SyncState { SYNCED, PENDING, CONFLICT, ERROR }

/**
 * Embedded into every transactional entity. Kept as a value object rather than copied
 * columns so that the sync engine can treat all transaction types uniformly.
 */
data class SyncMeta(
    @ColumnInfo(name = "sync_state", defaultValue = "SYNCED")
    val syncState: SyncState = SyncState.SYNCED,

    /** Epoch ms of the last local mutation. Drives sync-queue ordering (FIFO per worker). */
    @ColumnInfo(name = "local_modified_at")
    val localModifiedAt: Long = System.currentTimeMillis(),

    /** D365FO RecId once the record exists server-side. */
    @ColumnInfo(name = "server_rec_id")
    val serverRecId: Long? = null,

    /** OData ETag for optimistic concurrency on update/PATCH. */
    @ColumnInfo(name = "etag")
    val eTag: String? = null,

    @ColumnInfo(name = "retry_count", defaultValue = "0")
    val retryCount: Int = 0,

    /** Last failure text, shown in the supervisor review queue. */
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
) {
    @get:Ignore
    val isDirty: Boolean get() = syncState != SyncState.SYNCED

    fun markPending(now: Long = System.currentTimeMillis()) =
        copy(syncState = SyncState.PENDING, localModifiedAt = now, lastError = null)

    fun markSynced(recId: Long?, etag: String?) = copy(
        syncState = SyncState.SYNCED,
        serverRecId = recId ?: serverRecId,
        eTag = etag ?: eTag,
        retryCount = 0,
        lastError = null,
    )

    fun markError(reason: String) =
        copy(syncState = SyncState.ERROR, retryCount = retryCount + 1, lastError = reason)

    fun markConflict(reason: String) =
        copy(syncState = SyncState.CONFLICT, lastError = reason)
}
