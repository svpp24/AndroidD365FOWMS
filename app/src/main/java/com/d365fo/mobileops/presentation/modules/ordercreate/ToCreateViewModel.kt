package com.d365fo.mobileops.presentation.modules.ordercreate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.local.entity.CreatedOrderEntity
import com.d365fo.mobileops.data.local.entity.OrderKind
import com.d365fo.mobileops.data.repository.AssignedWarehouse
import com.d365fo.mobileops.data.repository.DraftLine
import com.d365fo.mobileops.data.repository.OrderCreationRepository
import com.d365fo.mobileops.data.repository.OrderItem
import com.d365fo.mobileops.data.repository.OrderVariant
import com.d365fo.mobileops.data.repository.StockRow
import com.d365fo.mobileops.data.repository.TransferDraft
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.sync.SyncStatusProvider
import com.d365fo.mobileops.domain.order.QuantityRules
import com.d365fo.mobileops.domain.order.checkQuantity
import com.d365fo.mobileops.domain.order.qtyText
import com.d365fo.mobileops.presentation.common.components.StatusKind
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.common.scan.RejectReason
import com.d365fo.mobileops.presentation.common.scan.ScanContext
import com.d365fo.mobileops.presentation.common.scan.ScanFeedback
import com.d365fo.mobileops.presentation.common.scan.ScanKind
import com.d365fo.mobileops.presentation.common.scan.ScanRoute
import com.d365fo.mobileops.presentation.common.scan.ScanRouter
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A line waiting for its variant and stock row (transfers and returns). */
data class PendingTransferLine(
    val item: OrderItem,
    val variant: OrderVariant? = null,
    /** Stock at From, when D365FO could be asked; null offline. */
    val stock: List<StockRow>? = null,
    val pick: StockRow? = null,
    /** Choosing a batch / serial from [stock]. */
    val choosingStock: Boolean = false,
)

data class ToCreateState(
    val draft: TransferDraft = TransferDraft(),
    val warehouses: List<AssignedWarehouse> = emptyList(),
    /** False while To is being chosen - Start (or the first item scan) begins. */
    val routeChosen: Boolean = false,
    val busy: Boolean = false,
    val notice: OrderNotice? = null,
    val choosingVariant: OrderItem? = null,
    val choosingStock: PendingTransferLine? = null,
    val lastAdd: LineAdd? = null,
    val showingLines: Boolean = false,
    /** The transfer just sent - the band follows it, and offers Ship it now once created. */
    val submittedId: String? = null,
)

/**
 * Create transfer, scan only (redesign brief §4, phase 7): From is the store the worker is in,
 * To is filled in when there is only one other; Start (or the first item scan) begins, each scan
 * adds one of the item from what is at From, and the transfer is created when the worker leaves
 * (Back, F1) - the screen stays to show it go, then **Ship it now** opens Ship on it.
 *
 * Online, the stock at From is asked per batch and serial: the only row is taken, several are a
 * real choice, a quantity above it is refused here. Offline it is not known and D365FO refuses a
 * short order when it is sent. The creation path - the draft, [OrderCreationRepository.submitTransfer],
 * the queue - is unchanged.
 */
@HiltViewModel
class ToCreateViewModel @Inject constructor(
    private val repository: OrderCreationRepository,
    private val sessions: WorkerSessionRepository,
    private val scannerBridge: HidScannerBridge,
    private val pendingFlowResume: com.d365fo.mobileops.data.repository.PendingFlowResume,
    syncStatusProvider: SyncStatusProvider,
    private val scanFeedback: ScanFeedback,
    private val appScope: CoroutineScope,
    scanRouter: ScanRouter,
) : ViewModel() {

    private val _state = MutableStateFlow(ToCreateState())
    val state: StateFlow<ToCreateState> = _state.asStateFlow()

    val online: StateFlow<Boolean> = syncStatusProvider.status.map { it.fullyOnline }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val orders: StateFlow<List<CreatedOrderEntity>> = repository.observeOrders(OrderKind.TRANSFER)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val userName: String get() = sessions.session.value?.userName.orEmpty()

    private val scanning = OrderFlowScanning(scanRouter, ::scanContext, ::onRoute)

    init {
        viewModelScope.launch {
            val session = sessions.session.value
            val warehouses = session?.warehouses.orEmpty()
            val saved = repository.loadTransferDraft(userName)
            val draft = (saved ?: TransferDraft(fromWarehouseId = session?.selected?.warehouseId.orEmpty()))
                .let { d -> if (d.toWarehouseId.isBlank()) d.copy(toWarehouseId = onlyOtherWarehouse(warehouses, d.fromWarehouseId)) else d }
            _state.update {
                it.copy(
                    draft = draft,
                    warehouses = warehouses,
                    routeChosen = draft.lines.isNotEmpty() && draft.fromWarehouseId.isNotBlank() && draft.toWarehouseId.isNotBlank(),
                )
            }
        }
    }

    // -- Scans -------------------------------------------------------------------------------------

    fun scannerOnScreen(visible: Boolean) = scanning.onScreen(visible)

    override fun onCleared() {
        scanning.close()
        super.onCleared()
    }

    private fun scanContext(): ScanContext = ScanContext.Flow(
        accepts = setOf(ScanKind.ITEM),
        acceptUnknown = true,
        expecting = "Scan an item to send",
    )

    private suspend fun onRoute(route: ScanRoute) {
        when (route) {
            is ScanRoute.Accept -> onCode(itemCodeFor(route.identity))
            is ScanRoute.Leave, is ScanRoute.SwitchDocument -> {
                scanFeedback.error()
                notice(StatusKind.Warn, "That's ${documentNoun(route.identity.kind)}", "Scan it on Home - this screen makes a new transfer")
            }
            is ScanRoute.Reject -> {
                scanFeedback.error()
                notice(if (route.reason == RejectReason.NOT_EXPECTED) StatusKind.Warn else StatusKind.Bad, route.title, route.line)
            }
            else -> Unit
        }
    }

    fun onCameraDecoded(value: String) = scannerBridge.submitCameraScan(value)

    fun onTyped(value: String) = scannerBridge.submitCameraScan(value)

    fun justScanned(): Boolean = scannerBridge.justScanned()

    private fun notice(kind: StatusKind, title: String, line: String? = null, undoable: Boolean = false) =
        _state.update { it.copy(notice = OrderNotice(kind, title, line, undoable), busy = false) }

    private fun refuse(title: String, line: String) {
        scanFeedback.error()
        notice(StatusKind.Bad, title, "$line - nothing was added")
    }

    // -- Route -------------------------------------------------------------------------------------

    /** The To warehouse when the worker has just one other - nothing to choose. */
    private fun onlyOtherWarehouse(warehouses: List<AssignedWarehouse>, from: String): String =
        warehouses.filter { it.warehouseId != from }.singleOrNull()?.warehouseId.orEmpty()

    fun pickTo(warehouseId: String) {
        if (_state.value.draft.lines.isNotEmpty()) return
        updateDraft { it.copy(toWarehouseId = warehouseId) }
    }

    /** Start: From and To set - scan items. */
    fun start(): Boolean {
        val d = _state.value.draft
        if (d.fromWarehouseId.isBlank() || d.toWarehouseId.isBlank() || d.fromWarehouseId == d.toWarehouseId) return false
        _state.update { it.copy(routeChosen = true, notice = null) }
        return true
    }

    fun changeRoute() {
        if (_state.value.draft.lines.isEmpty()) _state.update { it.copy(routeChosen = false, notice = null) }
    }

    // -- Lines -------------------------------------------------------------------------------------

    private suspend fun onCode(code: String) {
        val s = _state.value
        // The first item scan is Start, when To is known.
        if (!s.routeChosen && !start()) {
            scanFeedback.error()
            notice(StatusKind.Warn, "Choose where it goes first", "Tap the To store, then scan")
            return
        }
        if (s.choosingVariant != null || s.choosingStock != null) {
            scanFeedback.error()
            notice(StatusKind.Warn, "Pick one below first", "Or Undo to drop it")
            return
        }
        _state.update { it.copy(busy = true, notice = null) }
        when (val result = repository.findItem(code, _state.value.draft.fromWarehouseId)) {
            is NetworkResult.Success -> {
                val item = result.data
                val scanned = item.scannedVariant
                when {
                    !item.hasProductDimensions -> withVariant(item, OrderVariant())
                    scanned != null -> withVariant(item, scanned)
                    item.variants.isEmpty() -> refuse("No variants", "${item.itemId} has no released variants")
                    item.variants.size == 1 -> withVariant(item, item.variants.single())
                    else -> _state.update { it.copy(busy = false, choosingVariant = item) }
                }
            }
            is NetworkResult.Error -> refuse(
                "Item not found",
                if (!online.value) "$code isn't saved on this device - look it up online once" else result.failure.message,
            )
            NetworkResult.Loading -> Unit
        }
    }

    fun pickVariant(variant: OrderVariant) {
        val item = _state.value.choosingVariant ?: return
        _state.update { it.copy(choosingVariant = null, busy = true) }
        viewModelScope.launch { withVariant(item, variant) }
    }

    /** Asks D365FO what is at From (online); a tracked item takes its only batch / serial, or asks. */
    private suspend fun withVariant(item: OrderItem, variant: OrderVariant) {
        val from = _state.value.draft.fromWarehouseId
        val stock = if (online.value) {
            (repository.stock(item.itemId, variant, from) as? NetworkResult.Success)?.data
        } else {
            null
        }
        // An item allowed physical negative inventory may be transferred without stock on hand,
        // as D365FO allows - so neither this refusal nor the quantity cap applies to it.
        val withStock = stock?.filter { it.available > 0 }
        if (withStock != null && withStock.isEmpty() && !item.negativePhysical) {
            refuse("No stock at $from", "No ${item.itemId} ${variant.label} at $from".replace("  ", " "))
            return
        }
        val tracked = item.batchActive || item.serialActive
        val pending = PendingTransferLine(item, variant, stock)
        when {
            withStock != null && tracked && withStock.isNotEmpty() -> {
                val only = onlyStockRow(withStock)
                if (only != null) addLine(pending.copy(pick = only))
                else _state.update { it.copy(busy = false, choosingStock = pending.copy(stock = withStock, choosingStock = true)) }
            }
            else -> addLine(pending)
        }
    }

    fun pickStock(row: StockRow) {
        val pending = _state.value.choosingStock ?: return
        _state.update { it.copy(choosingStock = null) }
        addLine(pending.copy(pick = row, choosingStock = false))
    }

    /** What may still go: the stock picked (or all of it), less the rest of this transfer. */
    private fun availableLeft(itemId: String, variant: OrderVariant, batchId: String, serialId: String, available: Double?, excluding: String? = null): Double? {
        available ?: return null
        val already = _state.value.draft.lines
            .filter { it.id != excluding && it.itemId == itemId && it.variant == variant && it.batchId == batchId && it.serialId == serialId }
            .sumOf { it.qty }
        return (available - already).coerceAtLeast(0.0)
    }

    /** One scan, one unit - the scan is the confirm; the keypad changes it. */
    private fun addLine(pending: PendingTransferLine) {
        val variant = pending.variant ?: OrderVariant()
        val available = if (pending.item.negativePhysical) null else pending.pick?.available ?: pending.stock?.sumOf { it.available }
        val left = availableLeft(pending.item.itemId, variant, pending.pick?.batchId.orEmpty(), pending.pick?.serialId.orEmpty(), available)
        if (left != null && left < 1.0) {
            refuse("No more at ${_state.value.draft.fromWarehouseId}", "All ${qtyText(available ?: 0.0)} of ${pending.item.itemId} are on this transfer")
            return
        }
        val line = DraftLine(
            itemId = pending.item.itemId,
            itemName = pending.item.itemName,
            variant = variant,
            qty = 1.0,
            unitId = pending.item.inventUnit,
            batchId = pending.pick?.batchId.orEmpty(),
            serialId = pending.pick?.serialId.orEmpty(),
            available = pending.pick?.available ?: pending.stock?.sumOf { it.available },
            allowsNegative = pending.item.negativePhysical,
        )
        var added: LineAdd? = null
        var lineNow = line
        updateDraft { d ->
            val (lines, add) = addOrMerge(d.lines, line)
            added = add
            lineNow = lines.first { it.id == add.lineId }
            d.copy(lines = lines)
        }
        scanFeedback.success()
        _state.update {
            it.copy(
                busy = false,
                lastAdd = added,
                choosingVariant = null,
                choosingStock = null,
                notice = OrderNotice(StatusKind.Ok, "Added ${pending.item.itemId}", addedLine(lineNow, 1.0), undoable = true),
            )
        }
    }

    fun cancelChoice() = _state.update { it.copy(choosingVariant = null, choosingStock = null, notice = null) }

    fun undo() {
        val s = _state.value
        when {
            s.choosingVariant != null || s.choosingStock != null -> cancelChoice()
            s.draft.lines.isNotEmpty() -> {
                val gone = lineInHand(s.draft.lines, s.lastAdd)
                updateDraft { d -> d.copy(lines = undoAdd(d.lines, s.lastAdd)) }
                _state.update { it.copy(lastAdd = null, notice = OrderNotice(StatusKind.Info, "Undone", "${gone?.itemId.orEmpty()} taken off - scan it again".trim())) }
            }
        }
    }

    val canUndo: Boolean
        get() = _state.value.let { it.choosingVariant != null || it.choosingStock != null || it.draft.lines.isNotEmpty() }

    fun keypadLine(): DraftLine? = _state.value.let {
        if (it.choosingVariant == null && it.choosingStock == null) lineInHand(it.draft.lines, it.lastAdd) else null
    }

    fun keypadRules(line: DraftLine): QuantityRules {
        val available = if (line.allowsNegative) null else line.available
        return QuantityRules(available = availableLeft(line.itemId, line.variant, line.batchId, line.serialId, available, excluding = line.id))
    }

    fun keypadError(line: DraftLine, value: String): String? {
        val qty = value.replace(',', '.').toDoubleOrNull() ?: return "Enter a number"
        return checkQuantity(qty, keypadRules(line))
    }

    fun changeQuantity(line: DraftLine, value: String) {
        if (keypadError(line, value) != null) return
        val qty = value.replace(',', '.').toDouble()
        updateDraft { d -> d.copy(lines = d.lines.map { if (it.id == line.id) it.copy(qty = qty) else it }) }
        _state.update { it.copy(lastAdd = LineAdd(line.id, qty), notice = OrderNotice(StatusKind.Ok, "${line.itemId} changed", "${qtyText(qty)} ${line.unitId}".trim())) }
    }

    fun removeLine(line: DraftLine) {
        updateDraft { d -> d.copy(lines = d.lines.filterNot { it.id == line.id }) }
        _state.update { it.copy(lastAdd = null, notice = OrderNotice(StatusKind.Info, "${line.itemId} removed")) }
    }

    fun showLines(show: Boolean) = _state.update { it.copy(showingLines = show) }

    // -- Create (on leave) -------------------------------------------------------------------------

    val hasOrderToSend: Boolean get() = _state.value.draft.lines.isNotEmpty()

    /** Creates the transfer: saved and queued (the existing path), on the app's scope. */
    fun submit() {
        val s = _state.value
        val draft = s.draft
        if (draft.lines.isEmpty() || s.busy) return
        _state.update { it.copy(busy = true, notice = null, showingLines = false, choosingStock = null, choosingVariant = null) }
        appScope.launch {
            val id = repository.submitTransfer(userName, draft)
            _state.update {
                it.copy(
                    draft = TransferDraft(fromWarehouseId = draft.fromWarehouseId, toWarehouseId = draft.toWarehouseId),
                    routeChosen = false,
                    busy = false,
                    lastAdd = null,
                    submittedId = id,
                )
            }
        }
    }

    /** Hands the created order to Transfer - Ship; the caller navigates there. */
    fun ship(transferId: String) =
        pendingFlowResume.set(com.d365fo.mobileops.data.local.entity.FlowType.TRANSFER_SHIP, transferId)

    private fun updateDraft(change: (TransferDraft) -> TransferDraft) {
        _state.update { it.copy(draft = change(it.draft)) }
        val draft = _state.value.draft
        viewModelScope.launch {
            if (draft.lines.isEmpty()) repository.discardDraft(OrderKind.TRANSFER, userName)
            else repository.saveTransferDraft(userName, draft)
        }
    }
}
