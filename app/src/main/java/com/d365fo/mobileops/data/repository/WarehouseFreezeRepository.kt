package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.data.remote.api.CountTaskServiceApi
import com.d365fo.mobileops.data.remote.dto.FreezeStatusRequestBody
import com.d365fo.mobileops.data.remote.dto.FreezeStatusServiceRequest
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Is a warehouse frozen for a wall-to-wall count? Asked when a receipt, a transfer or a return
 * starts, so the worker learns before scanning anything, not at posting. Only a courtesy: D365FO
 * refuses the posting itself whatever this said. Unreachable or an older server reads as not
 * frozen, for the same reason.
 */
@Singleton
class WarehouseFreezeRepository @Inject constructor(
    private val api: CountTaskServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val ioDispatcher: CoroutineDispatcher,
) {
    /** D365FO's own reason when [warehouseId] is frozen, else null. */
    suspend fun frozenMessage(warehouseId: String?): String? {
        if (warehouseId.isNullOrBlank()) return null
        val company = companyProvider()?.takeIf { it.isNotBlank() }?.lowercase() ?: return null
        return withContext(ioDispatcher) {
            val result = errorMapper.safeCall {
                api.getFreezeStatus(FreezeStatusServiceRequest(FreezeStatusRequestBody(dataAreaId = company, warehouseId = warehouseId)))
            }
            (result as? NetworkResult.Success)?.data
                ?.takeIf { it.success && it.frozen }
                ?.let { it.message?.takeIf { m -> m.isNotBlank() } ?: "$warehouseId is frozen for a wall-to-wall count." }
        }
    }
}
