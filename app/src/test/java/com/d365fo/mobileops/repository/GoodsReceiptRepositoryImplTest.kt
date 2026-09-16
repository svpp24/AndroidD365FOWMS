package com.d365fo.mobileops.repository

import androidx.room.withTransaction
import app.cash.turbine.test
import com.d365fo.mobileops.data.local.D365Database
import com.d365fo.mobileops.data.local.dao.GoodsReceiptDao
import com.d365fo.mobileops.data.local.entity.GoodsReceiptEntity
import com.d365fo.mobileops.data.local.entity.GoodsReceiptLineEntity
import com.d365fo.mobileops.data.local.entity.GoodsReceiptWithLines
import com.d365fo.mobileops.data.local.entity.ReceiptStatus
import com.d365fo.mobileops.data.local.entity.SyncOperation
import com.d365fo.mobileops.data.local.entity.SyncQueueEntity
import com.d365fo.mobileops.data.local.entity.SyncState
import com.d365fo.mobileops.data.repository.GoodsReceiptRepositoryImpl
import com.d365fo.mobileops.data.sync.SyncQueueRepository
import com.d365fo.mobileops.data.sync.SyncScheduler
import com.d365fo.mobileops.data.sync.payload.SyncPayload
import com.d365fo.mobileops.domain.model.WorkerContext
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GoodsReceiptRepositoryImplTest {

    private val database = mockk<D365Database>(relaxed = true)
    private val receiptDao = mockk<GoodsReceiptDao>(relaxed = true)
    private val scheduler = mockk<SyncScheduler>(relaxed = true)
    private val syncQueueDao = mockk<com.d365fo.mobileops.data.local.dao.SyncQueueDao>(relaxed = true)
    private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }
    private val syncQueue = SyncQueueRepository(syncQueueDao, json)
    private val dispatcher = StandardTestDispatcher()

    private lateinit var repository: GoodsReceiptRepositoryImpl

    private val worker = WorkerContext(
        workerId = "worker-1",
        personnelNumber = "000123",
        displayName = "Test Worker",
        deviceId = "device-1",
        dataAreaId = "USMF",
        siteId = "S1",
        warehouseId = "WH1",
    )

    private fun receipt(
        status: ReceiptStatus = ReceiptStatus.DRAFT,
        lines: List<GoodsReceiptLineEntity> = listOf(line()),
        packingSlip: String = "DN-500",
    ) = GoodsReceiptWithLines(
        receipt = GoodsReceiptEntity(
            receiptId = "receipt-1",
            purchId = "PO-1",
            workerId = "worker-1",
            deviceId = "device-1",
            dataAreaId = "USMF",
            personnelNumber = "000123",
            vendorPackingSlipId = packingSlip,
            receiptDate = 1_756_000_000_000L,
            status = status,
            serverPackingSlipId = null,
            postedAt = null,
        ),
        lines = lines,
    )

    private fun line() = GoodsReceiptLineEntity(
        receiptLineId = "rl-1",
        receiptId = "receipt-1",
        purchLineId = "PO-1:1",
        purchId = "PO-1",
        lineNumber = 1.0,
        itemId = "ITEM-001",
        itemName = "Test item",
        enteredQuantity = "10",
        enteredUnit = "ea",
        inventoryQuantity = "10",
        inventoryUnit = "ea",
        siteId = "S1",
        warehouseId = "WH1",
        locationId = "LOC-01",
        batchId = null,
        serialId = null,
        toleranceOverridden = false,
        overrideReason = null,
        capturedAt = 1_756_000_000_000L,
    )

    @BeforeEach
    fun setUp() {
        repository = GoodsReceiptRepositoryImpl(
            database = database,
            receiptDao = receiptDao,
            syncQueue = syncQueue,
            scheduler = scheduler,
            ioDispatcher = dispatcher,
        )

        // `withTransaction` is a top-level suspend extension in androidx.room.RoomDatabaseKt.
        // Stubbing the file class and running the captured block inline keeps this a plain
        // unit test while still exercising the real "status update and enqueue happen
        // together" path.
        mockkStatic("androidx.room.RoomDatabaseKt")
        val block = slot<suspend () -> Any?>()
        coEvery { database.withTransaction(capture(block)) } coAnswers { block.captured() }
    }

    @Test
    fun `resumes an existing draft instead of starting a second receipt`() = runTest(dispatcher) {
        // A handheld that reboots mid-pallet must reopen the same receipt, otherwise the two
        // sessions would each post their own product receipt against the same order.
        coEvery { receiptDao.findDraftFor("PO-1", "worker-1") } returns receipt()

        val opened = repository.openDraft("PO-1", worker)

        assertThat(opened.receiptId).isEqualTo("receipt-1")
        coVerify(exactly = 0) { receiptDao.upsertReceipt(any()) }
    }

    @Test
    fun `creates a draft carrying the worker's legal entity and personnel number`() =
        runTest(dispatcher) {
            coEvery { receiptDao.findDraftFor("PO-1", "worker-1") } returns null
            val captured = slot<GoodsReceiptEntity>()
            coEvery { receiptDao.upsertReceipt(capture(captured)) } just Runs

            val opened = repository.openDraft("PO-1", worker)

            assertThat(opened.status.name).isEqualTo("DRAFT")
            assertThat(captured.captured.dataAreaId).isEqualTo("USMF")
            assertThat(captured.captured.personnelNumber).isEqualTo("000123")
        }

    @Test
    fun `refuses to add a line to a receipt that is already submitted`() = runTest(dispatcher) {
        coEvery { receiptDao.getReceipt("receipt-1") } returns
            receipt(status = ReceiptStatus.PENDING_POST)

        val result = repository.addLine("receipt-1", mockk(relaxed = true))

        assertThat(result).isInstanceOf(NetworkResult.Error::class.java)
        assertThat(((result as NetworkResult.Error).failure))
            .isInstanceOf(AppFailure.Validation::class.java)
    }

    @Test
    fun `refuses to submit a receipt with no lines`() = runTest(dispatcher) {
        coEvery { receiptDao.getReceipt("receipt-1") } returns receipt(lines = emptyList())

        val result = repository.submitForPosting("receipt-1")

        assertThat(result).isInstanceOf(NetworkResult.Error::class.java)
        coVerify(exactly = 0) { syncQueueDao.enqueue(any()) }
    }

    @Test
    fun `submitting marks the receipt pending and enqueues the posting`() = runTest(dispatcher) {
        coEvery { receiptDao.getReceipt("receipt-1") } returns receipt()
        val queued = slot<SyncQueueEntity>()
        coEvery { syncQueueDao.enqueue(capture(queued)) } returns 1L

        val result = repository.submitForPosting("receipt-1")

        assertThat(result).isInstanceOf(NetworkResult.Success::class.java)
        coVerify {
            receiptDao.updateStatus(
                receiptId = "receipt-1",
                status = ReceiptStatus.PENDING_POST,
                syncState = SyncState.PENDING,
                error = null,
            )
        }
        assertThat(queued.captured.operation).isEqualTo(SyncOperation.POST_PRODUCT_RECEIPT)
        assertThat(queued.captured.aggregateId).isEqualTo("receipt-1")
        // The idempotency key is the receipt id, so a retry after an ambiguous timeout is
        // recognised by the service rather than creating a second product receipt.
        assertThat(queued.captured.dedupeKey).isEqualTo("receipt-1")
    }

    @Test
    fun `submitting does not wait on the network`() = runTest(dispatcher) {
        coEvery { receiptDao.getReceipt("receipt-1") } returns receipt()

        repository.submitForPosting("receipt-1")

        // The scheduler only asks WorkManager to run; it does not post anything itself.
        // Offline, the request simply waits on its connectivity constraint.
        coVerify { scheduler.requestImmediatePush() }
    }

    @Test
    fun `the queued payload carries every captured line`() = runTest(dispatcher) {
        coEvery { receiptDao.getReceipt("receipt-1") } returns receipt()
        val queued = slot<SyncQueueEntity>()
        coEvery { syncQueueDao.enqueue(capture(queued)) } returns 1L

        repository.submitForPosting("receipt-1")

        val payload = json.decodeFromString(
            SyncPayload.serializer(),
            queued.captured.payloadJson,
        ) as com.d365fo.mobileops.data.sync.payload.ProductReceiptPayload

        assertThat(payload.purchaseOrderNumber).isEqualTo("PO-1")
        assertThat(payload.vendorPackingSlipId).isEqualTo("DN-500")
        assertThat(payload.dataAreaId).isEqualTo("USMF")
        assertThat(payload.lines).hasSize(1)
        assertThat(payload.lines.first().itemNumber).isEqualTo("ITEM-001")
        assertThat(payload.lines.first().receiveNowQuantity).isEqualTo(10.0)
    }

    @Test
    fun `discarding leaves a queued receipt untouched`() = runTest(dispatcher) {
        // Once queued, the receipt belongs to the sync engine. Deleting it would lose a
        // transaction the worker has been told is saved.
        coEvery { receiptDao.getReceipt("receipt-1") } returns
            receipt(status = ReceiptStatus.PENDING_POST)

        repository.discardDraft("receipt-1")

        coVerify(exactly = 0) { receiptDao.deleteReceipt(any()) }
    }

    @Test
    fun `observes the receipt as a flow from Room`() = runTest(dispatcher) {
        every { receiptDao.observeReceipt("receipt-1") } returns flowOf(receipt())

        repository.observeReceipt("receipt-1").test {
            val emitted = awaitItem()
            assertThat(emitted?.receiptId).isEqualTo("receipt-1")
            assertThat(emitted?.lines).hasSize(1)
            awaitComplete()
        }
    }
}
