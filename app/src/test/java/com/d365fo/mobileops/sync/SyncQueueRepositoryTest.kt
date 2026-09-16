package com.d365fo.mobileops.sync

import com.d365fo.mobileops.data.local.dao.SyncQueueDao
import com.d365fo.mobileops.data.local.entity.FailedSyncEntity
import com.d365fo.mobileops.data.local.entity.SyncOperation
import com.d365fo.mobileops.data.local.entity.SyncQueueEntity
import com.d365fo.mobileops.data.sync.SyncQueueRepository
import com.d365fo.mobileops.data.sync.payload.ProductReceiptPayload
import com.d365fo.mobileops.data.sync.payload.ReceiptLinePayload
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.Constants
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class SyncQueueRepositoryTest {

    private val dao = mockk<SyncQueueDao>(relaxed = true)
    private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }
    private val repository = SyncQueueRepository(dao, json)

    private val payload = ProductReceiptPayload(
        idempotencyKey = "receipt-1",
        dataAreaId = "USMF",
        receiptId = "receipt-1",
        purchaseOrderNumber = "PO-1",
        vendorPackingSlipId = "DN-500",
        receiptDate = "2026-08-26",
        personnelNumber = "000123",
        deviceId = "device-1",
        lines = listOf(
            ReceiptLinePayload(
                purchLineId = "PO-1:1",
                lineNumber = 1.0,
                itemNumber = "ITEM-001",
                receiveNowQuantity = 10.0,
                unitSymbol = "ea",
            ),
        ),
    )

    private fun entry(retryCount: Int = 0) = SyncQueueEntity(
        queueId = "q1",
        operation = SyncOperation.POST_PRODUCT_RECEIPT,
        aggregateId = "receipt-1",
        payloadJson = json.encodeToString(
            com.d365fo.mobileops.data.sync.payload.SyncPayload.serializer(),
            payload,
        ),
        workerId = "worker-1",
        deviceId = "device-1",
        createdAt = 1_000L,
        retryCount = retryCount,
        dedupeKey = "receipt-1",
    )

    @Test
    fun `builds an entry whose dedupe key is the payload idempotency key`() {
        // This is what makes a double-tapped Post safe: the unique index on dedupeKey turns
        // the second enqueue into a no-op instead of a second product receipt.
        val built = repository.buildEntry(
            operation = SyncOperation.POST_PRODUCT_RECEIPT,
            aggregateId = "receipt-1",
            payload = payload,
            workerId = "worker-1",
            deviceId = "device-1",
        )

        assertThat(built.dedupeKey).isEqualTo("receipt-1")
        assertThat(built.operation).isEqualTo(SyncOperation.POST_PRODUCT_RECEIPT)
    }

    @Test
    fun `round-trips a payload through serialization`() {
        val built = repository.buildEntry(
            SyncOperation.POST_PRODUCT_RECEIPT, "receipt-1", payload, "worker-1", "device-1",
        )

        val decoded = repository.decodePayload(built) as ProductReceiptPayload

        assertThat(decoded.purchaseOrderNumber).isEqualTo("PO-1")
        assertThat(decoded.lines).hasSize(1)
        assertThat(decoded.lines.first().receiveNowQuantity).isEqualTo(10.0)
    }

    @ParameterizedTest(name = "attempt {0} backs off {1} minutes")
    @CsvSource("1,1", "2,5", "3,15", "4,60", "5,60", "8,60")
    fun `climbs the backoff ladder and then holds`(attempt: Int, expectedMinutes: Long) {
        val backoff = repository.backoffMillis(attempt, AppFailure.Transient("timeout"))

        assertThat(backoff).isEqualTo(expectedMinutes * 60_000L)
    }

    @Test
    fun `honours a server Retry-After over the local ladder`() {
        // Being told when to come back is better information than any local guess.
        val backoff = repository.backoffMillis(
            attempt = 1,
            failure = AppFailure.Transient("throttled", httpCode = 429, retryAfterSeconds = 90),
        )

        assertThat(backoff).isEqualTo(90_000L)
    }

    @Test
    fun `reschedules a retryable failure`() = runTest {
        val dropped = repository.onFailure(
            entry(retryCount = 0),
            AppFailure.Transient("timeout"),
            now = 10_000L,
        )

        assertThat(dropped).isFalse()
        coVerify {
            dao.scheduleRetry(
                queueId = "q1",
                retryCount = 1,
                nextAttemptAt = 10_000L + 60_000L,
                error = "timeout",
            )
        }
    }

    @Test
    fun `dead-letters a business rule failure without retrying`() = runTest {
        // A closed accounting period does not fix itself in five minutes. Burning eight
        // attempts on it only delays telling a supervisor.
        val captured = slot<FailedSyncEntity>()
        coEvery { dao.deadLetter(any(), capture(captured)) } returns Unit

        val dropped = repository.onFailure(
            entry(),
            AppFailure.BusinessRule(
                "The accounting period is closed",
                d365ErrorCode = "PeriodClosed",
            ),
            now = 10_000L,
        )

        assertThat(dropped).isTrue()
        assertThat(captured.captured.errorCode).isEqualTo("PeriodClosed")
        assertThat(captured.captured.attempts).isEqualTo(1)
        coVerify(exactly = 0) { dao.scheduleRetry(any(), any(), any(), any()) }
    }

    @Test
    fun `dead-letters a retryable failure once attempts are exhausted`() = runTest {
        val dropped = repository.onFailure(
            entry(retryCount = Constants.MAX_RETRY_COUNT - 1),
            AppFailure.Transient("timeout"),
            now = 10_000L,
        )

        assertThat(dropped).isTrue()
        coVerify { dao.deadLetter(eq("q1"), any()) }
    }

    @Test
    fun `resubmit mints a fresh idempotency key`() = runTest {
        // The original submission was refused on business grounds, so the corrected one is
        // new work — replaying the old key would just replay the refusal.
        coEvery { dao.getFailure("f1") } returns FailedSyncEntity(
            failureId = "f1",
            operation = SyncOperation.POST_PRODUCT_RECEIPT,
            aggregateId = "receipt-1",
            payloadJson = entry().payloadJson,
            workerId = "worker-1",
            deviceId = "device-1",
            originallyQueuedAt = 1_000L,
            failedAt = 2_000L,
            attempts = 3,
            errorCode = "PeriodClosed",
            errorMessage = "closed",
            serverDetail = null,
        )
        val captured = slot<SyncQueueEntity>()
        coEvery { dao.resubmit(eq("f1"), capture(captured)) } returns Unit

        val resubmitted = repository.resubmit("f1")

        assertThat(resubmitted).isTrue()
        assertThat(captured.captured.dedupeKey).isNotEqualTo("receipt-1")
        assertThat(captured.captured.dedupeKey).startsWith("receipt-1:resubmit:")
        assertThat(captured.captured.retryCount).isEqualTo(0)
    }

    @Test
    fun `resubmit is a no-op when the failure has already been cleared`() = runTest {
        coEvery { dao.getFailure("gone") } returns null

        assertThat(repository.resubmit("gone")).isFalse()
    }
}
