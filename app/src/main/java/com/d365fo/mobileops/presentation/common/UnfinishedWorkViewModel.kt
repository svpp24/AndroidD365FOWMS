package com.d365fo.mobileops.presentation.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.local.entity.FlowType
import com.d365fo.mobileops.data.repository.FlowSessionRepository
import com.d365fo.mobileops.data.repository.PendingFlowResume
import com.d365fo.mobileops.data.repository.UnfinishedWork
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Work someone started and did not finish.
 *
 * Surfaced on the home screen rather than left to be rediscovered by scanning the same order
 * again. A half-counted pallet the app knows about but never mentions is, from the operator's
 * side, indistinguishable from one it lost — and the second time that appears to happen, they
 * start keeping a paper tally beside the device.
 */
@HiltViewModel
class UnfinishedWorkViewModel @Inject constructor(
    private val flowSessions: FlowSessionRepository,
    private val pendingFlowResume: PendingFlowResume,
    private val scannerBridge: HidScannerBridge,
    workerSessions: WorkerSessionRepository,
) : ViewModel() {

    /**
     * Unfinished work at the selected warehouse. Work saved at another warehouse is on hold -
     * kept, not shown - until the worker switches back to it (see ChangeWarehouseScreen).
     * Work saved with no warehouse shows everywhere.
     */
    val unfinished: StateFlow<List<UnfinishedWork>> = combine(flowSessions.unfinished, workerSessions.session) { all, session ->
        val here = session?.selected?.warehouseId
        all.filter { it.warehouseId.isNullOrBlank() || here == null || it.warehouseId.equals(here, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** How many unfinished jobs are on hold at other warehouses. */
    val onHoldElsewhere: StateFlow<Int> = combine(flowSessions.unfinished, workerSessions.session) { all, session ->
        val here = session?.selected?.warehouseId ?: return@combine 0
        all.count { !it.warehouseId.isNullOrBlank() && !it.warehouseId.equals(here, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun discard(work: UnfinishedWork) {
        viewModelScope.launch { flowSessions.clear(work.flowType, work.orderId) }
    }

    /** The route to navigate to for [work], having first handed off which order to auto-fill. */
    fun resume(work: UnfinishedWork): String {
        pendingFlowResume.set(work.flowType, work.orderId)
        return routeFor(work.flowType)
    }

    /**
     * True just after a hardware scan - see [HidScannerBridge.justScanned]. The physical scan
     * trigger fires a phantom touch on some devices alongside the real scan; on the Home screen
     * that phantom touch was landing on whatever tile sits under it and opening that module,
     * even though nothing was actually tapped. Guarding navigation here is the same fix already
     * applied to the back icon in ReceivingFlowScreen/TransferFlowScreen.
     */
    fun justScanned(): Boolean = scannerBridge.justScanned()

    companion object {
        /** The route a given saved job resumes into. */
        fun routeFor(flowType: FlowType): String = when (flowType) {
            FlowType.RECEIVING -> Routes.RECEIVE_FLOW
            FlowType.TRANSFER_SHIP -> Routes.TRANSFER_SHIP
            FlowType.TRANSFER_RECEIVE -> Routes.TRANSFER_RECEIVE
        }

        fun labelFor(flowType: FlowType): String = when (flowType) {
            FlowType.RECEIVING -> "Receiving"
            FlowType.TRANSFER_SHIP -> "Transfer ship"
            FlowType.TRANSFER_RECEIVE -> "Transfer receive"
        }
    }
}
