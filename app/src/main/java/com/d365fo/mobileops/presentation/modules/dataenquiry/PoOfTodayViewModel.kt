package com.d365fo.mobileops.presentation.modules.dataenquiry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.domain.repository.OnDemandLookupRepository
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
 * Purchase orders requested for delivery today, in this worker's warehouse.
 *
 * Unlike every other lookup in this package, this one loads itself - there is nothing to scan,
 * the question is "what's due today", not "tell me about the thing in my hand".
 */
@HiltViewModel
class PoOfTodayViewModel @Inject constructor(
    private val lookups: OnDemandLookupRepository,
    private val workerSession: WorkerSessionRepository,
) : ViewModel() {

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
        _state.update { it.copy(busy = true, error = null) }
        when (val result = lookups.lookupOrdersForToday(warehouseId)) {
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
