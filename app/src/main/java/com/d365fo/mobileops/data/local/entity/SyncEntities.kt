package com.d365fo.mobileops.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * What a queued operation posts to D365FO. The dispatcher switches on this to pick the
 * OData action or custom AOS service.
 */
enum class SyncOperation {
    POST_PRODUCT_RECEIPT,
    POST_ARRIVAL_JOURNAL,
    TRANSFER_ORDER_SHIP,
    TRANSFER_ORDER_RECEIVE,
    POST_PACKING_SLIP,
    POST_INVENTORY_JOURNAL,
}

/**
 * The outbound transaction log. Every user-facing mutation lands here in the same Room
 * transaction that writes the business rows, so a crash can never leave a captured receipt
 * un-queued.
 *
 * Ordering is FIFO by [createdAt]. [aggregateId] lets the dispatcher collapse or supersede
 * duplicate submissions of the same document, and [dedupeKey] makes enqueue idempotent when
 * a worker double-taps Post.
 */
@Entity(
    tableName = "sync_queue",
    indices = [
        Index("createdAt"),
        Index("operation"),
        Index("aggregateId"),
        Index(value = ["dedupeKey"], unique = true),
        Index("nextAttemptAt"),
    ],
)
data class SyncQueueEntity(
    @PrimaryKey val queueId: String,
    val operation: SyncOperation,
    /** Business document this entry posts — a receiptId, journalId, transferId, salesId. */
    val aggregateId: String,
    /** Serialized SyncPayload. Self-contained: the worker who captured it may have signed out. */
    val payloadJson: String,
    val workerId: String,
    val deviceId: String,
    val createdAt: Long,
    val retryCount: Int = 0,
    /** Earliest epoch ms at which the push worker may try again (exponential backoff). */
    val nextAttemptAt: Long = 0,
    val lastError: String? = null,
    /** Set while a worker holds the row, so two workers cannot post the same document twice. */
    val leasedUntil: Long = 0,
    /**
     * Idempotency key echoed to D365FO. A retry after an ambiguous timeout must not create a
     * second product receipt.
     */
    val dedupeKey: String,
)

/**
 * Dead-letter queue. An operation lands here when D365FO rejects it on business grounds, or
 * when retries are exhausted. Supervisors review, correct and resubmit from the app.
 */
@Entity(
    tableName = "failed_sync",
    indices = [Index("failedAt"), Index("operation"), Index("acknowledged")],
)
data class FailedSyncEntity(
    @PrimaryKey val failureId: String,
    val operation: SyncOperation,
    val aggregateId: String,
    val payloadJson: String,
    val workerId: String,
    val deviceId: String,
    val originallyQueuedAt: Long,
    val failedAt: Long,
    val attempts: Int,
    /** D365FO error code where one was returned, for support triage. */
    val errorCode: String?,
    val errorMessage: String,
    /** Raw server response body, truncated. Never shown to the worker verbatim. */
    val serverDetail: String?,
    /** A supervisor has seen it; clears the in-app badge without resolving it. */
    val acknowledged: Boolean = false,
    val resubmittedAt: Long? = null,
)
