package com.d365fo.mobileops.data.sync

import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.DeviceHeartbeatBody
import com.d365fo.mobileops.data.remote.dto.DeviceHeartbeatServiceRequest
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's check-in for Sync health in D365FO (user, 16 Nov 2026): it is on, and what waits in
 * its posting queue - so a device that is on but cannot post shows as "Postings stuck", and one
 * that went quiet as "Not seen". Sent from the posting worker at most every [EVERY_MS]; best
 * effort, never fails anything.
 */
@Singleton
class DeviceHeartbeat @Inject constructor(
    private val service: MobileOpsServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val secureStore: SecureStore,
    private val queue: SyncQueueRepository,
    private val sessions: WorkerSessionRepository,
) {
    @Volatile private var lastSentAt = 0L

    suspend fun sendIfDue(now: Long = System.currentTimeMillis()) {
        if (now - lastSentAt < EVERY_MS) return
        val company = companyProvider()?.takeIf { it.isNotBlank() }?.lowercase() ?: return
        lastSentAt = now
        val pending = queue.observePendingCount().first()
        val failed = queue.observeUnacknowledgedFailureCount().first()
        val oldest = queue.observeOldestQueuedAt().first()
        val result = errorMapper.safeCall {
            service.deviceHeartbeat(
                DeviceHeartbeatServiceRequest(
                    DeviceHeartbeatBody(
                        dataAreaId = company,
                        deviceId = secureStore.deviceId(),
                        warehouse = sessions.session.value?.selected?.warehouseId.orEmpty(),
                        queuePending = pending,
                        queueFailed = failed,
                        oldestQueuedSeconds = if (pending > 0 && oldest != null) ((now - oldest) / 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt() else 0,
                        appVersion = com.d365fo.mobileops.BuildConfig.VERSION_NAME,
                    ),
                ),
            )
        }
        if (result is NetworkResult.Error) {
            lastSentAt = now - EVERY_MS + RETRY_MS
            Timber.i("Device check-in not sent: %s", result.failure.message)
        }
    }

    companion object {
        const val EVERY_MS = 15 * 60_000L
        const val RETRY_MS = 5 * 60_000L
    }
}
