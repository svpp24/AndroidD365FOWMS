package com.d365fo.mobileops.data.repository

import androidx.room.withTransaction
import com.d365fo.mobileops.data.local.D365Database
import com.d365fo.mobileops.data.local.dao.GoodsReceiptDao
import com.d365fo.mobileops.data.local.entity.GoodsReceiptEntity
import com.d365fo.mobileops.data.local.entity.GoodsReceiptWithLines
import com.d365fo.mobileops.data.local.entity.ReceiptStatus
import com.d365fo.mobileops.data.local.entity.SyncOperation
import com.d365fo.mobileops.data.local.entity.SyncState
import com.d365fo.mobileops.data.sync.SyncQueueRepository
import com.d365fo.mobileops.data.sync.SyncScheduler
import com.d365fo.mobileops.data.sync.payload.ProductReceiptPayload
import com.d365fo.mobileops.data.sync.payload.ReceiptLinePayload
import com.d365fo.mobileops.domain.model.GoodsReceipt
import com.d365fo.mobileops.domain.model.GoodsReceiptLine
import com.d365fo.mobileops.domain.model.WorkerContext
import com.d365fo.mobileops.domain.repository.GoodsReceiptRepository
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.Formatters
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoodsReceiptRepositoryImpl @Inject constructor(
    private val database: D365Database,
    private val receiptDao: GoodsReceiptDao,
    private val syncQueue: SyncQueueRepository,
    private val scheduler: SyncScheduler,
    private val ioDispatcher: CoroutineDispatcher,
) : GoodsReceiptRepository {

    /**
     * Resumes the worker's existing draft for this order, or creates one.
     *
     * Resuming rather than always creating is what makes an interrupted session survivable:
     * a handheld that reboots mid-pallet reopens the same receipt with its captured lines
     * intact, instead of starting a second one that would double-receive on posting.
     */
    override suspend fun openDraft(purchId: String, worker: WorkerContext): GoodsReceipt =
        withContext(ioDispatcher) {
            receiptDao.findDraftFor(purchId, worker.workerId)?.let { return@withContext it.toDomain() }

            val receipt = GoodsReceiptEntity(
                receiptId = UUID.randomUUID().toString(),
                purchId = purchId,
                workerId = worker.workerId,
                deviceId = worker.deviceId,
                dataAreaId = worker.dataAreaId,
                personnelNumber = worker.personnelNumber,
                vendorPackingSlipId = "",
                receiptDate = System.currentTimeMillis(),
                status = ReceiptStatus.DRAFT,
                serverPackingSlipId = null,
                postedAt = null,
            )
            receiptDao.upsertReceipt(receipt)
            GoodsReceiptWithLines(receipt, emptyList()).toDomain()
        }

    override fun observeReceipt(receiptId: String): Flow<GoodsReceipt?> =
        receiptDao.observeReceipt(receiptId)
            .map { it?.toDomain() }
            .flowOn(ioDispatcher)

    override suspend fun addLine(
        receiptId: String,
        line: GoodsReceiptLine,
    ): NetworkResult<Unit> = withContext(ioDispatcher) {
        val stored = receiptDao.getReceipt(receiptId)
            ?: return@withContext NetworkResult.Error(
                AppFailure.Fatal("receipt $receiptId not found"),
            )

        if (stored.receipt.status != ReceiptStatus.DRAFT) {
            return@withContext NetworkResult.Error(
                AppFailure.Validation("This receipt has already been submitted"),
            )
        }

        receiptDao.addLine(
            line = line.toEntity(receiptId, stored.receipt.purchId),
            header = stored.receipt,
        )
        NetworkResult.Success(Unit)
    }

    override suspend fun removeLine(
        receiptId: String,
        receiptLineId: String,
    ): NetworkResult<Unit> = withContext(ioDispatcher) {
        val stored = receiptDao.getReceipt(receiptId)
            ?: return@withContext NetworkResult.Error(
                AppFailure.Fatal("receipt $receiptId not found"),
            )
        if (stored.receipt.status != ReceiptStatus.DRAFT) {
            return@withContext NetworkResult.Error(
                AppFailure.Validation("This receipt has already been submitted"),
            )
        }
        val target = stored.lines.firstOrNull { it.receiptLineId == receiptLineId }
            ?: return@withContext NetworkResult.Success(Unit)

        receiptDao.deleteLine(target)
        NetworkResult.Success(Unit)
    }

    override suspend fun updatePackingSlipId(receiptId: String, vendorPackingSlipId: String) {
        withContext(ioDispatcher) {
            val stored = receiptDao.getReceipt(receiptId) ?: return@withContext
            receiptDao.updateReceipt(
                stored.receipt.copy(
                    vendorPackingSlipId = vendorPackingSlipId.trim(),
                    sync = stored.receipt.sync.copy(localModifiedAt = System.currentTimeMillis()),
                ),
            )
        }
    }

    /**
     * Commits the receipt and enqueues its posting.
     *
     * Both writes happen inside one Room transaction. That is the whole guarantee this app
     * rests on: there is no interleaving in which a receipt is marked submitted but has no
     * queue entry to post it, or in which a queue entry exists for a receipt still shown as
     * an editable draft.
     *
     * The network is not touched here. [SyncScheduler.requestImmediatePush] only asks
     * WorkManager to run; if the device is offline the request simply waits on its
     * connectivity constraint, and the worker moves on to the next pallet.
     */
    override suspend fun submitForPosting(receiptId: String): NetworkResult<Unit> =
        withContext(ioDispatcher) {
            val stored = receiptDao.getReceipt(receiptId)
                ?: return@withContext NetworkResult.Error(
                    AppFailure.Fatal("receipt $receiptId not found"),
                )

            if (stored.receipt.status != ReceiptStatus.DRAFT) {
                return@withContext NetworkResult.Error(
                    AppFailure.Validation("This receipt has already been submitted"),
                )
            }
            if (stored.lines.isEmpty()) {
                return@withContext NetworkResult.Error(
                    AppFailure.Validation("Register at least one line before posting", "lines"),
                )
            }

            val payload = ProductReceiptPayload(
                // The receipt id doubles as the idempotency key: it is stable across
                // retries and unique per capture session, so a replayed post is recognised
                // by the service instead of creating a second product receipt.
                idempotencyKey = stored.receipt.receiptId,
                dataAreaId = stored.receipt.dataAreaId,
                receiptId = stored.receipt.receiptId,
                purchaseOrderNumber = stored.receipt.purchId,
                vendorPackingSlipId = stored.receipt.vendorPackingSlipId,
                receiptDate = Formatters.epochMillisToODataDate(stored.receipt.receiptDate),
                personnelNumber = stored.receipt.personnelNumber,
                deviceId = stored.receipt.deviceId,
                lines = stored.lines.map { line ->
                    ReceiptLinePayload(
                        purchLineId = line.purchLineId,
                        lineNumber = line.lineNumber,
                        itemNumber = line.itemId,
                        receiveNowQuantity = line.enteredQuantity.toDouble(),
                        unitSymbol = line.enteredUnit,
                        siteId = line.siteId,
                        warehouseId = line.warehouseId,
                        locationId = line.locationId,
                        batchNumber = line.batchId,
                        serialNumbers = listOfNotNull(line.serialId),
                    )
                },
            )

            val entry = syncQueue.buildEntry(
                operation = SyncOperation.POST_PRODUCT_RECEIPT,
                aggregateId = stored.receipt.receiptId,
                payload = payload,
                workerId = stored.receipt.workerId,
                deviceId = stored.receipt.deviceId,
            )

            database.withTransaction {
                receiptDao.updateStatus(
                    receiptId = receiptId,
                    status = ReceiptStatus.PENDING_POST,
                    syncState = SyncState.PENDING,
                    error = null,
                )
                syncQueue.enqueue(entry)
            }

            scheduler.requestImmediatePush()
            Timber.i("receipt %s queued for posting", receiptId)
            NetworkResult.Success(Unit)
        }

    override suspend fun discardDraft(receiptId: String) = withContext(ioDispatcher) {
        val stored = receiptDao.getReceipt(receiptId) ?: return@withContext
        // Only an untouched draft may be discarded. Anything already queued belongs to the
        // sync engine, and deleting it would lose a transaction the worker believes is posted.
        if (stored.receipt.status == ReceiptStatus.DRAFT) {
            receiptDao.deleteReceipt(receiptId)
        }
    }

    override fun observePendingReceiptCount(): Flow<Int> = receiptDao.observePendingCount()
}
