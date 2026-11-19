package com.d365fo.mobileops.presentation.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.repository.TransferListRepository
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.sync.SyncStatus
import com.d365fo.mobileops.data.sync.SyncStatusProvider
import com.d365fo.mobileops.domain.repository.OnDemandLookupRepository
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import timber.log.Timber
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home's "My Dashboard" figures (20 Oct restyle): open counts of this warehouse, POs due
 * today, transfers to ship from this warehouse, and what is waiting to post. Each one is
 * fetched on its own and stays null until it answers - a figure D365FO could not give is left
 * out, never guessed.
 */
@HiltViewModel
class HomeDashboardViewModel @Inject constructor(
    private val lookup: OnDemandLookupRepository,
    private val transfers: TransferListRepository,
    private val countRequests: com.d365fo.mobileops.data.counting.CountRequestRepository,
    private val listCache: com.d365fo.mobileops.data.repository.ListCache,
    private val workerSession: WorkerSessionRepository,
    syncStatusProvider: SyncStatusProvider,
    @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
) : ViewModel() {

    /** The last figures per warehouse: Home shows them at once, then D365FO's answer (user, 19 Nov 2026: slow to load). */
    private val kept = context.getSharedPreferences("home_figures", android.content.Context.MODE_PRIVATE)
    private var shownFor: String? = null

    data class Figures(
        val countsInProgress: Int? = null,
        val posDueToday: Int? = null,
        val transfersToShip: Int? = null,
        /** Transfers coming in to this warehouse today - part of Home's Receive count. */
        val transfersToReceive: Int? = null,
    )

    private val _figures = MutableStateFlow(Figures())
    val figures: StateFlow<Figures> = _figures.asStateFlow()

    val sync: StateFlow<SyncStatus> =
        syncStatusProvider.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatus.UNKNOWN)

    val warehouseId: String? get() = workerSession.session.value?.selected?.warehouseId

    init {
        // Something left the send queue (posted, or given up on): the open POs and transfers
        // changed in D365FO - ask again, so Home does not keep yesterday's number.
        viewModelScope.launch {
            var last = Int.MAX_VALUE
            syncStatusProvider.status.collect { s ->
                if (s.pendingCount < last && last != Int.MAX_VALUE) {
                    kotlinx.coroutines.delay(2_000)
                    refresh()
                }
                last = s.pendingCount
            }
        }
    }

    companion object {
        /** How often Home asks again while it stays on screen. */
        const val REFRESH_EVERY_MS = 60_000L
    }

    /** Each time Home shows. */
    fun refresh() {
        val wh = warehouseId
        if (wh.isNullOrBlank()) return
        if (shownFor != wh) {
            shownFor = wh
            _figures.value = restore(wh)
        }
        val started = System.currentTimeMillis()
        // The open counts the Count lists show - hazard, wall to wall and blind together (user,
        // 12 Nov: Home said 20 from the old counting journals while the lists held 10).
        viewModelScope.launch {
            // Kept for a short while like the PO and transfer figures (ListCache) - Home showed again
            // on every return, and asked each time (13 Nov, performance). The Count lists ask fresh.
            val open = (listCache.get("counts:${wh.uppercase()}", ttlMs = 45_000L) { countRequests.counts(wh) } as? NetworkResult.Success)?.data?.count { it.open }
            Timber.w("Home counts: open counts in %d ms", System.currentTimeMillis() - started)
            if (open != null) set(wh) { it.copy(countsInProgress = open) }
        }
        viewModelScope.launch {
            // The same POs PO receiving lists under "Due today": open, with something left to
            // receive - one received in full earlier today no longer counts (user, 9 Nov: the
            // card said 3 while the list showed 1).
            val due = (lookup.lookupOrdersForToday(wh) as? NetworkResult.Success)?.data?.count { it.receivable }
            Timber.w("Home counts: POs due today in %d ms", System.currentTimeMillis() - started)
            if (due != null) set(wh) { it.copy(posDueToday = due) }
        }
        viewModelScope.launch {
            // Due today only (user, 12 Nov): ship date today to ship, receive date today to receive -
            // the overdue ones stay in Enquiry > Transfer orders.
            val today = java.time.LocalDate.now().toString()
            val open = (transfers.openTransferOrders(wh) as? NetworkResult.Success)?.data
            Timber.w("Home counts: transfers in %d ms", System.currentTimeMillis() - started)
            if (open != null) {
                set(wh) {
                    it.copy(
                        transfersToShip = open.count { t -> t.outbound && t.shipDate.take(10) == today },
                        transfersToReceive = open.count { t -> !t.outbound && t.receiveDate.take(10) == today },
                    )
                }
            }
        }
    }

    /** Updates a figure and keeps it for the next start - only while Home still shows [wh]. */
    private fun set(wh: String, change: (Figures) -> Figures) {
        if (shownFor != wh) return
        val f = _figures.updateAndGet(change)
        val k = wh.uppercase()
        kept.edit().apply {
            listOf("counts" to f.countsInProgress, "pos" to f.posDueToday, "ship" to f.transfersToShip, "receive" to f.transfersToReceive)
                .forEach { (name, v) -> if (v != null) putInt("$k:$name", v) }
        }.apply()
    }

    private fun restore(wh: String): Figures {
        val k = wh.uppercase()
        fun get(name: String) = if (kept.contains("$k:$name")) kept.getInt("$k:$name", 0) else null
        return Figures(get("counts"), get("pos"), get("ship"), get("receive"))
    }
}
