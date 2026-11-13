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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Purchase orders by delivery date, like Transfer orders' direction chips (user, 13 Nov). */
enum class PoDueFilter(val label: String) { ALL("All"), OVERDUE("Overdue"), TODAY("Today"), LATER("Later") }

/** The day an order is due, in the device's zone; null when it has no delivery date. */
internal fun PurchaseOrderSummary.dueDay(zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
    deliveryDate?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

internal fun PurchaseOrderSummary.dueIn(filter: PoDueFilter, today: LocalDate = LocalDate.now()): Boolean {
    val day = dueDay()
    return when (filter) {
        PoDueFilter.ALL -> true
        PoDueFilter.OVERDUE -> day != null && day.isBefore(today)
        PoDueFilter.TODAY -> day == today
        PoDueFilter.LATER -> day == null || day.isAfter(today)
    }
}

data class PoOfTodayState(
    val warehouseId: String? = null,
    val orders: List<PurchaseOrderSummary> = emptyList(),
    val filter: PoDueFilter = PoDueFilter.ALL,
    val query: String = "",
    val busy: Boolean = false,
    val error: String? = null,
) {
    /** The orders under the chosen chip whose number or vendor holds the search text. */
    val visible: List<PurchaseOrderSummary>
        get() = orders.filter { it.dueIn(filter) }.filter {
            query.isBlank() || it.purchId.contains(query.trim(), ignoreCase = true) ||
                it.vendorAccount.contains(query.trim(), ignoreCase = true)
        }
}

/**
 * Every open purchase order of this worker's warehouse (user, 12 Nov: was today's only), soonest
 * delivery first, with the same search and chips as Transfer orders (user, 13 Nov). Loads itself.
 */
@HiltViewModel
class PoOfTodayViewModel @Inject constructor(
    private val enquiry: com.d365fo.mobileops.data.repository.EnquiryRepository,
    workerSession: WorkerSessionRepository,
    private val pendingFlowResume: com.d365fo.mobileops.data.repository.PendingFlowResume,
) : ViewModel() {

    /** Hands [purchId] to the receiving flow, which opens on it - the caller then navigates there. */
    fun startReceiving(purchId: String) =
        pendingFlowResume.set(com.d365fo.mobileops.data.local.entity.FlowType.RECEIVING, purchId)

    private val _state = MutableStateFlow(PoOfTodayState(warehouseId = workerSession.session.value?.selected?.warehouseId))
    val state: StateFlow<PoOfTodayState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val warehouseId = _state.value.warehouseId
        if (warehouseId.isNullOrBlank()) {
            _state.update { it.copy(busy = false, error = "No warehouse is assigned to you.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (val result = enquiry.openOrders(warehouseId)) {
                is NetworkResult.Error -> _state.update { it.copy(busy = false, error = result.failure.message) }
                NetworkResult.Loading -> _state.update { it.copy(busy = false, error = "Lookup did not complete") }
                is NetworkResult.Success -> _state.update { it.copy(busy = false, orders = result.data) }
            }
        }
    }

    fun setFilter(filter: PoDueFilter) = _state.update { it.copy(filter = filter) }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }
}
