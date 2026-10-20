package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.data.remote.dto.toItemUnits
import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.TransferLineDto
import com.d365fo.mobileops.data.remote.dto.TransferLineRequestBody
import com.d365fo.mobileops.data.remote.dto.TransferLineServiceRequest
import com.d365fo.mobileops.data.remote.dto.TransferRequestBody
import com.d365fo.mobileops.data.remote.dto.TransferServiceRequest
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.domain.model.TransferMode
import com.d365fo.mobileops.domain.model.TransferOrderLine
import com.d365fo.mobileops.domain.model.TransferOrderLookup
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transfer orders, read on demand for one direction at a time.
 *
 * **No OData fallback here, unlike purchase orders.** That fallback exists because the app had to
 * keep working on environments where the AOS model was not yet deployed — but transfers were never
 * built on OData, so there is nothing to fall back to. A missing service surfaces as a plain
 * failure, which is the honest answer: this module needs the service.
 */
@Singleton
class TransferLookupRepository @Inject constructor(
    private val service: MobileOpsServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private fun company(): String =
        companyProvider()?.takeIf { it.isNotBlank() }?.lowercase().orEmpty()

    suspend fun lookupOrder(
        transferId: String,
        mode: TransferMode,
        atWarehouseId: String?,
    ): NetworkResult<TransferOrderLookup> = withContext(ioDispatcher) {
        val trimmed = transferId.trim()
        if (trimmed.isEmpty()) {
            return@withContext NetworkResult.Error(
                AppFailure.BusinessRule("Enter or scan a transfer order number"),
            )
        }

        val result = errorMapper.safeCall {
            service.getTransferOrder(
                TransferServiceRequest(
                    TransferRequestBody(
                        dataAreaId = company(),
                        transferId = trimmed,
                        warehouseId = atWarehouseId.orEmpty(),
                        mode = mode.wire,
                    ),
                ),
            )
        }

        when (result) {
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> {
                val body = result.data
                if (!body.success) {
                    return@withContext NetworkResult.Error(
                        AppFailure.BusinessRule(
                            body.message ?: "Could not read $trimmed",
                        ),
                    )
                }
                NetworkResult.Success(
                    TransferOrderLookup(
                        transferId = body.transferId.ifBlank { trimmed },
                        transferStatus = body.transferStatus,
                        actionable = body.actionable,
                        blockReason = body.errorCode?.takeIf { !body.actionable },
                        fromSiteId = body.fromSiteId,
                        fromWarehouseId = body.fromWarehouseId,
                        toSiteId = body.toSiteId,
                        toWarehouseId = body.toWarehouseId,
                        outstandingLineCount = body.outstandingLineCount,
                        linesInline = body.linesIncluded,
                        lines = body.lines.map { it.toDomain() },
                        requireLocationCapture = body.requireLocationCapture,
                        defaultIssueLocation = body.defaultIssueLocation.ifBlank { null },
                        defaultReceiptLocation = body.defaultReceiptLocation.ifBlank { null },
                    ),
                )
            }
        }
    }

    /**
     * The outstanding lines for one item, in one direction.
     *
     * The large-order path: cost tracks the lines carrying that item rather than the size of the
     * order, so a thousand-line transfer behaves like a five-line one from the operator's side.
     */
    suspend fun lookupLinesForItem(
        transferId: String,
        itemNumber: String,
        mode: TransferMode,
    ): NetworkResult<List<TransferOrderLine>> = withContext(ioDispatcher) {
        val result = errorMapper.safeCall {
            service.getTransferLinesForItem(
                TransferLineServiceRequest(
                    TransferLineRequestBody(
                        dataAreaId = company(),
                        transferId = transferId.trim(),
                        itemId = itemNumber.trim(),
                        mode = mode.wire,
                    ),
                ),
            )
        }

        when (result) {
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            // NOT_ON_ORDER comes back with Success and an empty list: the operator is holding
            // something that is not outstanding here, which is an answer rather than a failure.
            is NetworkResult.Success -> NetworkResult.Success(
                result.data.lines.map { it.toDomain() },
            )
        }
    }
}

private fun TransferLineDto.toDomain(): TransferOrderLine = TransferOrderLine(
    lineNumber = lineNumber,
    itemId = itemId,
    itemName = itemName,
    unitId = unitId,
    transferQuantity = BigDecimal.valueOf(transferQty),
    shippedQuantity = BigDecimal.valueOf(shippedQty),
    receivedQuantity = BigDecimal.valueOf(receivedQty),
    // Already direction-correct from the server; the client never picks between ship and
    // receive quantities, which is the mistake this whole contract is shaped to prevent.
    remainingQuantity = BigDecimal.valueOf(remainingQty),
    fromSiteId = fromSiteId,
    fromWarehouseId = fromWarehouseId,
    toSiteId = toSiteId,
    toWarehouseId = toWarehouseId,
    locationId = locationId?.takeIf { it.isNotBlank() },
    variant = com.d365fo.mobileops.domain.model.ProductVariant.of(configId, sizeId, colorId, styleId),
    batchRequired = batchRequired,
    serialRequired = serialRequired,
    locationRequired = locationRequired,
    rules = com.d365fo.mobileops.domain.model.DimensionRules(batchMandatory, serialMandatory, locationMandatory, serialPerUnit),
    units = units.toItemUnits(),
    shippedBatches = shippedBatches
        .filter { it.batchId.isNotBlank() }
        .map { com.d365fo.mobileops.domain.model.ShippedBatch(it.batchId, BigDecimal.valueOf(it.qty)) },
    lineBatchId = lineBatchId?.takeIf { it.isNotBlank() },
)
