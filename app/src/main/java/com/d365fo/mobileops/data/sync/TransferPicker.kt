package com.d365fo.mobileops.data.sync

import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.PostLineDto
import com.d365fo.mobileops.data.remote.dto.PostRequestBody
import com.d365fo.mobileops.data.remote.dto.PostServiceRequest
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks a transfer line in D365FO the moment it is scanned on Transfer - Ship, so its issue
 * transaction is Picked before the shipment is posted (feedback 8b3daea2) - and unpicks it again
 * when the worker removes the line or lowers its quantity. HSMobileOpsPostingSvc/pickTransferLine.
 *
 * Live only: offline, or when D365FO refuses, the line simply stays unpicked and the shipment
 * posting picks it, as before. A line sent with Picked is not picked again at posting.
 */
@Singleton
class TransferPicker @Inject constructor(
    private val api: MobileOpsServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val json: Json,
) {
    /** [quantity] above zero picks, below zero unpicks. */
    suspend fun pick(
        dataAreaId: String,
        userName: String,
        deviceId: String,
        transferId: String,
        lineNumber: Double,
        itemId: String,
        unitId: String,
        quantity: BigDecimal,
        batchId: String?,
        serialIds: List<String>,
        locationId: String?,
    ): NetworkResult<Unit> {
        val line = PostLineDto(
            lineNumber = lineNumber,
            itemId = itemId,
            quantity = quantity.toDouble(),
            unitId = unitId,
            locationId = locationId.orEmpty(),
            batchId = batchId.orEmpty(),
            serialIds = serialIds,
        )
        val request = PostServiceRequest(
            PostRequestBody(
                dataAreaId = dataAreaId,
                idempotencyKey = java.util.UUID.randomUUID().toString(),
                sourceId = transferId,
                userName = userName,
                deviceId = deviceId,
                linesJson = json.encodeToString(ListSerializer(PostLineDto.serializer()), listOf(line)),
            ),
        )
        return when (val result = errorMapper.safeCall { api.pickTransferLine(request) }) {
            is NetworkResult.Success -> if (result.data.success) {
                NetworkResult.Success(Unit)
            } else if (result.data.errorCode == "RETRY") {
                NetworkResult.Error(AppFailure.Transient(result.data.message ?: "D365FO was busy"))
            } else {
                NetworkResult.Error(
                    AppFailure.BusinessRule(
                        result.data.message ?: "D365FO could not pick it",
                        d365ErrorCode = result.data.errorCode,
                    ),
                )
            }
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
        }
    }
}
