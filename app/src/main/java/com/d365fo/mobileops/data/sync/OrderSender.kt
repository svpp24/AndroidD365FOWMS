package com.d365fo.mobileops.data.sync

import com.d365fo.mobileops.data.local.dao.OrderCreationDao
import com.d365fo.mobileops.data.local.entity.CreatedOrderStatus
import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.OrderLineDto
import com.d365fo.mobileops.data.remote.dto.OrderRequestBody
import com.d365fo.mobileops.data.remote.dto.linesJson
import com.d365fo.mobileops.data.remote.dto.OrderServiceRequest
import com.d365fo.mobileops.data.remote.dto.OrderServiceResponse
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.sync.payload.CreatePurchaseOrderPayload
import com.d365fo.mobileops.data.sync.payload.CreateTransferOrderPayload
import com.d365fo.mobileops.data.sync.payload.OrderLinePayload
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends a queued purchase or transfer order to HSMobileOpsOrderCreateSvc and records the outcome
 * on its created_order row: the D365FO number and prices, or D365FO's reason for refusing it.
 * Called from [PostingDispatcher], so offline orders go out with everything else in the queue.
 *
 * "RETRY" (D365FO busy) and no answer at all keep the order waiting; any other refusal fails it
 * and it shows under Activity with D365FO's own message.
 */
@Singleton
class OrderSender @Inject constructor(
    private val api: MobileOpsServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val dao: OrderCreationDao,
    private val json: Json,
) {

    suspend fun sendPurchase(p: CreatePurchaseOrderPayload): NetworkResult<Unit> = send(p.idempotencyKey) {
        api.createPurchaseOrder(
            OrderServiceRequest(
                OrderRequestBody(
                    dataAreaId = p.dataAreaId,
                    userName = p.userName,
                    deviceId = p.deviceId,
                    idempotencyKey = p.idempotencyKey,
                    vendorAccount = p.vendorAccount,
                    warehouseId = p.warehouseId,
                    deliveryDate = p.deliveryDate,
                    linesJson = linesJson(p.lines.map { it.toDto() }),
                ),
            ),
        )
    }

    suspend fun sendTransfer(p: CreateTransferOrderPayload): NetworkResult<Unit> = send(p.idempotencyKey) {
        api.createTransferOrder(
            OrderServiceRequest(
                OrderRequestBody(
                    dataAreaId = p.dataAreaId,
                    userName = p.userName,
                    deviceId = p.deviceId,
                    idempotencyKey = p.idempotencyKey,
                    fromWarehouseId = p.fromWarehouseId,
                    toWarehouseId = p.toWarehouseId,
                    shipDate = p.shipDate,
                    receiveDate = p.receiveDate,
                    linesJson = linesJson(p.lines.map { it.toDto() }),
                ),
            ),
        )
    }

    /**
     * Sends a queued return to a vendor. D365FO creates the return order, confirms it and posts
     * the packing slip; when the order was made but a later step failed, it is still CREATED here
     * and the message says what is left - a resend of the same key finishes it.
     */
    suspend fun sendReturn(p: com.d365fo.mobileops.data.sync.payload.CreatePurchaseReturnPayload): NetworkResult<Unit> =
        send(p.idempotencyKey, message = { body ->
            body.packingSlipId.ifBlank { null }?.let { "Posted · packing slip $it" } ?: body.message.ifBlank { null }
        }) {
            api.createPurchaseReturn(
                OrderServiceRequest(
                    OrderRequestBody(
                        dataAreaId = p.dataAreaId,
                        userName = p.userName,
                        deviceId = p.deviceId,
                        idempotencyKey = p.idempotencyKey,
                        vendorAccount = p.vendorAccount,
                        warehouseId = p.warehouseId,
                        returnReasonCode = p.returnReasonCode,
                        rmaNumber = p.rmaNumber,
                        returnActionId = p.returnActionId,
                        linesJson = linesJson(p.lines.map { it.toDto() }),
                    ),
                ),
            )
        }

    suspend fun sendConfirmation(p: com.d365fo.mobileops.data.sync.payload.ConfirmPurchaseOrderPayload): NetworkResult<Unit> {
        val now = System.currentTimeMillis()
        val result = errorMapper.safeCall {
            api.confirmPurchaseOrder(
                OrderServiceRequest(
                    OrderRequestBody(
                        dataAreaId = p.dataAreaId,
                        userName = p.userName,
                        deviceId = p.deviceId,
                        idempotencyKey = p.idempotencyKey,
                        purchId = p.purchId,
                    ),
                ),
            )
        }
        return when (result) {
            is NetworkResult.Success -> {
                val body = result.data
                when {
                    body.success -> {
                        val confirmation = body.confirmationNumber.ifBlank { null }
                        dao.markOrder(
                            id = p.idempotencyKey,
                            status = CreatedOrderStatus.CREATED,
                            documentNumber = body.documentNumber.ifBlank { p.purchId },
                            total = null,
                            currencyCode = null,
                            message = when {
                                body.message.isNotBlank() -> body.message + (confirmation?.let { " - confirmation $it" } ?: "")
                                confirmation != null -> "Confirmation $confirmation"
                                else -> null
                            },
                            pricedLinesJson = null,
                            now = now,
                        )
                        NetworkResult.Success(Unit)
                    }
                    body.errorCode == "RETRY" ->
                        NetworkResult.Error(AppFailure.Transient(body.message.ifBlank { "D365FO was busy" }))
                    else -> {
                        val reason = body.message.ifBlank { "D365FO refused the confirmation (${body.errorCode})" }
                        dao.markOrder(p.idempotencyKey, CreatedOrderStatus.FAILED, p.purchId, null, null, reason, null, now)
                        NetworkResult.Error(AppFailure.BusinessRule(reason, d365ErrorCode = body.errorCode))
                    }
                }
            }
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
        }
    }

    /**
     * Sends queued feedback, with its screenshot read from the device at send time. On success
     * the screenshot file is deleted - D365FO has it as an attachment.
     */
    suspend fun sendFeedback(p: com.d365fo.mobileops.data.sync.payload.SendFeedbackPayload): NetworkResult<Unit> {
        val now = System.currentTimeMillis()
        val shot = p.screenshotPath?.let { java.io.File(it) }?.takeIf { it.isFile }
        val base64 = shot?.let { android.util.Base64.encodeToString(it.readBytes(), android.util.Base64.NO_WRAP) }.orEmpty()
        val result = errorMapper.safeCall {
            api.submitFeedback(
                com.d365fo.mobileops.data.remote.dto.FeedbackServiceRequest(
                    com.d365fo.mobileops.data.remote.dto.FeedbackRequestBody(
                        dataAreaId = p.dataAreaId,
                        userName = p.userName,
                        deviceId = p.deviceId,
                        feedbackId = p.idempotencyKey,
                        warehouseId = p.warehouseId,
                        appVersion = p.appVersion,
                        deviceModel = p.deviceModel,
                        screen = p.screen,
                        feedbackType = p.feedbackType,
                        message = p.message,
                        logs = p.logs,
                        screenshotBase64 = base64,
                    ),
                ),
            )
        }
        return when (result) {
            is NetworkResult.Success -> {
                val body = result.data
                if (body.success) {
                    dao.markOrder(p.idempotencyKey, CreatedOrderStatus.CREATED, null, null, null, body.message.ifBlank { null }, null, now)
                    shot?.delete()
                    NetworkResult.Success(Unit)
                } else {
                    val reason = body.message.ifBlank { "D365FO refused the feedback (${body.errorCode})" }
                    dao.markOrder(p.idempotencyKey, CreatedOrderStatus.FAILED, null, null, null, reason, null, now)
                    NetworkResult.Error(AppFailure.BusinessRule(reason, d365ErrorCode = body.errorCode))
                }
            }
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
        }
    }

    private suspend fun send(
        orderId: String,
        // A PO is confirmed as it is created: "Confirmation <no>", or D365FO's reason it was
        // created but not confirmed.
        message: (OrderServiceResponse) -> String? = { body ->
            listOfNotNull(
                body.confirmationNumber.ifBlank { null }?.let { "Confirmation $it" },
                body.message.ifBlank { null },
            ).joinToString(" · ").ifBlank { null }
        },
        call: suspend () -> retrofit2.Response<OrderServiceResponse>,
    ): NetworkResult<Unit> {
        val now = System.currentTimeMillis()
        return when (val result = errorMapper.safeCall { call() }) {
            is NetworkResult.Success -> {
                val body = result.data
                when {
                    body.success -> {
                        val priced = body.lines.map { it.toPayload() }
                        dao.markOrder(
                            id = orderId,
                            status = CreatedOrderStatus.CREATED,
                            documentNumber = body.documentNumber,
                            total = if (body.lines.isEmpty() && body.alreadyCreated) null else body.orderTotal.takeIf { it != 0.0 },
                            currencyCode = body.currencyCode.ifBlank { null },
                            message = message(body),
                            pricedLinesJson = if (priced.isEmpty()) null else json.encodeToString(ListSerializer(OrderLinePayload.serializer()), priced),
                            now = now,
                        )
                        NetworkResult.Success(Unit)
                    }
                    body.errorCode == "RETRY" ->
                        NetworkResult.Error(AppFailure.Transient(body.message.ifBlank { "D365FO was busy" }))
                    else -> {
                        val reason = body.message.ifBlank { "D365FO refused the order (${body.errorCode})" }
                        dao.markOrder(orderId, CreatedOrderStatus.FAILED, null, null, null, reason, null, now)
                        NetworkResult.Error(AppFailure.BusinessRule(reason, d365ErrorCode = body.errorCode))
                    }
                }
            }
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
        }
    }

    private fun OrderLinePayload.toDto() = OrderLineDto(
        itemId = itemId,
        configId = configId,
        sizeId = sizeId,
        colorId = colorId,
        styleId = styleId,
        batchId = batchId,
        serialId = serialId,
        locationId = locationId,
        qty = qty,
        unitId = unitId,
        price = if (priceOverride) price ?: 0.0 else 0.0,
        priceOverride = priceOverride && price != null,
    )

    private fun OrderLineDto.toPayload() = OrderLinePayload(
        itemId = itemId,
        itemName = itemName,
        configId = configId,
        sizeId = sizeId,
        colorId = colorId,
        styleId = styleId,
        batchId = batchId,
        serialId = serialId,
        qty = qty,
        unitId = unitId,
        price = price,
        discPct = discPct,
        lineAmount = lineAmount,
        priceSource = priceSource.ifBlank { null },
    )
}
