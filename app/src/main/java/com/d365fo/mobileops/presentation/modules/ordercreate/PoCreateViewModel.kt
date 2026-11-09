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
import com.d365fo.mobileops.data.repository.PurchaseDraft
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.sync.SyncStatusProvider
import com.d365fo.mobileops.domain.order.QuantityRules
import com.d365fo.mobileops.domain.order.checkQuantity
import com.d365fo.mobileops.presentation.common.components.StatusKind
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.common.scan.ScanContext
import com.d365fo.mobileops.presentation.common.scan.ScanFeedback
import com.d365fo.mobileops.presentation.common.scan.ScanIdentity
import com.d365fo.mobileops.presentation.common.scan.ScanKind
import com.d365fo.mobileops.presentation.common.scan.ScanRoute
import com.d365fo.mobileops.presentation.common.scan.ScanRouter
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class PoCreateState(
    val draft: PurchaseDraft = PurchaseDraft(),
    val vendorQuery: String = "",
    val vendors: List<VendorEntity> = emptyList(),
    /** The vendor ordered from last - first in the list, one tap to start. */
    val lastVendor: VendorEntity? = null,
    val vendorCount: Int = 0,
    val vendorsSavedAt: Long? = null,
    val refreshingVendors: Boolean = false,
    val busy: Boolean = false,
    val pricing: Boolean = false,
    /** What the last scan or action did - see [OrderNotice]. */
    val notice: OrderNotice? = null,
    /** Item scanned, its variant still to pick - the one real choice a scan can need. */
    val choosingVariant: OrderItem? = null,
    /** What the last scan added, for Undo and the keypad. */
    val lastAdd: LineAdd? = null,
    /** Lines (F2). */
    val showingLines: Boolean = false,
    /** A line whose price a supervisor is setting. */
    val editingPrice: DraftLine? = null,
    /** The order just sent - the band follows it from sending to created or refused. */
    val submittedId: String? = null,
)

/**
 * Create PO, scan only (redesign brief §4, phase 7): the vendor list with the last-used vendor
 * first (one tap) - or scan the vendor's account - then each item scan adds the item at its
 * order minimum, rounded to its multiple ([defaultOrderQty]); the keypad (F4, or tapping the
 * quantity) changes it. The order is created and confirmed in D365FO when the worker leaves
 * (Back, F1) or scans another vendor; the band then follows it to its PO number, or to D365FO's
 * reason when it refuses it.
 *
 * Works offline as before: vendors and items come from what the device has saved, prices are
 * D365FO's while online and set by D365FO when the order is created. The creation path - the
 * draft, [OrderCreationRepository.submitPurchase], the queue - is unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PoCreateViewModel @Inject constructor(
    private val repository: OrderCreationRepository,
    private val sessions: WorkerSessionRepository,
    private val scannerBridge: HidScannerBridge,
    private val syncStatusProvider: SyncStatusProvider,
    private val scanFeedback: ScanFeedback,
    private val memory: OrderCreateMemory,
    private val appScope: CoroutineScope,
    scanRouter: ScanRouter,
    permissionDao: com.d365fo.mobileops.data.local.dao.PermissionDao,
) : ViewModel() {

    /** Whether the worker may also confirm - then a PO created but not confirmed offers "Confirm it now". */
    val mayConfirm: StateFlow<Boolean> = permissionDao.observePermissions(sessions.session.value?.userName.orEmpty())
        .map { rows -> rows.any { it.workflow == com.d365fo.mobileops.domain.model.WarehouseWorkflow.PO_CONFIRM && it.allowed } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _state = MutableStateFlow(PoCreateState())
    val state: StateFlow<PoCreateState> = _state.asStateFlow()

    val online: StateFlow<Boolean> = syncStatusProvider.status.map { it.fullyOnline }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val orders: StateFlow<List<CreatedOrderEntity>> = repository.observeOrders(OrderKind.PURCHASE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val userName: String get() = sessions.session.value?.userName.orEmpty()

    /** The warehouse the worker is working in now - a new order is for it. */
    private fun currentWarehouse(): String = sessions.session.value?.selected?.warehouseId.orEmpty()

    /** Supervisors may override D365FO's price - see HSMobileOpsOrderCreateService::createPurchaseOrder. */
    val mayOverridePrice: Boolean get() = sessions.session.value?.isSupervisor == true

    /** Each scanned item's order rules, for the keypad's check. Not kept for a reopened draft. */
    private val rules = mutableMapOf<String, QuantityRules>()

    /** The draft a Discard took away - Undo puts it back. */
    private var discarded: PurchaseDraft? = null

    private val scanning = OrderFlowScanning(scanRouter, ::scanContext, ::onRoute)

    init {
        viewModelScope.launch {
            val saved = repository.loadPurchaseDraft(userName)
            val warehouse = currentWarehouse()
            val today = java.time.LocalDate.now().toString()
            _state.update {
                it.copy(
                    draft = (saved ?: PurchaseDraft()).let { d ->
                        when {
                            // Nothing on it yet: the warehouse the worker is in now (a draft
                            // left at another warehouse ordered into AAS while working in AAC).
                            d.lines.isEmpty() -> d.copy(warehouseId = warehouse, deliveryDate = today)
                            d.warehouseId.isBlank() -> d.copy(warehouseId = warehouse)
                            else -> d
                        }.let { r -> if (r.deliveryDate < today) r.copy(deliveryDate = today) else r }
                    },
                )
            }
            loadVendors()
            // A draft saved offline, or before D365FO answered, gets its prices now.
            if (_state.value.draft.lines.any { it.lineAmount == null }) {
                // The first connectivity reading can lag - give it a few seconds to say online.
                val online = withTimeoutOrNull(5_000) { syncStatusProvider.status.first { it.fullyOnline } } != null
                priceAll(isOnline = online)
            }
            refreshVendors(force = false)
        }
    }

    // -- Scans (through ScanRouter, as PO receiving) --------------------------------------------

    fun scannerOnScreen(visible: Boolean) = scanning.onScreen(visible)

    override fun onCleared() {
        scanning.close()
        super.onCleared()
    }

    /** Items once a vendor is picked; before that a barcode may be a vendor's account. */
    private fun scanContext(): ScanContext = ScanContext.Flow(
        accepts = setOf(ScanKind.ITEM),
        acceptUnknown = true,
        expecting = if (_state.value.draft.vendorAccount.isBlank()) "Pick the vendor first" else "Scan an item",
    )

    private suspend fun onRoute(route: ScanRoute) {
        when (route) {
            is ScanRoute.Accept -> onCode(route.identity)
            is ScanRoute.Leave, is ScanRoute.SwitchDocument -> {
                scanFeedback.error()
                notice(StatusKind.Warn, "That's ${documentNoun(route.identity.kind)}", "Scan it on Home - this screen orders items")
            }
            is ScanRoute.Reject -> {
                scanFeedback.error()
                notice(if (route.reason == com.d365fo.mobileops.presentation.common.scan.RejectReason.NOT_EXPECTED) StatusKind.Warn else StatusKind.Bad, route.title, route.line)
            }
            else -> Unit
        }
    }

    fun onCameraDecoded(value: String) = scannerBridge.submitCameraScan(value)

    /** Typed (⋮ Type it in): the same path as a scan. */
    fun onTyped(value: String) = scannerBridge.submitCameraScan(value)

    fun justScanned(): Boolean = scannerBridge.justScanned()

    private suspend fun onCode(identity: ScanIdentity) {
        val s = _state.value
        if (s.choosingVariant != null) {
            scanFeedback.error()
            notice(StatusKind.Warn, "Pick the variant first", "Tap one below, or Undo")
            return
        }
        val code = itemCodeFor(identity)
        // A barcode nothing on the device knows may be a vendor's account: start (or switch to) it.
        if (identity is ScanIdentity.Unknown && identity.gs1 == null) {
            vendorFor(code, repository.searchVendors(code))?.let { vendor ->
                switchVendor(vendor)
                return
            }
        }
        if (s.draft.vendorAccount.isBlank()) {
            scanFeedback.error()
            notice(StatusKind.Warn, "Pick the vendor first", "Tap a vendor below, or scan its account")
            return
        }
        _state.update { it.copy(busy = true, notice = null) }
        when (val result = repository.findItem(code)) {
            is NetworkResult.Success -> {
                val item = result.data
                val scanned = item.scannedVariant
                when {
                    !item.hasProductDimensions -> addLine(item, OrderVariant())
                    scanned != null -> addLine(item, scanned)
                    item.variants.isEmpty() -> refuse("No variants to order", "${item.itemId} has no released variants")
                    item.variants.size == 1 -> addLine(item, item.variants.single())
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

    private fun refuse(title: String, line: String) {
        scanFeedback.error()
        _state.update { it.copy(busy = false, notice = OrderNotice(StatusKind.Bad, title, "$line - nothing was added")) }
    }

    private fun notice(kind: StatusKind, title: String, line: String? = null, undoable: Boolean = false) =
        _state.update { it.copy(notice = OrderNotice(kind, title, line, undoable), busy = false) }

    // -- Vendor --------------------------------------------------------------------------------

    private var vendorSearch: kotlinx.coroutines.Job? = null

    fun onVendorQuery(value: String) {
        _state.update { it.copy(vendorQuery = value) }
        // One search at a time, applied only if the text is still what was searched: each letter
        // started its own, and an earlier one ("19") finishing last showed its results for "19721".
        vendorSearch?.cancel()
        vendorSearch = viewModelScope.launch { loadVendors() }
    }

    private suspend fun loadVendors() {
        val query = _state.value.vendorQuery
        val list = repository.searchVendors(query)
        if (_state.value.vendorQuery != query) return
        val last = memory.lastPoVendor(userName)?.let { account -> vendorFor(account, repository.searchVendors(account)) }
        _state.update {
            it.copy(vendors = list, lastVendor = last, vendorCount = repository.vendorCount(), vendorsSavedAt = repository.vendorsSavedAt())
        }
    }

    fun refreshVendors(force: Boolean = true) {
        viewModelScope.launch {
            _state.update { it.copy(refreshingVendors = true) }
            val result = repository.refreshVendors(force)
            _state.update {
                it.copy(
                    refreshingVendors = false,
                    notice = if (force && result is NetworkResult.Error) OrderNotice(StatusKind.Bad, "Vendors not refreshed", result.failure.message) else it.notice,
                )
            }
            loadVendors()
        }
    }

    fun pickVendor(vendor: VendorEntity) {
        updateDraft { it.copy(vendorAccount = vendor.accountNum, vendorName = vendor.name, currencyCode = vendor.currencyCode) }
        memory.setLastPoVendor(userName, vendor.accountNum)
        _state.update {
            it.copy(
                vendorQuery = "",
                notice = OrderNotice(StatusKind.Info, "${vendor.accountNum} ${vendor.name}".trim(), "Scan the items to order"),
            )
        }
        priceAll()
    }

    /** A vendor's account scanned: the order in hand is created and confirmed, then the new one starts. */
    private suspend fun switchVendor(vendor: VendorEntity) {
        val draft = _state.value.draft
        when {
            draft.vendorAccount.equals(vendor.accountNum, ignoreCase = true) ->
                notice(StatusKind.Info, "Already ordering from ${vendor.accountNum}", "Scan the items to order")
            draft.lines.isEmpty() -> {
                scanFeedback.success()
                pickVendor(vendor)
            }
            else -> {
                scanFeedback.success()
                submit()?.join()
                pickVendor(vendor)
            }
        }
    }

    fun changeVendor() {
        if (_state.value.draft.lines.isNotEmpty()) return
        updateDraft { it.copy(vendorAccount = "", vendorName = "", currencyCode = "") }
        _state.update { it.copy(notice = null) }
    }

    // -- Lines ---------------------------------------------------------------------------------

    fun pickVariant(variant: OrderVariant) {
        val item = _state.value.choosingVariant ?: return
        _state.update { it.copy(choosingVariant = null) }
        addLine(item, variant)
    }

    /** One scan, one line: the item at [defaultOrderQty] - the scan is the confirm. */
    private fun addLine(item: OrderItem, variant: OrderVariant) {
        rules[item.itemId] = QuantityRules(min = item.minQty, max = item.maxQty, multiple = item.multipleQty)
        val qty = defaultOrderQty(item)
        val line = DraftLine(
            itemId = item.itemId,
            itemName = item.itemName,
            variant = variant,
            qty = qty,
            unitId = item.purchUnit.ifBlank { item.inventUnit },
        )
        var added: LineAdd? = null
        var lineNow: DraftLine = line
        updateDraft { draft ->
            val (lines, add) = addOrMerge(draft.lines, line, repriced = true)
            added = add
            lineNow = lines.first { it.id == add.lineId }
            draft.copy(lines = lines)
        }
        scanFeedback.success()
        _state.update {
            it.copy(
                busy = false,
                lastAdd = added,
                choosingVariant = null,
                notice = OrderNotice(StatusKind.Ok, "Added ${item.itemId}", addedLine(lineNow, qty), undoable = true),
            )
        }
        priceAll()
    }

    fun cancelVariant() = _state.update { it.copy(choosingVariant = null, notice = null) }

    /** Undo (F3): the variant choice, else the quantity the last scan added, else a Discard. */
    fun undo() {
        val s = _state.value
        when {
            s.choosingVariant != null -> cancelVariant()
            discarded != null && s.draft.lines.isEmpty() -> {
                val back = discarded ?: return
                discarded = null
                updateDraft { back }
                _state.update { it.copy(notice = OrderNotice(StatusKind.Info, "Order back", linesWord(back.lines.size))) }
                priceAll()
            }
            s.draft.lines.isNotEmpty() -> {
                val gone = lineInHand(s.draft.lines, s.lastAdd)
                updateDraft { d -> d.copy(lines = undoAdd(d.lines, s.lastAdd, repriced = true)) }
                _state.update {
                    it.copy(lastAdd = null, notice = OrderNotice(StatusKind.Info, "Undone", "${gone?.itemId.orEmpty()} taken off - scan it again".trim()))
                }
                priceAll()
            }
        }
    }

    val canUndo: Boolean
        get() = _state.value.let { it.choosingVariant != null || it.draft.lines.isNotEmpty() || (discarded != null && it.draft.vendorAccount.isNotBlank()) }

    /** The line the keypad changes: the one the last scan touched. */
    fun keypadLine(): DraftLine? = _state.value.let { if (it.choosingVariant == null) lineInHand(it.draft.lines, it.lastAdd) else null }

    fun keypadError(line: DraftLine, value: String): String? {
        val qty = value.replace(',', '.').toDoubleOrNull() ?: return "Enter a number"
        return checkQuantity(qty, rules[line.itemId] ?: QuantityRules())
    }

    fun changeQuantity(line: DraftLine, value: String) {
        val qty = value.replace(',', '.').toDoubleOrNull() ?: return
        if (keypadError(line, value) != null) return
        updateDraft { d -> d.copy(lines = d.lines.map { if (it.id == line.id) it.copy(qty = qty, lineAmount = null, price = if (it.priceOverride) it.price else null) else it }) }
        _state.update {
            it.copy(lastAdd = LineAdd(line.id, qty), notice = OrderNotice(StatusKind.Ok, "${line.itemId} changed", "${com.d365fo.mobileops.domain.order.qtyText(qty)} ${line.unitId}".trim()))
        }
        priceAll()
    }

    fun removeLine(line: DraftLine) {
        updateDraft { d -> d.copy(lines = d.lines.filterNot { it.id == line.id }) }
        _state.update { it.copy(lastAdd = null, notice = OrderNotice(StatusKind.Info, "${line.itemId} removed")) }
    }

    fun showLines(show: Boolean) = _state.update { it.copy(showingLines = show) }

    fun editPrice(line: DraftLine?) = _state.update { it.copy(editingPrice = line) }

    fun overridePrice(line: DraftLine, price: Double) {
        _state.update { it.copy(editingPrice = null) }
        updateDraft { d ->
            d.copy(lines = d.lines.map { if (it.id == line.id) it.copy(price = price, priceOverride = true, lineAmount = price * it.qty, priceSource = "Price entered here") else it })
        }
    }

    /**
     * Pricing in flight. Cancelled when the order is created or discarded: an answer arriving
     * after that would otherwise write the old lines back as a draft - and the same order could
     * then be created twice.
     */
    private var priceJob: kotlinx.coroutines.Job? = null

    /** D365FO's prices for every line - online only; offline they are set when the order is sent. */
    fun priceAll(isOnline: Boolean = online.value) {
        val draft = _state.value.draft
        if (!isOnline || draft.vendorAccount.isBlank() || draft.lines.isEmpty()) return
        priceJob?.cancel()
        priceJob = viewModelScope.launch {
            _state.update { it.copy(pricing = true) }
            when (val result = repository.priceLines(draft)) {
                is NetworkResult.Success -> {
                    val priced = result.data.associateBy { it.id }
                    // Only lines still on the order, at the quantity priced - a scan meanwhile wins.
                    updateDraft { d -> d.copy(lines = d.lines.map { l -> priced[l.id]?.takeIf { it.qty == l.qty } ?: l }) }
                }
                // Not an error to the worker: priced when sent.
                is NetworkResult.Error -> android.util.Log.w("PoCreate", "priceLines: ${result.failure.message}")
                NetworkResult.Loading -> Unit
            }
            _state.update { it.copy(pricing = false) }
        }
    }

    // -- Create (on leave, or on the next vendor) -------------------------------------------------

    /** Whether leaving now creates the order - the screen's Back sends it rather than leaving. */
    val hasOrderToSend: Boolean get() = _state.value.draft.let { it.vendorAccount.isNotBlank() && it.lines.isNotEmpty() }

    /**
     * Creates and confirms the order in hand: saved and queued in one go (the existing path), sent
     * at once when D365FO answers. Runs on the app's scope, so leaving the screen straight after
     * can't cut it short. The screen is then ready for the next order, last vendor first.
     */
    fun submit(): kotlinx.coroutines.Job? {
        val s = _state.value
        val draft = s.draft
        if (draft.vendorAccount.isBlank() || draft.lines.isEmpty()) return null
        priceJob?.cancel()
        discarded = null
        val warehouse = currentWarehouse().ifBlank { draft.warehouseId }
        _state.update { it.copy(busy = true, pricing = false, notice = null, choosingVariant = null, showingLines = false) }
        memory.setLastPoVendor(userName, draft.vendorAccount)
        return appScope.launch {
            val id = repository.submitPurchase(userName, draft)
            _state.update {
                it.copy(
                    draft = PurchaseDraft(warehouseId = warehouse),
                    busy = false,
                    lastAdd = null,
                    submittedId = id,
                    notice = null,
                )
            }
            loadVendors()
        }
    }

    /** ⋮ Discard: the lines go, nothing is sent - Undo brings them back. */
    fun discard() {
        priceJob?.cancel()
        val draft = _state.value.draft
        if (draft.lines.isEmpty()) return
        discarded = draft
        updateDraft { it.copy(lines = emptyList()) }
        _state.update {
            it.copy(lastAdd = null, showingLines = false, notice = OrderNotice(StatusKind.Info, "Order discarded", "${linesWord(draft.lines.size)} - nothing was sent", undoable = true))
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private fun updateDraft(change: (PurchaseDraft) -> PurchaseDraft) {
        _state.update { it.copy(draft = change(it.draft)) }
        val draft = _state.value.draft
        viewModelScope.launch {
            if (draft.vendorAccount.isBlank() && draft.lines.isEmpty()) {
                repository.discardDraft(OrderKind.PURCHASE, userName)
            } else {
                repository.savePurchaseDraft(userName, draft)
            }
        }
    }
}
