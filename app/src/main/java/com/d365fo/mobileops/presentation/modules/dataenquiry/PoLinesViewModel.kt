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

        // A large order's lines come per item (see OrderLookup.linesInline): with one of those
        // open, a scan is first tried as an item on it, and only then as another order.
        val open = _state.value
        if (open.purchId != null && !open.linesInline && open.outstandingLineCount > 0) {
            pending?.cancel()
            pending = viewModelScope.launch {
                _state.update { it.copy(busy = true, error = null) }
                val forItem = lookups.lookupLinesForItem(open.purchId, purchId)
                when {
                    forItem is NetworkResult.Success && forItem.data.isNotEmpty() -> {
                        scanFeedback.success()
                        _state.update { it.copy(busy = false, lines = forItem.data, itemFilter = purchId) }
                    }
                    // No connection: keep the order open rather than trying the scan as a PO.
                    forItem is NetworkResult.Error && forItem.failure.retryable -> {
                        scanFeedback.error()
                        _state.update {
                            it.copy(
                                busy = false,
                                error = "Offline - can't look up $purchId",
                            )
                        }
                    }
                    else -> lookUpOrder(purchId)
                }
            }
            return
        }

        pending?.cancel()
        pending = viewModelScope.launch { lookUpOrder(purchId) }
    }

    private suspend fun lookUpOrder(purchId: String) {
        _state.update { it.copy(busy = true, error = null) }

        when (val result = lookups.lookupOrder(purchId)) {
            is NetworkResult.Error -> {
                scanFeedback.error()
                _state.update {
                    it.copy(
                        busy = false,
                        error = result.failure.message,
                        purchId = null,
                        lines = emptyList(),
                        blockNotice = null,
                    )
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
                        outstandingLineCount = result.data.outstandingLineCount,
                        linesInline = result.data.linesInline,
                        blockNotice = result.data.blockReason?.let(::blockNotice),
                        itemFilter = null,
                    )
                }
            }
        }
    }
}

data class PoLinesState(
    val purchId: String? = null,
    val vendorAccount: String? = null,
    val lines: List<PurchaseOrderLine> = emptyList(),
    /** Open lines on the order, whether or not [lines] holds them. */
    val outstandingLineCount: Int = 0,
    /**
     * False when the order's lines were not sent with it: they are shown per scanned item,
     * [itemFilter]. Usually a large order - but an older AOS also withholds them from a blocked
     * order of any size, so with [blockNotice] set this says nothing about size.
     */
    val linesInline: Boolean = true,
    /**
     * Why the order cannot be received yet, in words, or null when it can. Shown alongside the
     * lines: this screen only reads, but an operator looking at an unconfirmed order should not
     * come away thinking the goods can go in against it.
     */
    val blockNotice: String? = null,
    val itemFilter: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** [com.d365fo.mobileops.domain.repository.OrderLookup.blockReason], for the operator. */
internal fun blockNotice(reason: String): String = when (reason) {
    "NOT_CONFIRMED" -> "Not confirmed yet."
    "NOT_APPROVED" -> "Not approved yet."
    "NOT_OPEN" -> "Not open for receiving."
    else -> "Can't be received yet ($reason)."
}
