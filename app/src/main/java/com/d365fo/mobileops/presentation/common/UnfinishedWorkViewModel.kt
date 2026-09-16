package com.d365fo.mobileops.presentation.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.local.entity.FlowType
import com.d365fo.mobileops.data.repository.FlowSessionRepository
import com.d365fo.mobileops.data.repository.UnfinishedWork
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
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
) : ViewModel() {

    val unfinished: StateFlow<List<UnfinishedWork>> = flowSessions.unfinished
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun discard(work: UnfinishedWork) {
        viewModelScope.launch { flowSessions.clear(work.flowType, work.orderId) }
    }

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
