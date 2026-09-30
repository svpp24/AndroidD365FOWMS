package com.d365fo.mobileops.data.sync

import com.d365fo.mobileops.data.local.entity.FlowType
import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.PostLineDto
import com.d365fo.mobileops.data.remote.dto.PostQueueEntryDto
import com.d365fo.mobileops.data.remote.dto.PostQueueRequestBody
import com.d365fo.mobileops.data.remote.dto.PostQueueServiceRequest
import com.d365fo.mobileops.data.remote.dto.PostQueueServiceResponse
import com.d365fo.mobileops.data.remote.dto.PostRequestBody
import com.d365fo.mobileops.data.remote.dto.PostServiceRequest
import com.d365fo.mobileops.data.remote.dto.PostServiceResponse
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.local.entity.SyncOperation
import com.d365fo.mobileops.data.local.entity.SyncQueueEntity
import com.d365fo.mobileops.data.remote.dto.SetModulePermissionRequestBody
import com.d365fo.mobileops.data.remote.dto.SetModulePermissionServiceRequest
import com.d365fo.mobileops.data.repository.FlowSessionRepository
import com.d365fo.mobileops.data.sync.payload.ProductReceiptPayload
import com.d365fo.mobileops.data.sync.payload.SetWorkerPermissionPayload
import com.d365fo.mobileops.data.sync.payload.SyncPayload
import com.d365fo.mobileops.data.sync.payload.TransferLinePayload
import com.d365fo.mobileops.data.sync.payload.TransferReceivePayload
import com.d365fo.mobileops.data.sync.payload.TransferShipPayload
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes a queued operation to `HSMobileOpsPostingSvc` and reconciles local state on success.
 *
 * The dispatcher owns the whole post-and-reconcile step so that [TransactionPushWorker] stays
 * thin: WorkManager decides *when* to run, this decides *what* running means.
 *
 * **Product receipt, transfer shipment and transfer receipt are one operation with three call
 * sites.** All three post through `HSMobileOpsPostRequest`/`HSMobileOpsPostResponse` — see
 * `aos/HSMobileOps/AxClass/HSMobileOpsPostingService.xml` — and differ only in which endpoint
 * carries them and what `sourceId`/`reference` mean. [post] is the one place that shape is
 * built; the three public entry points only choose the endpoint.
 */
@Singleton
class PostingDispatcher @Inject constructor(
    private val serviceApi: MobileOpsServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val flowSessions: FlowSessionRepository,
    private val queue: SyncQueueRepository,
    private val json: Json,
) {

    suspend fun dispatch(entry: SyncQueueEntity): NetworkResult<Unit> {
        val payload = runCatching { queue.decodePayload(entry) }.getOrElse {
            // A payload we cannot parse will never parse. Fail it permanently rather than
            // retrying a corrupt row forever.
            return NetworkResult.Error(
                AppFailure.Fatal("corrupt sync payload for ${entry.aggregateId}", it),
            )
        }

        return when (entry.operation) {
            SyncOperation.POST_PRODUCT_RECEIPT ->
                postProductReceipt(payload as ProductReceiptPayload)

            SyncOperation.TRANSFER_ORDER_SHIP ->
                postTransfer(payload as TransferShipPayload)

            SyncOperation.TRANSFER_ORDER_RECEIVE ->
                postTransfer(payload as TransferReceivePayload)

            SyncOperation.SET_WORKER_PERMISSION ->
                setWorkerPermission(payload as SetWorkerPermissionPayload)

            // Journals and packing slips have no HSMobileOps endpoint yet — that is a
            // different X++ service, not written. Failing loudly here beats silently
            // dropping a captured count on the floor.
            SyncOperation.POST_ARRIVAL_JOURNAL,
            SyncOperation.POST_PACKING_SLIP,
            SyncOperation.POST_INVENTORY_JOURNAL,
            -> NetworkResult.Error(
                AppFailure.Fatal("${entry.operation} has no posting service yet"),
            )
        }
    }

    private suspend fun postProductReceipt(payload: ProductReceiptPayload): NetworkResult<Unit> =
        post(
            call = { req -> serviceApi.postProductReceipt(req) },
            dataAreaId = payload.dataAreaId,
            idempotencyKey = payload.idempotencyKey,
            sourceId = payload.purchaseOrderNumber,
            reference = payload.vendorPackingSlipId,
            userName = payload.userName,
            personnelNumber = payload.personnelNumber.orEmpty(),
            deviceId = payload.deviceId,
            lines = payload.lines.map {
                PostLineDto(
                    lineNumber = it.lineNumber,
                    itemId = it.itemNumber,
                    quantity = it.receiveNowQuantity,
                    unitId = it.unitSymbol,
                    siteId = it.siteId.orEmpty(),
                    warehouseId = it.warehouseId.orEmpty(),
                    locationId = it.locationId.orEmpty(),
                    batchId = it.batchNumber.orEmpty(),
                    batchManufacturingDate = it.batchManufacturingDate.orEmpty(),
                    batchExpiryDate = it.batchExpiryDate.orEmpty(),
                    serialIds = it.serialNumbers,
                )
            },
            onPosted = {
                flowSessions.clear(FlowType.RECEIVING, payload.purchaseOrderNumber)
            },
        )

    /**
     * One function for both directions. [TransferShipPayload] and [TransferReceivePayload]
     * share every field except which one they are, so the call is picked by type rather than
     * duplicating this method for each.
     */
    private suspend fun postTransfer(payload: SyncPayload): NetworkResult<Unit> {
        val transferId: String
        val lines: List<TransferLinePayload>
        val flowType: FlowType
        val userName: String
        val personnelNumber: String?
        val deviceId: String
        val call: suspend (PostServiceRequest) -> retrofit2.Response<PostServiceResponse>

        when (payload) {
            is TransferShipPayload -> {
                transferId = payload.transferId
                lines = payload.lines
                flowType = FlowType.TRANSFER_SHIP
                userName = payload.userName
                personnelNumber = payload.personnelNumber
                deviceId = payload.deviceId
                call = { req -> serviceApi.postTransferShipment(req) }
            }

            is TransferReceivePayload -> {
                transferId = payload.transferId
                lines = payload.lines
                flowType = FlowType.TRANSFER_RECEIVE
                userName = payload.userName
                personnelNumber = payload.personnelNumber
                deviceId = payload.deviceId
                call = { req -> serviceApi.postTransferReceipt(req) }
            }

            else -> return NetworkResult.Error(
                AppFailure.Fatal("postTransfer called with ${payload::class.simpleName}"),
            )
        }

        return post(
            call = call,
            dataAreaId = payload.dataAreaId,
            idempotencyKey = payload.idempotencyKey,
            sourceId = transferId,
            reference = "",
            userName = userName,
            personnelNumber = personnelNumber.orEmpty(),
            deviceId = deviceId,
            lines = lines.map {
                PostLineDto(
                    lineNumber = it.lineNumber,
                    itemId = it.itemNumber,
                    quantity = it.quantity,
                    unitId = it.unitSymbol,
                    locationId = it.locationId.orEmpty(),
                    batchId = it.batchNumber.orEmpty(),
                    serialIds = it.serialNumbers,
                )
            },
            onPosted = { flowSessions.clear(flowType, transferId) },
        )
    }

    /**
     * Pushes one queued permission toggle. Unlike the postings above, [SetWorkerPermissionPayload]
     * has no per-entity "already applied" flag to reconcile against - `setModulePermission` is
     * naturally idempotent (it always sets the row to the requested value, never increments or
     * appends), so a duplicate delivery from a retried queue entry is harmless by construction and
     * needs no `alreadyPosted`-style check.
     */
    private suspend fun setWorkerPermission(payload: SetWorkerPermissionPayload): NetworkResult<Unit> {
        val request = SetModulePermissionServiceRequest(
            SetModulePermissionRequestBody(
                dataAreaId = payload.dataAreaId,
                actingUserName = payload.actingUserName,
                targetUserName = payload.targetUserName,
                module = payload.workflow,
                isAllowed = payload.allowed,
            ),
        )

        return when (val result = errorMapper.safeCall { serviceApi.setModulePermission(request) }) {
            is NetworkResult.Success ->
                if (result.data.success) {
                    NetworkResult.Success(Unit)
                } else {
                    NetworkResult.Error(
                        AppFailure.BusinessRule(
                            result.data.message ?: "D365FO rejected the permission change",
                            d365ErrorCode = result.data.errorCode,
                        ),
                    )
                }

            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
        }
    }

    /** Posts one request and interprets the response. */
    private suspend fun post(
        call: suspend (PostServiceRequest) -> retrofit2.Response<PostServiceResponse>,
        dataAreaId: String,
        idempotencyKey: String,
        sourceId: String,
        reference: String,
        userName: String,
        personnelNumber: String,
        deviceId: String,
        lines: List<PostLineDto>,
        onPosted: suspend () -> Unit,
    ): NetworkResult<Unit> {
        val request = PostServiceRequest(
            PostRequestBody(
                dataAreaId = dataAreaId,
                idempotencyKey = idempotencyKey,
                sourceId = sourceId,
                reference = reference,
                userName = userName,
                personnelNumber = personnelNumber,
                deviceId = deviceId,
                // Serialized here, once, rather than carried as a nested array — see
                // PostRequestBody.linesJson for why.
                linesJson = json.encodeToString(ListSerializer(PostLineDto.serializer()), lines),
            ),
        )

        return when (val result = errorMapper.safeCall { call(request) }) {
            is NetworkResult.Success -> {
                val body = result.data
                // alreadyPosted is a success, not a duplicate error: it means a previous
                // attempt reached D365FO and only the response was lost in transit. Treating
                // it as a failure would strand a receipt that is already in the ledger and
                // retry it forever against a service that will keep saying the same thing.
                if (body.success || body.alreadyPosted) {
                    onPosted()
                    NetworkResult.Success(Unit)
                } else {
                    NetworkResult.Error(
                        AppFailure.BusinessRule(
                            body.message ?: "D365FO rejected the posting",
                            d365ErrorCode = body.errorCode,
                        ),
                    )
                }
            }

            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
        }
    }

    /**
     * Works every Pending row of `HSMobileOpsPostQueue` for [dataAreaId] and returns what each
     * became. This is the one call in this class that is slow on purpose - it is where the
     * registration or transfer-posting work that used to block [postProductReceipt] now happens,
     * off the operator's own trigger on the Activity screen rather than off a scan.
     */
    suspend fun processPending(dataAreaId: String): NetworkResult<List<PostQueueEntry>> =
        queueCall(dataAreaId) { serviceApi.processPending(it) }

    /** The most recent rows for [dataAreaId], whatever their status - read-only, always fast. */
    suspend fun getQueueStatus(dataAreaId: String): NetworkResult<List<PostQueueEntry>> =
        queueCall(dataAreaId) { serviceApi.getQueueStatus(it) }

    private suspend fun queueCall(
        dataAreaId: String,
        call: suspend (PostQueueServiceRequest) -> retrofit2.Response<PostQueueServiceResponse>,
    ): NetworkResult<List<PostQueueEntry>> {
        val request = PostQueueServiceRequest(PostQueueRequestBody(dataAreaId))
        return when (val result = errorMapper.safeCall { call(request) }) {
            is NetworkResult.Success -> NetworkResult.Success(result.data.rows.map { it.toDomain() })
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
        }
    }

    private fun PostQueueEntryDto.toDomain() = PostQueueEntry(
        sourceId = sourceId,
        documentType = documentType,
        status = status,
        errorMessage = errorMessage,
        receivedUtc = receivedUtc,
        processedUtc = processedUtc,
        userName = userName,
        linesJson = linesJson,
    )
}
