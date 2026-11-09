package com.d365fo.mobileops.presentation.modules.ordercreate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.local.entity.CreatedOrderEntity
import com.d365fo.mobileops.data.local.entity.OrderKind
import com.d365fo.mobileops.data.local.entity.VendorEntity
import com.d365fo.mobileops.data.repository.DraftLine
import com.d365fo.mobileops.data.repository.OrderCreationRepository
import com.d365fo.mobileops.data.repository.OrderItem
import com.d365fo.mobileops.data.repository.OrderVariant
import com.d365fo.mobileops.data.repository.ReturnDraft
import com.d365fo.mobileops.data.repository.ReturnReason
import com.d365fo.mobileops.data.repository.StockRow
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
import com.d365fo.mobileops.presentation.common.scan.ScanIdentity
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

data class ReturnCreateState(
    val draft: ReturnDraft = ReturnDraft(),
    /** The original purchase order scanned - the vendor came from it. */
    val sourcePo: String? = null,
    val vendorQuery: String = "",
    val vendors: List<VendorEntity> = emptyList(),
    val vendorCount: Int = 0,
    /** Picking the vendor from the list instead of scanning the PO (⋮). */
    val pickingVendor: Boolean = false,
    val reasons: List<ReturnReason> = emptyList(),
    val actions: List<ReturnReason> = emptyList(),
    /** Changing the reason and action (⋮, or when there is no last-used one to default to). */
    val pickingCodes: Boolean = false,
    val busy: Boolean = false,
    val notice: OrderNotice? = null,
    val choosingVariant: OrderItem? = null,
    /** Several locations / batches hold the item - which one it comes from. */
    val choosingStock: PendingTransferLine? = null,
    val lastAdd: LineAdd? = null,
    val showingLines: Boolean = false,
    val editingRma: Boolean = false,
    /** The return just posted - the band follows it. */
    val submittedId: String? = null,
) {
    /** What still stops the return being posted, or null when it can go. */
    val missing: String?
        get() = when {
            draft.lines.isEmpty() -> "Scan what goes back"
            reasons.isNotEmpty() && draft.reasonCode.isBlank() -> "Pick a return reason"
            actions.isNotEmpty() && draft.actionCode.isBlank() -> "Pick a return action"
            else -> null
        }

    val reasonLabel: String? get() = reasons.firstOrNull { it.code == draft.reasonCode }?.label ?: draft.reasonCode.ifBlank { null }
    val actionLabel: String? get() = actions.firstOrNull { it.code == draft.actionCode }?.label ?: draft.actionCode.ifBlank { null }
}

/**
 * Return to vendor, scan only (redesign brief §4, phase 7): scan the original purchase order -
 * its vendor is the return's - with the reason and action the worker used last; then each item
 * scan takes one from the only location holding it (a real choice is asked only when several
 * do), and **Hold to post return** sends it. D365FO creates the return order, confirms it and
 * posts its packing slip (HSMobileOpsOrderCreateService::createPurchaseReturn) - that path, the
 * draft and the queue are unchanged.
 */
@HiltViewModel
class ReturnCreateViewModel @Inject constructor(
    private val repository: OrderCreationRepository,
    private val sessions: WorkerSessionRepository,
    private val scannerBridge: HidScannerBridge,
    syncStatusProvider: SyncStatusProvider,
    private val freezes: com.d365fo.mobileops.data.repository.WarehouseFreezeRepository,
    private val scanFeedback: ScanFeedback,
    private val memory: OrderCreateMemory,
    private val appScope: CoroutineScope,
    scanRouter: ScanRouter,
) : ViewModel() {

    private val _state = MutableStateFlow(ReturnCreateState())
    val state: StateFlow<ReturnCreateState> = _state.asStateFlow()

    val online: StateFlow<Boolean> = syncStatusProvider.status.map { it.fullyOnline }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val orders: StateFlow<List<CreatedOrderEntity>> = repository.observeOrders(OrderKind.RETURN)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val userName: String get() = sessions.session.value?.userName.orEmpty()

    private val scanning = OrderFlowScanning(scanRouter, ::scanContext, ::onRoute)

    init {
        viewModelScope.launch {
            val warehouse = sessions.session.value?.selected?.warehouseId.orEmpty()
            val saved = repository.loadReturnDraft(userName)
            _state.update { it.copy(draft = (saved ?: ReturnDraft()).let { d -> if (d.warehouseId.isBlank()) d.copy(warehouseId = warehouse) else d }) }
            loadVendors()
            loadReasons()
            repository.refreshVendors(force = false)
            loadVendors()
        }
    }

    // -- Scans -------------------------------------------------------------------------------------

    fun scannerOnScreen(visible: Boolean) = scanning.onScreen(visible)

    override fun onCleared() {
        scanning.close()
        super.onCleared()
    }

    /** The original PO first; then items - another PO switches the vendor while nothing is scanned. */
    private fun scanContext(): ScanContext {
        val s = _state.value
        return if (s.draft.vendorAccount.isBlank()) {
            ScanContext.Flow(
                accepts = setOf(ScanKind.PURCHASE_ORDER),
                documentKind = ScanKind.PURCHASE_ORDER,
                // A PO number without its prefix still reaches the lookup.
                acceptUnknown = true,
                expecting = "Scan the original purchase order",
            )
        } else {
            ScanContext.Flow(
                accepts = setOf(ScanKind.ITEM),
                documentKind = ScanKind.PURCHASE_ORDER,
                currentDocument = s.sourcePo,
                acceptUnknown = true,
                expecting = "Scan an item to return",
            )
        }
    }

    private suspend fun onRoute(route: ScanRoute) {
        val s = _state.value
        when (route) {
            is ScanRoute.Accept -> when {
                s.draft.vendorAccount.isBlank() -> vendorFromPurchaseOrder(itemCodeFor(route.identity).let { (route.identity as? ScanIdentity.Document)?.documentId ?: it })
                route.identity is ScanIdentity.Document && route.identity.documentId.equals(s.sourcePo, ignoreCase = true) ->
                    notice(StatusKind.Info, "Already returning against ${route.identity.documentId}", "Scan the items that go back")
                route.identity is ScanIdentity.Document -> otherPurchaseOrder(route.identity.documentId)
                else -> onItem(itemCodeFor(route.identity))
            }
            is ScanRoute.SwitchDocument -> otherPurchaseOrder(route.documentId)
            is ScanRoute.Leave -> {
                scanFeedback.error()
                notice(StatusKind.Warn, "That's ${documentNoun(route.identity.kind)}", "Scan it on Home - this screen returns stock")
            }
            is ScanRoute.Reject -> {
                scanFeedback.error()
                notice(if (route.reason == RejectReason.NOT_EXPECTED) StatusKind.Warn else StatusKind.Bad, route.title, route.line)
            }
            else -> Unit
        }
    }

    /** Another PO: its vendor while nothing is scanned yet; with lines, this return posts first. */
    private suspend fun otherPurchaseOrder(purchId: String) {
        val s = _state.value
        if (s.draft.lines.isEmpty()) {
            vendorFromPurchaseOrder(purchId)
        } else {
            scanFeedback.error()
            notice(StatusKind.Warn, "Post this return first", "${linesWord(s.draft.lines.size)} for ${s.draft.vendorAccount} - hold to post, then scan $purchId")
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

    // -- Vendor, reason, action ----------------------------------------------------------------------

    private var vendorSearch: kotlinx.coroutines.Job? = null

    fun onVendorQuery(value: String) {
        _state.update { it.copy(vendorQuery = value) }
        vendorSearch?.cancel()
        vendorSearch = viewModelScope.launch { loadVendors() }
    }

    private suspend fun loadVendors() {
        val query = _state.value.vendorQuery
        val list = repository.searchVendors(query)
        if (_state.value.vendorQuery != query) return
        _state.update { it.copy(vendors = list, vendorCount = repository.vendorCount()) }
    }

    /** D365FO's reasons and actions; the draft's, else the last used, else the only one, filled in. */
    private suspend fun loadReasons() {
        val result = repository.returnCodes()
        if (result is NetworkResult.Success) {
            val codes = result.data
            _state.update { s ->
                s.copy(
                    reasons = codes.reasons,
                    actions = codes.actions,
                    draft = s.draft.copy(
                        reasonCode = defaultCode(s.draft.reasonCode, memory.lastReturnReason(userName), codes.reasons),
                        actionCode = defaultCode(s.draft.actionCode, memory.lastReturnAction(userName), codes.actions),
                    ),
                )
            }
        }
    }

    fun showVendorList(show: Boolean) = _state.update { it.copy(pickingVendor = show, vendorQuery = if (show) it.vendorQuery else "") }

    fun pickVendor(vendor: VendorEntity) {
        updateDraft { it.copy(vendorAccount = vendor.accountNum, vendorName = vendor.name) }
        _state.update {
            it.copy(pickingVendor = false, sourcePo = null, notice = OrderNotice(StatusKind.Info, "${vendor.accountNum} ${vendor.name}".trim(), "Scan the items that go back"))
        }
    }

    fun changeVendor() {
        if (_state.value.draft.lines.isNotEmpty()) return
        updateDraft { it.copy(vendorAccount = "", vendorName = "") }
        _state.update { it.copy(sourcePo = null, notice = null) }
    }

    fun showCodes(show: Boolean) = _state.update { it.copy(pickingCodes = show) }

    /** A tap picks; the list stays until Back (opened from ⋮) or until nothing is missing. */
    fun pickReason(code: String) = updateDraft { it.copy(reasonCode = code) }

    fun pickAction(code: String) = updateDraft { it.copy(actionCode = code) }

    fun editRma(open: Boolean) = _state.update { it.copy(editingRma = open) }

    fun setRma(value: String) {
        _state.update { it.copy(editingRma = false) }
        updateDraft { it.copy(rmaNumber = value.trim().take(10)) }
    }

    /** The original purchase order: its vendor is the return's. */
    private suspend fun vendorFromPurchaseOrder(code: String) {
        _state.update { it.copy(busy = true, notice = null) }
        when (val result = repository.purchaseOrder(userName, code)) {
            is NetworkResult.Success -> {
                val po = result.data.summary
                scanFeedback.success()
                updateDraft { it.copy(vendorAccount = po.vendorAccount, vendorName = po.vendorName) }
                _state.update {
                    it.copy(
                        busy = false,
                        sourcePo = po.purchId,
                        pickingVendor = false,
                        notice = OrderNotice(StatusKind.Info, "${po.purchId} · ${po.vendorAccount}", "${po.vendorName} - scan the items that go back".trimStart(' ', '-')),
                    )
                }
            }
            is NetworkResult.Error -> refuse(
                "Order not found",
                if (!online.value) "Offline - ⋮ Pick the vendor instead" else "$code: ${result.failure.message}",
            )
            NetworkResult.Loading -> Unit
        }
    }

    // -- Lines ---------------------------------------------------------------------------------------

    private suspend fun onItem(code: String) {
        val s = _state.value
        if (s.choosingVariant != null || s.choosingStock != null) {
            scanFeedback.error()
            notice(StatusKind.Warn, "Pick one below first", "Or Undo to drop it")
            return
        }
        _state.update { it.copy(busy = true, notice = null) }
        when (val result = repository.findItem(code)) {
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

    /**
     * Online: the stock here per location, batch and serial. The only row with stock is taken
     * without asking; several are a real choice. Offline the line names only the item, and
     * D365FO checks the stock when the return is sent.
     */
    private suspend fun withVariant(item: OrderItem, variant: OrderVariant) {
        val warehouse = _state.value.draft.warehouseId
        val stock = if (online.value) {
            (repository.stock(item.itemId, variant, warehouse, byLocation = true) as? NetworkResult.Success)?.data
        } else {
            null
        }
        val withStock = stock?.filter { it.available > 0 }
        when {
            withStock != null && withStock.isEmpty() -> refuse("No stock here", "No ${item.itemId} ${variant.label} at $warehouse".replace("  ", " "))
            withStock == null -> addLine(PendingTransferLine(item, variant, null))
            else -> {
                val only = onlyStockRow(withStock)
                if (only != null) addLine(PendingTransferLine(item, variant, stock, pick = only))
                else _state.update { it.copy(busy = false, choosingStock = PendingTransferLine(item, variant, withStock, choosingStock = true)) }
            }
        }
    }

    fun pickStock(row: StockRow) {
        val pending = _state.value.choosingStock ?: return
        _state.update { it.copy(choosingStock = null) }
        addLine(pending.copy(pick = row, choosingStock = false))
    }

    /** What is left to take from [pick] - its stock less what this return already takes. */
    private fun availableLeft(itemId: String, variant: OrderVariant, pick: StockRow?, excluding: String? = null): Double? {
        val available = pick?.available ?: return null
        val already = _state.value.draft.lines
            .filter {
                it.id != excluding && it.itemId == itemId && it.variant == variant &&
                    it.locationId == pick.locationId && it.batchId == pick.batchId && it.serialId == pick.serialId
            }
            .sumOf { it.qty }
        return (available - already).coerceAtLeast(0.0)
    }

    /** One scan, one unit back - the scan is the confirm; the keypad changes it. */
    private fun addLine(pending: PendingTransferLine) {
        val pick = pending.pick
        val variant = pending.variant ?: OrderVariant()
        val left = availableLeft(pending.item.itemId, variant, pick)
        if (left != null && left < 1.0) {
            refuse("No more at ${pick?.locationId.orEmpty()}".trim(), "All ${qtyText(pick?.available ?: 0.0)} of ${pending.item.itemId} there are on this return")
            return
        }
        val line = DraftLine(
            itemId = pending.item.itemId,
            itemName = pending.item.itemName,
            variant = variant,
            qty = 1.0,
            unitId = pending.item.inventUnit,
            batchId = pick?.batchId.orEmpty(),
            serialId = pick?.serialId.orEmpty(),
            locationId = pick?.locationId.orEmpty(),
            available = pick?.available,
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
                notice = OrderNotice(
                    StatusKind.Ok,
                    "Returning ${pending.item.itemId}",
                    listOfNotNull(addedLine(lineNow, 1.0), pick?.locationId?.takeIf { l -> l.isNotBlank() }?.let { l -> "from $l" }).joinToString(" · "),
                    undoable = true,
                ),
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

    /** Up to what is in the location / batch / serial, less the rest of this return. */
    fun keypadRules(line: DraftLine): QuantityRules {
        val pick = line.available?.let { StockRow(line.batchId, line.serialId, it, line.locationId) }
        val left = availableLeft(line.itemId, line.variant, pick, excluding = line.id)
        return QuantityRules(
            available = left,
            availableWhere = line.locationId.takeIf { it.isNotBlank() }?.let { "at location $it" } ?: "to return",
        )
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

    // -- Post (Hold to post return) ---------------------------------------------------------------

    fun post() {
        val s = _state.value
        val draft = s.draft
        if (s.missing != null || s.busy) return
        _state.update { it.copy(busy = true, notice = null, showingLines = false) }
        appScope.launch {
            // A return ships stock out at once: not while a wall-to-wall count freezes the warehouse.
            freezes.frozenMessage(draft.warehouseId)?.let { reason ->
                scanFeedback.error()
                _state.update { it.copy(busy = false, notice = OrderNotice(StatusKind.Bad, "Not posted", reason)) }
                return@launch
            }
            memory.setLastReturnCodes(userName, draft.reasonCode, draft.actionCode)
            val id = repository.submitReturn(userName, draft)
            _state.update {
                ReturnCreateState(
                    draft = ReturnDraft(warehouseId = draft.warehouseId, reasonCode = draft.reasonCode, actionCode = draft.actionCode),
                    vendorCount = it.vendorCount,
                    vendors = it.vendors,
                    reasons = it.reasons,
                    actions = it.actions,
                    submittedId = id,
                )
            }
        }
    }

    private fun updateDraft(change: (ReturnDraft) -> ReturnDraft) {
        _state.update { it.copy(draft = change(it.draft)) }
        val draft = _state.value.draft
        viewModelScope.launch {
            if (draft.vendorAccount.isBlank() && draft.lines.isEmpty()) repository.discardDraft(OrderKind.RETURN, userName)
            else repository.saveReturnDraft(userName, draft)
        }
    }
}

/**
 * A reason or action code to start with: the one already on the draft, else the worker's last
 * used (while D365FO still has it), else the only one there is; blank when there is a choice.
 */
fun defaultCode(current: String, lastUsed: String?, codes: List<ReturnReason>): String = when {
    current.isNotBlank() -> current
    lastUsed != null && codes.any { it.code == lastUsed } -> lastUsed
    else -> codes.singleOrNull()?.code.orEmpty()
}
