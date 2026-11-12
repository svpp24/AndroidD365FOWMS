package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.TransferListRequestBody
import com.d365fo.mobileops.data.remote.dto.TransferListServiceRequest
import com.d365fo.mobileops.data.remote.dto.TransferListServiceResponse
import com.d365fo.mobileops.data.remote.dto.TransferSummaryDto
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import com.d365fo.mobileops.util.plainError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/** An open transfer order this warehouse can act on - ship it ([outbound]) or receive it. */
data class TransferOrderSummary(
    val transferId: String,
    val fromWarehouseId: String,
    val toWarehouseId: String,
    val status: String,
    val shipDate: String,
    val receiveDate: String,
    val lineCount: Int,
    val outbound: Boolean,
) {
    /** The other end of the transfer - the store it goes to, or comes from. */
    val otherWarehouseId: String get() = if (outbound) toWarehouseId else fromWarehouseId
}

/** Reads for the Transfer orders screen and Home's figures (HSMobileOpsTransferListSvc). */
@Singleton
class TransferListRepository @Inject constructor(
    private val service: MobileOpsServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val ioDispatcher: CoroutineDispatcher,
    private val listCache: ListCache,
) {

    /** Shared for a short while by Home, Transfer orders, Create transfer and the prefetcher; [fresh] asks D365FO now. */
    suspend fun openTransferOrders(warehouseId: String, fresh: Boolean = false): NetworkResult<List<TransferOrderSummary>> {
        val key = "transfers:${companyProvider().orEmpty().lowercase()}:${warehouseId.trim().uppercase()}"
        if (fresh) listCache.invalidate(key)
        return listCache.get(key) {
            call("Couldn't list transfer orders") { service.listTransferOrders(request(warehouseId)) }
                .map { response -> response.orders.filter { it.transferId.isNotBlank() }.map(::toSummary) }
        }
    }

    private fun request(warehouseId: String, days: Int = 0, search: String = "") = TransferListServiceRequest(
        TransferListRequestBody(
            dataAreaId = companyProvider()?.takeIf { it.isNotBlank() }?.lowercase().orEmpty(),
            warehouseId = warehouseId.trim(),
            days = days,
            search = search,
        ),
    )

    private suspend fun call(
        whatFailed: String,
        block: suspend () -> Response<TransferListServiceResponse>,
    ): NetworkResult<TransferListServiceResponse> = withContext(ioDispatcher) {
        when (val result = errorMapper.safeCall { block() }) {
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> if (!result.data.success) {
                NetworkResult.Error(
                    AppFailure.BusinessRule(plainError(result.data.errorCode, whatFailed), result.data.errorCode),
                )
            } else {
                result
            }
        }
    }

    private fun <T, R> NetworkResult<T>.map(transform: (T) -> R): NetworkResult<R> = when (this) {
        is NetworkResult.Success -> NetworkResult.Success(transform(data))
        is NetworkResult.Error -> this
        NetworkResult.Loading -> NetworkResult.Loading
    }

    private fun toSummary(dto: TransferSummaryDto) = TransferOrderSummary(
        transferId = dto.transferId,
        fromWarehouseId = dto.fromWarehouseId,
        toWarehouseId = dto.toWarehouseId,
        status = dto.status,
        shipDate = dto.shipDate,
        receiveDate = dto.receiveDate,
        lineCount = dto.lineCount,
        outbound = dto.direction.equals("OUT", ignoreCase = true),
    )

}
