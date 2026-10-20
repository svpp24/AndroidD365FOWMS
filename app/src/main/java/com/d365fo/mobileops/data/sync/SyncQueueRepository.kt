package com.d365fo.mobileops.data.sync

import com.d365fo.mobileops.data.local.dao.SyncQueueDao
import com.d365fo.mobileops.data.local.entity.FailedSyncEntity
import com.d365fo.mobileops.data.local.entity.SyncOperation
import com.d365fo.mobileops.data.local.entity.SyncQueueEntity
import com.d365fo.mobileops.data.sync.payload.SyncPayload
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class SyncQueueRepository @Inject constructor(
    private val dao: SyncQueueDao,
    private val json: Json,
) {

    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    fun observeUnacknowledgedFailureCount(): Flow<Int> = dao.observeUnacknowledgedFailureCount()

    fun observeFailures(): Flow<List<FailedSyncEntity>> = dao.observeFailures()

    fun observeWaiting(): Flow<List<com.d365fo.mobileops.data.local.entity.SyncQueueEntity>> = dao.observeWaiting()

    fun observeOldestQueuedAt(): Flow<Long?> = dao.observeOldestQueuedAt()

    /**
     * Builds a queue entry. Not inserted here — the caller inserts it inside the same Room
     * transaction that writes the business rows, so a queued posting can never exist without
     * its document, or a document without its posting.
     */
    fun buildEntry(
        operation: SyncOperation,
        aggregateId: String,
        payload: SyncPayload,
        workerId: String,
        deviceId: String,
        now: Long = System.currentTimeMillis(),
    ): SyncQueueEntity = SyncQueueEntity(
        queueId = UUID.randomUUID().toString(),
        operation = operation,
        aggregateId = aggregateId,
        payloadJson = json.encodeToString(SyncPayload.serializer(), payload),
        workerId = workerId,
        deviceId = deviceId,
        createdAt = now,
        nextAttemptAt = now,
        dedupeKey = payload.idempotencyKey,
    )

    suspend fun enqueue(entry: SyncQueueEntity) {
        dao.enqueue(entry)
    }

    suspend fun leaseBatch(limit: Int = LEASE_BATCH_SIZE): List<SyncQueueEntity> =
        dao.leaseBatch(limit)

    /**
     * A worker's "Retry now": the order's entries skip the rest of their backoff wait. Same
     * entries, same idempotency keys - a retry of work D365FO may already have, never new work.
     */
    suspend fun retryNow(aggregateId: String) = dao.makeDue(aggregateId, System.currentTimeMillis())

    /**
     * The device (or D365FO) is reachable again: whatever was waiting out a backoff because it
     * could not get through is due now. Without this, an entry that failed while offline sat
     * out its 1-5-15-60 minute wait after the network came back, and nothing woke the queue
     * until WorkManager's 15-minute safety net - confirmed live.
     */
    suspend fun retryAllNow() = dao.makeAllDue(System.currentTimeMillis())

    /** See [SyncQueueDao.leaseOne]. Null means something else (a background worker run) already claimed it. */
    suspend fun leaseOne(queueId: String): SyncQueueEntity? = dao.leaseOne(queueId)

    suspend fun releaseLease(entries: List<SyncQueueEntity>) =
        dao.releaseLease(entries.map { it.queueId })

    fun decodePayload(entry: SyncQueueEntity): SyncPayload =
        json.decodeFromString(SyncPayload.serializer(), entry.payloadJson)

    suspend fun onSuccess(entry: SyncQueueEntity) = dao.remove(entry.queueId)

    /**
     * Applies the retry policy after a failed attempt.
     *
     * Retryable failures climb the backoff ladder — 1, 5, 15, then 60 minutes, holding at
     * 60 — until [Constants.MAX_RETRY_COUNT] attempts are spent. Non-retryable failures skip
     * the ladder entirely: a closed accounting period will not fix itself in five minutes,
     * and burning eight attempts on it only delays the moment a supervisor is told.
     *
     * @return true when the entry was dead-lettered rather than rescheduled.
     */
    suspend fun onFailure(
        entry: SyncQueueEntity,
        failure: AppFailure,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val attempts = entry.retryCount + 1
        // No answer from D365FO at all - no connection, DNS, a timeout - never uses up the
        // budget: an outage longer than the ladder must not turn captured work into a "failed"
        // posting someone has to resubmit. Only D365FO answering (5xx, 429) counts towards it.
        val noAnswer = failure is AppFailure.Offline || (failure is AppFailure.Transient && failure.httpCode == null)
        val exhausted = !noAnswer && attempts >= Constants.MAX_RETRY_COUNT

        if (!failure.retryable || exhausted) {
            dao.deadLetter(
                queueId = entry.queueId,
                failure = FailedSyncEntity(
                    failureId = UUID.randomUUID().toString(),
                    operation = entry.operation,
                    aggregateId = entry.aggregateId,
                    payloadJson = entry.payloadJson,
                    workerId = entry.workerId,
                    deviceId = entry.deviceId,
                    originallyQueuedAt = entry.createdAt,
                    failedAt = now,
                    attempts = attempts,
                    errorCode = (failure as? AppFailure.BusinessRule)?.d365ErrorCode,
                    errorMessage = failure.message,
                    serverDetail = (failure as? AppFailure.BusinessRule)?.d365Detail,
                ),
            )
            return true
        }

        dao.scheduleRetry(
            queueId = entry.queueId,
            retryCount = attempts,
            nextAttemptAt = now + backoffMillis(attempts, failure),
            error = failure.message,
        )
        return false
    }

    /**
     * Backoff ladder, in minutes: 1 → 5 → 15 → 60, then held at 60.
     *
     * A server-supplied `Retry-After` always wins — being told when to come back is better
     * information than any local guess.
     */
    fun backoffMillis(attempt: Int, failure: AppFailure): Long {
        (failure as? AppFailure.Transient)?.retryAfterSeconds?.let {
            return it * 1_000L
        }
        val ladder = Constants.BACKOFF_LADDER_MINUTES
        val index = min(attempt - 1, ladder.lastIndex).coerceAtLeast(0)
        return ladder[index] * 60_000L
    }

    // -- Dead letter -----------------------------------------------------------------------

    suspend fun acknowledgeFailure(failureId: String) = dao.acknowledgeFailure(failureId)

    /**
     * Supervisor resubmit. A fresh idempotency key is minted: the original attempt was
     * rejected on business grounds, so the service must treat the corrected submission as
     * new work rather than replaying the refusal.
     */
    suspend fun resubmit(failureId: String): Boolean {
        val failure = dao.getFailure(failureId) ?: return false
        val now = System.currentTimeMillis()
        val entry = SyncQueueEntity(
            queueId = UUID.randomUUID().toString(),
            operation = failure.operation,
            aggregateId = failure.aggregateId,
            payloadJson = failure.payloadJson,
            workerId = failure.workerId,
            deviceId = failure.deviceId,
            createdAt = now,
            nextAttemptAt = now,
            dedupeKey = "${failure.aggregateId}:resubmit:$now",
        )
        dao.resubmit(failureId, entry)
        return true
    }

    private companion object {
        const val LEASE_BATCH_SIZE = 10
    }
}
