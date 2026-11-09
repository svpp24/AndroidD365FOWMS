package com.d365fo.mobileops.presentation.modules.stepflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.domain.repository.OnDemandLookupRepository
import com.d365fo.mobileops.domain.repository.OnHandLine
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.domain.repository.WarehouseOption
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.common.scan.ScanContext
import com.d365fo.mobileops.presentation.common.scan.ScanFeedback
import com.d365fo.mobileops.presentation.common.scan.ScanIdentity
import com.d365fo.mobileops.presentation.common.scan.ScanKind
import com.d365fo.mobileops.presentation.common.scan.ScanRoute
import com.d365fo.mobileops.presentation.common.scan.ScanRouter
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The item Home's scan opened On-hand for (redesign phase 8) - handed over the way
 * [com.d365fo.mobileops.data.repository.PendingFlowResume] hands an order to a flow, and taken
 * once by the On-hand screen it opened.
 */
@Singleton
class PendingOnHandItem @Inject constructor() {
    @Volatile private var pending: String? = null

    fun set(itemId: String) {
        pending = itemId
    }

    /** Returns and clears the item - null when Home handed none over. */
    fun consume(): String? = pending.also { pending = null }
}

/** On-demand on-hand enquiry: one scan, one query, no cache. */
@HiltViewModel
class OnHandInquiryViewModel @Inject constructor(
    private val lookups: OnDemandLookupRepository,
    private val workerSession: WorkerSessionRepository,
    private val scannerBridge: HidScannerBridge,
    private val scanFeedback: ScanFeedback,
    private val scanRouter: ScanRouter,
    pendingItem: PendingOnHandItem,
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
        // Scanned on Home: look it up straight away, as if scanned here.
        pendingItem.consume()?.let(::lookUp)
    }

    // -- scans (redesign phase 8: through ScanRouter) ------------------------------------------

    /**
     * Scans arrive through [ScanRouter] while On-hand or its typing screen is in front - both
     * register, as receiving's do. Items are what this screen is for; a barcode the device
     * doesn't know still goes to the online lookup (it may be an item not synced yet).
     */
    private var scanHandle: ScanRouter.Handle? = null
    private var scanScreens = 0

    fun scannerOnScreen(visible: Boolean) {
        if (visible) {
            if (scanScreens++ == 0) scanHandle = scanRouter.attach(SCAN_CONTEXT, ::onRoute)
        } else if (scanScreens > 0 && --scanScreens == 0) {
            scanHandle?.close()
            scanHandle = null
        }
    }

    override fun onCleared() {
        scanHandle?.close()
        super.onCleared()
    }

    private fun onRoute(route: ScanRoute) {
        when (route) {
            is ScanRoute.Accept -> when (val id = route.identity) {
                is ScanIdentity.Item -> lookUp(id.item.itemId)
                else -> lookUp(id.raw.value)
            }
            // A document: it opens from Home, not here.
            is ScanRoute.Leave -> notice(
                "That's " + when (route.identity.kind) {
                    ScanKind.PURCHASE_ORDER -> "a purchase order"
                    ScanKind.TRANSFER_ORDER -> "a transfer order"
                    else -> "a counting journal"
                },
                "Scan an item here - scan orders on Home",
            )
            is ScanRoute.Reject -> notice(route.title, route.line)
            else -> Unit
        }
    }

    private fun notice(title: String, line: String) {
        scanFeedback.warning()
        _state.update { it.copy(notice = OnHandNotice(title, line), error = null) }
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
            _state.update { it.copy(busy = true, error = null, notice = null) }

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

/** A scan this screen doesn't take - shown as a Warn band. */
data class OnHandNotice(val title: String, val line: String)

/** The figures the On-hand screen leads with, summed over the rows shown. */
data class OnHandTotals(val available: BigDecimal, val onHand: BigDecimal, val reserved: BigDecimal)

/** Sums [rows] - one per warehouse - into the screen's headline figures. */
fun onHandTotals(rows: List<OnHandLine>): OnHandTotals = OnHandTotals(
    available = rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.available },
    onHand = rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.onHand },
    reserved = rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.reserved },
)

/** A quantity as a worker reads it: no trailing zeros, no exponent. */
fun formatOnHandQty(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

private val SCAN_CONTEXT = ScanContext.Flow(
    accepts = setOf(ScanKind.ITEM),
    acceptUnknown = true,
    expecting = "Scan an item",
)

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
    val notice: OnHandNotice? = null,
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
