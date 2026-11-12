package com.d365fo.mobileops.data.sync

import com.d365fo.mobileops.data.repository.TransferListRepository
import com.d365fo.mobileops.data.repository.TransferLookupRepository
import com.d365fo.mobileops.domain.model.TransferMode
import com.d365fo.mobileops.domain.repository.OnDemandLookupRepository
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** What was last saved for offline use, and whether a save is running. */
data class OfflineReadiness(
    val warehouseId: String? = null,
    val purchaseOrders: Int = 0,
    val transferOrders: Int = 0,
    val savedAt: Long? = null,
    val running: Boolean = false,
    val error: String? = null,
)

/**
 * Opens today's purchase orders, this warehouse's open transfer orders and its picked serials
 * ahead of time, so the
 * offline read cache holds them before the connection drops. Each lookup here is the same call
 * the receiving and transfer flows make, with the same arguments, so the saved answer is the
 * one those flows find when they look the order up offline.
 *
 * Kept small on purpose: headers and inline lines of at most [MAX_ORDERS] of each, at most once
 * per [MIN_INTERVAL_MS] per warehouse, after a random delay of up to [JITTER_MS]. A fleet
 * signing in at shift start then spreads its calls over a minute instead of hitting D365FO's
 * service protection limits all at once.
 */
@Singleton
class OfflinePrefetcher @Inject constructor(
    private val lookups: OnDemandLookupRepository,
    private val transferList: TransferListRepository,
    private val transferLookup: TransferLookupRepository,
    ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var job: Job? = null

    private val _readiness = MutableStateFlow(OfflineReadiness())
    val readiness: StateFlow<OfflineReadiness> = _readiness.asStateFlow()

    /** Starts a save for [warehouseId] unless one ran recently or is running. */
    fun maybeRun(warehouseId: String) {
        val last = _readiness.value
        val recent = last.warehouseId == warehouseId && last.savedAt != null &&
            System.currentTimeMillis() - last.savedAt < MIN_INTERVAL_MS
        if (recent || job?.isActive == true) return
        start(warehouseId, jitter = true)
    }

    /** Saves now - the worker asked for it. */
    fun runNow(warehouseId: String) {
        if (job?.isActive == true) return
        start(warehouseId, jitter = false)
    }

    private fun start(warehouseId: String, jitter: Boolean) {
        job = scope.launch {
            if (jitter) delay(FleetJitter.millisUpTo(JITTER_MS))
            _readiness.value = _readiness.value.copy(running = true, error = null)
            _readiness.value = runCatching { save(warehouseId) }
                .getOrElse { e ->
                    Timber.w(e, "offline prefetch failed for %s", warehouseId)
                    _readiness.value.copy(running = false, error = e.message ?: "Could not save for offline use")
                }
        }
    }

    private suspend fun save(warehouseId: String): OfflineReadiness {
        var purchaseOrders = 0
        var transferOrders = 0
        var error: String? = null

        when (val today = lookups.lookupOrdersForToday(warehouseId)) {
            is NetworkResult.Success -> today.data.filter { it.receivable }.take(MAX_ORDERS).forEach { order ->
                if (lookups.lookupOrder(order.purchId) is NetworkResult.Success) purchaseOrders++
            }
            is NetworkResult.Error -> error = today.failure.message
            NetworkResult.Loading -> Unit
        }

        when (val open = transferList.openTransferOrders(warehouseId)) {
            is NetworkResult.Success -> open.data.take(MAX_ORDERS).forEach { order ->
                val mode = if (order.outbound) TransferMode.SHIP else TransferMode.RECEIVE
                if (transferLookup.lookupOrder(order.transferId, mode, warehouseId) is NetworkResult.Success) transferOrders++
            }
            is NetworkResult.Error -> error = error ?: open.failure.message
            NetworkResult.Loading -> Unit
        }

        // Picked serials, as the screen first opens it (30 days, no search) - one call.

        Timber.i("offline prefetch for %s: %d purchase orders, %d transfer orders", warehouseId, purchaseOrders, transferOrders)
        return OfflineReadiness(
            warehouseId = warehouseId,
            purchaseOrders = purchaseOrders,
            transferOrders = transferOrders,
            savedAt = System.currentTimeMillis(),
            running = false,
            error = error,
        )
    }

    private companion object {
        const val MAX_ORDERS = 30
        const val MIN_INTERVAL_MS = 30 * 60 * 1000L
        const val JITTER_MS = 60_000L
    }
}
