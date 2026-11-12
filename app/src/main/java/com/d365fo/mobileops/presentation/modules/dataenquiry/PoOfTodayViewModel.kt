package com.d365fo.mobileops.presentation.modules.dataenquiry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.domain.repository.PurchaseOrderSummary
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Every open purchase order of this worker's warehouse (user, 12 Nov: was today's only), soonest
 * delivery first. Loads itself - there is nothing to scan.
 */
@HiltViewModel
class PoOfTodayViewModel @Inject constructor(
    private val enquiry: com.d365fo.mobileops.data.repository.EnquiryRepository,
    private val workerSession: WorkerSessionRepository,
    private val pendingFlowResume: com.d365fo.mobileops.data.repository.PendingFlowResume,
) : ViewModel() {

    /** Hands [purchId] to the receiving flow, which opens on it - the caller then navigates there. */
    fun startReceiving(purchId: String) =
        pendingFlowResume.set(com.d365fo.mobileops.data.local.entity.FlowType.RECEIVING, purchId)

    private val _state = MutableStateFlow(PoOfTodayState())
    val state: StateFlow<PoOfTodayState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val warehouseId = workerSession.session.value?.selected?.warehouseId
            _state.update { it.copy(warehouseId = warehouseId) }
            load(warehouseId)
        }
    }

    fun refresh() {
        viewModelScope.launch { load(_state.value.warehouseId) }
    }

    private suspend fun load(warehouseId: String?) {
        if (warehouseId.isNullOrBlank()) {
            _state.update { it.copy(busy = false, error = "No warehouse is assigned to you.") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        when (val result = enquiry.openOrders(warehouseId)) {
            is NetworkResult.Error -> _state.update {
                it.copy(busy = false, error = result.failure.message)
            }
            NetworkResult.Loading -> _state.update {
                it.copy(busy = false, error = "Lookup did not complete")
            }
            is NetworkResult.Success -> _state.update {
                it.copy(busy = false, orders = result.data)
            }
        }
    }
}

data class PoOfTodayState(
    val warehouseId: String? = null,
    val orders: List<PurchaseOrderSummary> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)
