package com.d365fo.mobileops.presentation.modules.stepflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.domain.repository.OnDemandLookupRepository
import com.d365fo.mobileops.domain.repository.OnHandLine
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.domain.repository.WarehouseOption
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.common.scan.ScanFeedback
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** On-demand on-hand enquiry: one scan, one query, no cache. */
@HiltViewModel
class OnHandInquiryViewModel @Inject constructor(
    private val lookups: OnDemandLookupRepository,
    private val workerSession: WorkerSessionRepository,
    private val scannerBridge: HidScannerBridge,
    private val scanFeedback: ScanFeedback,
) : ViewModel() {

    private val _state = MutableStateFlow(OnHandInquiryState())
    val state: StateFlow<OnHandInquiryState> = _state.asStateFlow()

    private var pending: Job? = null

    init {
        // The warehouse comes from the signed-in worker, not from the device.
        //
        // It used to read WorkerContextRepository — the *device* context — which has been empty
        // since device registration was removed. A null warehouse there silently disabled the
        // "this warehouse only" filter: the toggle moved, and the query it produced was
        // identical either way.
        viewModelScope.launch {
            workerSession.session.collect { session ->
                _state.update {
                    it.copy(homeWarehouseId = session?.selected?.warehouseId)
                }
            }
        }
        viewModelScope.launch {
            scannerBridge.scans.collect { lookUp(it.value) }
        }
    }

    fun onCameraDecoded(value: String) = scannerBridge.submitCameraScan(value)

    fun setScannerInterception(enabled: Boolean) {
        scannerBridge.enabled = enabled
    }

    fun setThisWarehouseOnly(enabled: Boolean) {
        _state.update {
            // Turning the filter back on drops any warehouse that was picked by hand, so the
            // switch always means the same thing rather than depending on what came before.
            it.copy(thisWarehouseOnly = enabled, pickedWarehouseId = null)
        }
        if (!enabled) loadWarehouses()
        // Re-run against the item already on screen rather than making the operator scan
        // again: they flipped the switch precisely to see the same item a different way.
        _state.value.itemNumber?.let { lookUp(it) }
    }

    fun openWarehousePicker() {
        _state.update { it.copy(pickerOpen = true) }
        loadWarehouses()
    }

    fun dismissWarehousePicker() {
        _state.update { it.copy(pickerOpen = false) }
    }

    /** Null means every warehouse. */
    fun pickWarehouse(warehouseId: String?) {
        _state.update { it.copy(pickedWarehouseId = warehouseId, pickerOpen = false) }
        _state.value.itemNumber?.let { lookUp(it) }
    }

    /**
     * Fetches the warehouse list once and keeps it.
     *
     * Not fetched at construction: most enquiries never open the picker, and a list of every
     * warehouse in the company is a round trip that would otherwise be paid on every visit to
     * the screen for a filter nobody touched.
     */
    private fun loadWarehouses() {
        if (_state.value.warehouses.isNotEmpty() || _state.value.loadingWarehouses) return
        viewModelScope.launch {
            _state.update { it.copy(loadingWarehouses = true) }
            val result = lookups.listWarehouses()
            _state.update {
                it.copy(
                    loadingWarehouses = false,
                    warehouses = (result as? NetworkResult.Success)?.data.orEmpty(),
                )
            }
        }
    }

    fun lookUp(scanned: String) {
        val value = scanned.trim()
        if (value.isEmpty()) return

        pending?.cancel()
        pending = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }

            val warehouse = _state.value.effectiveWarehouseId

            // Both questions go out together, on the assumption the scan is an item number.
            //
            // It nearly always is, and the two calls are independent: the quantities do not
            // depend on the product name. Run in sequence they cost the sum of both round trips
            // — and the operator waits on a name they can already read off the box before seeing
            // the number they came for. Run together they cost the slower of the two.
            val facts = async { lookups.lookupItem(value) }
            val onHand = async { lookups.lookupOnHand(value, warehouse) }

            val itemResult = facts.await()
            if (itemResult is NetworkResult.Error) {
                onHand.cancel()
                return@launch finishWith(itemResult.failure.message)
            }

            // Presence of the record decides this, not the name. An item with a blank search
            // name is still an item, and treating it as a barcode sent it down a lookup that
            // could only ever fail.
            val item = (itemResult as? NetworkResult.Success)?.data
            if (item != null) {
                return@launch show(value, item.productName, onHand.await())
            }

            // The guess was wrong, so the on-hand call in flight is asking about an item that
            // does not exist. Drop it and resolve the barcode instead. The wasted call is the
            // price of the common case being one round trip instead of two.
            onHand.cancel()

            when (val match = lookups.lookupBarcode(value)) {
                is NetworkResult.Error -> finishWith(match.failure.message)
                NetworkResult.Loading -> finishWith("Lookup did not complete")
                is NetworkResult.Success -> {
                    val resolved = match.data
                        ?: return@launch finishWith("$value is not a known item or barcode")

                    val itemNumber = resolved.itemNumber
                    val name = async { lookups.lookupItem(itemNumber) }
                    val rows = async { lookups.lookupOnHand(itemNumber, warehouse) }

                    show(
                        itemNumber,
                        (name.await() as? NetworkResult.Success)?.data?.productName,
                        rows.await(),
                    )
                }
            }
        }
    }

    private fun show(
        itemNumber: String,
        productName: String?,
        result: NetworkResult<List<OnHandLine>>,
    ) {
        when (result) {
            is NetworkResult.Error -> finishWith(result.failure.message)
            NetworkResult.Loading -> finishWith("Lookup did not complete")
            is NetworkResult.Success -> {
                scanFeedback.success()
                _state.update {
                    it.copy(
                        busy = false,
                        error = null,
                        itemNumber = itemNumber,
                        productName = productName,
                        rows = result.data,
                    )
                }
            }
        }
    }

    /** Clears the rows as well as showing the message: a stale table under an error reads as fact. */
    private fun finishWith(message: String) {
        scanFeedback.error()
        _state.update { it.copy(busy = false, error = message, rows = emptyList()) }
    }
}

data class OnHandInquiryState(
    val itemNumber: String? = null,
    val productName: String? = null,
    val rows: List<OnHandLine> = emptyList(),
    /** The warehouse the worker signed into. Null until the session arrives. */
    val homeWarehouseId: String? = null,
    /** Chosen by hand from the picker while the filter is off. Null means every warehouse. */
    val pickedWarehouseId: String? = null,
    val thisWarehouseOnly: Boolean = true,
    val warehouses: List<WarehouseOption> = emptyList(),
    val loadingWarehouses: Boolean = false,
    val pickerOpen: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    /**
     * The warehouse the query is actually filtered by, or null for all of them.
     *
     * One place decides this, so the switch, the picker and the query cannot disagree about
     * what is being shown — which is the failure that makes an operator distrust the numbers.
     */
    val effectiveWarehouseId: String?
        get() = if (thisWarehouseOnly) homeWarehouseId else pickedWarehouseId

    /** What the screen says it is showing. */
    val scopeLabel: String
        get() = when {
            thisWarehouseOnly && homeWarehouseId != null -> homeWarehouseId
            thisWarehouseOnly -> "No warehouse — showing all"
            pickedWarehouseId != null ->
                warehouses.firstOrNull { it.warehouseId == pickedWarehouseId }?.label
                    ?: pickedWarehouseId
            else -> "All warehouses"
        }
}
