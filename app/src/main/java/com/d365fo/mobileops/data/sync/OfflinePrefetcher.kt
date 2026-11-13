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
    /** Open counts (hazard, wall to wall, blind) whose item lists were saved. */
    val counts: Int = 0,
    val savedAt: Long? = null,
    val running: Boolean = false,
    val error: String? = null,
)

/**
 * Opens everything this warehouse's processes work from ahead of time (user, 13 Nov: "every
 * process to have full data load") - every open purchase order with its lines, every open
 * transfer order with its lines, and every open count with its items - so the offline read cache
 * holds them before the connection drops. Each lookup here is the same call
 * the receiving and transfer flows make, with the same arguments, so the saved answer is the
 * one those flows find when they look the order up offline.
 *
 * One call at a time with [PACE_MS] between them, at most once per [MIN_INTERVAL_MS] per
 * warehouse, after [SETTLE_MS] and a random delay of up to [JITTER_MS]. A fleet signing in at shift
 * start then spreads its calls instead of hitting D365FO's service protection limits all at once.
 * [MAX_PER_KIND] is only a backstop against a runaway list.
 */
@Singleton
class OfflinePrefetcher @Inject constructor(
    private val lookups: OnDemandLookupRepository,
    private val transferList: TransferListRepository,
    private val transferLookup: TransferLookupRepository,
    private val enquiry: com.d365fo.mobileops.data.repository.EnquiryRepository,
    private val countRequests: com.d365fo.mobileops.data.counting.CountRequestRepository,
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
            // Never in the first seconds after Home opens (13 Nov, performance: 11 order lookups in a
            // row competed with what the worker opened first), then spread over the fleet as before.
            if (jitter) delay(SETTLE_MS + FleetJitter.millisUpTo(JITTER_MS))
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
        var counts = 0
        var error: String? = null

        // Today's list as Home and PO receiving ask for it, then every open PO and its lines.
        lookups.lookupOrdersForToday(warehouseId)
        when (val open = enquiry.openOrders(warehouseId)) {
            is NetworkResult.Success -> open.data.filter { it.receivable }.take(MAX_PER_KIND).forEach { order ->
                delay(PACE_MS)
                if (lookups.lookupOrder(order.purchId) is NetworkResult.Success) purchaseOrders++
            }
            is NetworkResult.Error -> error = open.failure.message
            NetworkResult.Loading -> Unit
        }

        when (val open = transferList.openTransferOrders(warehouseId)) {
            is NetworkResult.Success -> open.data.take(MAX_PER_KIND).forEach { order ->
                delay(PACE_MS)
                val mode = if (order.outbound) TransferMode.SHIP else TransferMode.RECEIVE
                if (transferLookup.lookupOrder(order.transferId, mode, warehouseId) is NetworkResult.Success) transferOrders++
            }
            is NetworkResult.Error -> error = error ?: open.failure.message
            NetworkResult.Loading -> Unit
        }

        // The Count lists and each open count's items, as the count screens ask for them.
        when (val open = countRequests.counts(warehouseId)) {
            is NetworkResult.Success -> open.data.filter { it.open }.take(MAX_PER_KIND).forEach { count ->
                delay(PACE_MS)
                if (countRequests.items(count.masterId) is NetworkResult.Success) counts++
            }
            is NetworkResult.Error -> error = error ?: open.failure.message
            NetworkResult.Loading -> Unit
        }

        Timber.i("offline prefetch for %s: %d purchase orders, %d transfer orders, %d counts", warehouseId, purchaseOrders, transferOrders, counts)
        return OfflineReadiness(
            warehouseId = warehouseId,
            purchaseOrders = purchaseOrders,
            transferOrders = transferOrders,
            counts = counts,
            savedAt = System.currentTimeMillis(),
            running = false,
            error = error,
        )
    }

    private companion object {
        const val MAX_PER_KIND = 2_000
        const val PACE_MS = 150L
        const val MIN_INTERVAL_MS = 30 * 60 * 1000L
        const val JITTER_MS = 60_000L
        const val SETTLE_MS = 20_000L
    }
}
