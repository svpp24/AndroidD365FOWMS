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
    private val workerSession: WorkerSessionRepository,
    syncStatusProvider: SyncStatusProvider,
) : ViewModel() {

    data class Figures(
        val countsInProgress: Int? = null,
        val posDueToday: Int? = null,
        val transfersToShip: Int? = null,
        /** Open transfers coming in to this warehouse - part of Home's Receive count. */
        val transfersToReceive: Int? = null,
    )

    private val _figures = MutableStateFlow(Figures())
    val figures: StateFlow<Figures> = _figures.asStateFlow()

    val sync: StateFlow<SyncStatus> =
        syncStatusProvider.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatus.UNKNOWN)

    val warehouseId: String? get() = workerSession.session.value?.selected?.warehouseId

    /** Each time Home shows. */
    fun refresh() {
        val wh = warehouseId
        if (wh.isNullOrBlank()) return
        // The open counts the Count lists show - hazard, wall to wall and blind together (user,
        // 12 Nov: Home said 20 from the old counting journals while the lists held 10).
        viewModelScope.launch {
            val open = (countRequests.counts(wh) as? NetworkResult.Success)?.data?.count { it.open }
            if (open != null) _figures.update { it.copy(countsInProgress = open) }
        }
        viewModelScope.launch {
            // The same POs PO receiving lists under "Due today": open, with something left to
            // receive - one received in full earlier today no longer counts (user, 9 Nov: the
            // card said 3 while the list showed 1).
            val due = (lookup.lookupOrdersForToday(wh) as? NetworkResult.Success)?.data?.count { it.receivable }
            if (due != null) _figures.update { it.copy(posDueToday = due) }
        }
        viewModelScope.launch {
            val open = (transfers.openTransferOrders(wh) as? NetworkResult.Success)?.data
            if (open != null) _figures.update { it.copy(transfersToShip = open.count { t -> t.outbound }, transfersToReceive = open.count { t -> !t.outbound }) }
        }
    }
}
