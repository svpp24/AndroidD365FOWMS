package com.d365fo.mobileops.presentation.modules.dataenquiry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.domain.model.PurchaseOrderLine
import com.d365fo.mobileops.domain.repository.OnDemandLookupRepository
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.common.scan.ScanFeedback
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Read-only view onto [OnDemandLookupRepository.lookupOrder] - scan or type a PO number, see
 * its lines. Everything here already exists for Receiving; this just shows it without starting
 * a receipt.
 */
@HiltViewModel
class PoLinesViewModel @Inject constructor(
    private val lookups: OnDemandLookupRepository,
    private val scannerBridge: HidScannerBridge,
    private val scanFeedback: ScanFeedback,
) : ViewModel() {

    private val _state = MutableStateFlow(PoLinesState())
    val state: StateFlow<PoLinesState> = _state.asStateFlow()

    private var pending: Job? = null

    init {
        viewModelScope.launch {
            scannerBridge.scans.collect { lookUp(it.value) }
        }
    }

    fun onCameraDecoded(value: String) = scannerBridge.submitCameraScan(value)

    fun setScannerInterception(enabled: Boolean) {
        scannerBridge.enabled = enabled
    }

    fun lookUp(scanned: String) {
        val purchId = scanned.trim()
        if (purchId.isEmpty()) return

        pending?.cancel()
        pending = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }

            when (val result = lookups.lookupOrder(purchId)) {
                is NetworkResult.Error -> {
                    scanFeedback.error()
                    _state.update {
                        it.copy(busy = false, error = result.failure.message, purchId = null, lines = emptyList())
                    }
                }
                NetworkResult.Loading -> _state.update {
                    it.copy(busy = false, error = "Lookup did not complete")
                }
                is NetworkResult.Success -> {
                    scanFeedback.success()
                    _state.update {
                        it.copy(
                            busy = false,
                            purchId = purchId,
                            vendorAccount = result.data.order.vendorAccount,
                            lines = result.data.order.lines,
                        )
                    }
                }
            }
        }
    }
}

data class PoLinesState(
    val purchId: String? = null,
    val vendorAccount: String? = null,
    val lines: List<PurchaseOrderLine> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)
