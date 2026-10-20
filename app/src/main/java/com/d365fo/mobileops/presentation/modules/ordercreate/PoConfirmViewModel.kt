package com.d365fo.mobileops.presentation.modules.ordercreate

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.local.entity.CreatedOrderEntity
import com.d365fo.mobileops.data.local.entity.CreatedOrderStatus
import com.d365fo.mobileops.data.local.entity.OrderKind
import com.d365fo.mobileops.data.repository.OrderCreationRepository
import com.d365fo.mobileops.data.repository.PurchaseOrderDetail
import com.d365fo.mobileops.data.repository.PurchaseOrderSummary
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.sync.SyncStatusProvider
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PoConfirmState(
    val warehouseId: String = "",
    val loading: Boolean = false,
    val orders: List<PurchaseOrderSummary> = emptyList(),
    val listError: String? = null,
    /** An order being opened (scan, typed or tapped). */
    val opening: Boolean = false,
    val detail: PurchaseOrderDetail? = null,
    val error: String? = null,
    val askConfirm: Boolean = false,
    /** The confirmation just submitted - its card follows it from waiting to confirmed. */
    val submittedId: String? = null,
)

/**
 * PO confirmation: the approved, unconfirmed purchase orders of the worker's warehouse (or one
 * scanned or typed), each opened with its lines and D365FO's prices, then confirmed. The
 * confirmation is queued like a created order - offline it is sent when D365FO can be reached -
 * and D365FO returns the confirmation number.
 */
@HiltViewModel
class PoConfirmViewModel @Inject constructor(
    private val repository: OrderCreationRepository,
    private val sessions: WorkerSessionRepository,
    private val scannerBridge: HidScannerBridge,
    savedStateHandle: SavedStateHandle,
    syncStatusProvider: SyncStatusProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(PoConfirmState())
    val state: StateFlow<PoConfirmState> = _state.asStateFlow()

    val online: StateFlow<Boolean> = syncStatusProvider.status.map { it.fullyOnline }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Confirmations made on this device, newest first. */
    val confirmations: StateFlow<List<CreatedOrderEntity>> = repository.observeOrders(OrderKind.CONFIRMATION)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val userName: String get() = sessions.session.value?.userName.orEmpty()
    private var opening: Job? = null

    init {
        _state.update { it.copy(warehouseId = sessions.session.value?.selected?.warehouseId.orEmpty()) }
        refresh()
        savedStateHandle.get<String>("purchId")?.takeIf { it.isNotBlank() }?.let { open(it) }
        viewModelScope.launch { scannerBridge.scans.collect { open(it.value) } }
    }

    fun onCameraDecoded(value: String) = scannerBridge.submitCameraScan(value)

    fun refresh() {
        val warehouse = _state.value.warehouseId
        if (warehouse.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, listError = null) }
            when (val result = repository.purchaseOrdersToConfirm(userName, warehouse)) {
                is NetworkResult.Success -> _state.update { it.copy(loading = false, orders = result.data) }
                is NetworkResult.Error -> _state.update {
                    it.copy(
                        loading = false,
                        listError = if (result.failure.retryable) {
                            "Can't reach D365FO - the list shows once it can be reached. A PO you scan is still shown if it was opened here before."
                        } else {
                            result.failure.message
                        },
                    )
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    /** Opens a purchase order - from the list, a scan or typed. */
    fun open(purchId: String) {
        val id = purchId.trim()
        if (id.isEmpty() || _state.value.askConfirm) return
        opening?.cancel()
        opening = viewModelScope.launch {
            _state.update { it.copy(opening = true, error = null) }
            when (val result = repository.purchaseOrder(userName, id)) {
                is NetworkResult.Success -> _state.update { it.copy(opening = false, detail = result.data) }
                is NetworkResult.Error -> _state.update {
                    it.copy(
                        opening = false,
                        error = if (result.failure.retryable) {
                            "Can't reach D365FO to open $id. A PO opened here before can be confirmed offline."
                        } else {
                            result.failure.message
                        },
                    )
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun closeDetail() = _state.update { it.copy(detail = null, askConfirm = false) }

    fun askConfirm() = _state.update { it.copy(askConfirm = it.detail?.summary?.confirmable == true) }

    fun cancelConfirm() = _state.update { it.copy(askConfirm = false) }

    fun confirm() {
        val summary = _state.value.detail?.summary ?: return
        if (!summary.confirmable) return
        viewModelScope.launch {
            _state.update { it.copy(askConfirm = false) }
            val id = repository.submitConfirmation(userName, summary)
            _state.update { s ->
                s.copy(detail = null, submittedId = id, orders = s.orders.filterNot { it.purchId == summary.purchId })
            }
        }
    }

    /** A confirmation of [purchId] already made on this device that has not been refused. */
    fun pendingOrDone(purchId: String, confirmations: List<CreatedOrderEntity>): CreatedOrderEntity? =
        confirmations.firstOrNull { it.documentNumber == purchId && it.status != CreatedOrderStatus.FAILED }

    fun dismissResult() = _state.update { it.copy(submittedId = null) }

    fun dismissError() = _state.update { it.copy(error = null) }
}
