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
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.sync.SyncStatusProvider
import com.d365fo.mobileops.domain.order.QuantityRules
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val vendorQuery: String = "",
    val vendors: List<VendorEntity> = emptyList(),
    val vendorCount: Int = 0,
    val reasons: List<ReturnReason> = emptyList(),
    val actions: List<ReturnReason> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val choosingVariant: OrderItem? = null,
    /** Same shape as a transfer line: item, variant, the stock per bin / batch / serial and the pick. */
    val pending: PendingTransferLine? = null,
    val enteringQty: Boolean = false,
    val editing: DraftLine? = null,
    val editingRma: Boolean = false,
    val submittedId: String? = null,
) {
    /** What still stops the return being sent, or null when it can go. */
    val missing: String?
        get() = when {
            draft.lines.isEmpty() -> "Scan what goes back"
            reasons.isNotEmpty() && draft.reasonCode.isBlank() -> "Pick a return reason"
            actions.isNotEmpty() && draft.actionCode.isBlank() -> "Pick a return action"
            else -> null
        }
}

/**
 * Purchase return (return to vendor): pick the vendor - or scan the original PO and its vendor is
 * picked - and a return reason, then scan what goes back. Online, the stock at the warehouse is
 * listed per bin, batch and serial and the worker picks what is taken; offline the line names
 * only the item and D365FO checks the stock when the return is sent. D365FO creates the return
 * order, confirms it and posts the packing slip - see HSMobileOpsOrderCreateService::createPurchaseReturn.
 */
@HiltViewModel
class ReturnCreateViewModel @Inject constructor(
    private val repository: OrderCreationRepository,
    private val sessions: WorkerSessionRepository,
    private val scannerBridge: HidScannerBridge,
    syncStatusProvider: SyncStatusProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(ReturnCreateState())
    val state: StateFlow<ReturnCreateState> = _state.asStateFlow()

    val online: StateFlow<Boolean> = syncStatusProvider.status.map { it.fullyOnline }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val orders: StateFlow<List<CreatedOrderEntity>> = repository.observeOrders(OrderKind.RETURN)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val userName: String get() = sessions.session.value?.userName.orEmpty()

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
        viewModelScope.launch { scannerBridge.scans.collect { onCode(it.value) } }
    }

    fun onCameraDecoded(value: String) = scannerBridge.submitCameraScan(value)

    // -- Vendor and reason -----------------------------------------------------------------------

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
        _state.update { it.copy(vendors = list, vendorCount = repository.vendorCount()) }
    }

    private suspend fun loadReasons() {
        val result = repository.returnCodes()
        if (result is NetworkResult.Success) {
            val codes = result.data
            _state.update { s ->
                // Only one to choose from - nothing to choose.
                val reason = s.draft.reasonCode.ifBlank { codes.reasons.singleOrNull()?.code.orEmpty() }
                val action = s.draft.actionCode.ifBlank { codes.actions.singleOrNull()?.code.orEmpty() }
                s.copy(reasons = codes.reasons, actions = codes.actions, draft = s.draft.copy(reasonCode = reason, actionCode = action))
            }
        }
    }

    fun pickVendor(vendor: VendorEntity) = updateDraft { it.copy(vendorAccount = vendor.accountNum, vendorName = vendor.name) }

    fun changeVendor() = updateDraft { it.copy(vendorAccount = "", vendorName = "") }

    fun pickReason(code: String) = updateDraft { it.copy(reasonCode = code) }

    fun pickAction(code: String) = updateDraft { it.copy(actionCode = code) }

    fun editRma(open: Boolean) = _state.update { it.copy(editingRma = open) }

    fun setRma(value: String) {
        _state.update { it.copy(editingRma = false) }
        updateDraft { it.copy(rmaNumber = value.trim().take(10)) }
    }

    /** A scan before the vendor is known is the original purchase order: its vendor is picked. */
    private suspend fun vendorFromPurchaseOrder(code: String) {
        _state.update { it.copy(busy = true, error = null) }
        when (val result = repository.purchaseOrder(userName, code)) {
            is NetworkResult.Success -> {
                val po = result.data.summary
                _state.update { it.copy(busy = false) }
                updateDraft { it.copy(vendorAccount = po.vendorAccount, vendorName = po.vendorName) }
            }
            is NetworkResult.Error -> _state.update {
                it.copy(busy = false, error = if (!online.value) "$code can't be looked up offline - pick the vendor from the list." else "$code: ${result.failure.message}")
            }
            NetworkResult.Loading -> Unit
        }
    }

    // -- Lines -----------------------------------------------------------------------------------

    fun onCode(code: String) {
        val s = _state.value
        if (s.busy || s.pending != null || s.choosingVariant != null || code.isBlank()) return
        viewModelScope.launch {
            if (s.draft.vendorAccount.isBlank()) {
                vendorFromPurchaseOrder(code.trim())
                return@launch
            }
            _state.update { it.copy(busy = true, error = null) }
            when (val result = repository.findItem(code)) {
                is NetworkResult.Success -> {
                    val item = result.data
                    val scanned = item.scannedVariant
                    when {
                        !item.hasProductDimensions -> withVariant(item, OrderVariant())
                        scanned != null -> withVariant(item, scanned)
                        item.variants.isEmpty() -> _state.update { it.copy(busy = false, error = "${item.itemId} has no released variants") }
                        item.variants.size == 1 -> withVariant(item, item.variants.single())
                        else -> _state.update { it.copy(busy = false, choosingVariant = item) }
                    }
                }
                is NetworkResult.Error -> _state.update {
                    it.copy(busy = false, error = if (!online.value) "$code isn't saved on this device. Look it up once while connected." else result.failure.message)
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun pickVariant(variant: OrderVariant) {
        val item = _state.value.choosingVariant ?: return
        _state.update { it.copy(choosingVariant = null, busy = true) }
        viewModelScope.launch { withVariant(item, variant) }
    }

    /** Online: the stock here per bin, batch and serial to pick from (one row is picked for you). */
    private suspend fun withVariant(item: OrderItem, variant: OrderVariant) {
        val warehouse = _state.value.draft.warehouseId
        val stock = if (online.value) {
            (repository.stock(item.itemId, variant, warehouse, byLocation = true) as? NetworkResult.Success)?.data
        } else {
            null
        }
        if (stock != null && stock.isEmpty()) {
            _state.update { it.copy(busy = false, error = "No stock of ${item.itemId} ${variant.label} at $warehouse to return".trim()) }
            return
        }
        val pending = PendingTransferLine(item, variant, stock)
        _state.update {
            when {
                stock != null && stock.size == 1 -> it.copy(busy = false, pending = pending.copy(pick = stock.single()), enteringQty = true)
                stock != null -> it.copy(busy = false, pending = pending.copy(choosingStock = true))
                else -> it.copy(busy = false, pending = pending, enteringQty = true)
            }
        }
    }

    fun pickStock(row: com.d365fo.mobileops.data.repository.StockRow?) {
        val pending = _state.value.pending ?: return
        _state.update { it.copy(pending = pending.copy(pick = row, choosingStock = false), enteringQty = true) }
    }

    fun cancelPending() = _state.update { it.copy(choosingVariant = null, pending = null, enteringQty = false) }

    /** Up to what is in the bin / batch / serial picked, less what this return already takes. */
    fun quantityRules(pending: PendingTransferLine): QuantityRules {
        val pick = pending.pick
        val available = pick?.available ?: pending.stock?.sumOf { it.available }
        val already = _state.value.draft.lines
            .filter {
                it.itemId == pending.item.itemId && it.variant == pending.variant &&
                    it.locationId == pick?.locationId.orEmpty() && it.batchId == pick?.batchId.orEmpty() && it.serialId == pick?.serialId.orEmpty()
            }
            .sumOf { it.qty }
        return QuantityRules(
            available = available?.let { (it - already).coerceAtLeast(0.0) },
            availableWhere = pick?.locationId?.takeIf { it.isNotBlank() }?.let { "in bin $it" } ?: "to return",
        )
    }

    fun addLine(qty: Double) {
        val pending = _state.value.pending ?: return
        val pick = pending.pick
        val line = DraftLine(
            itemId = pending.item.itemId,
            itemName = pending.item.itemName,
            variant = pending.variant ?: OrderVariant(),
            qty = qty,
            unitId = pending.item.inventUnit,
            batchId = pick?.batchId.orEmpty(),
            serialId = pick?.serialId.orEmpty(),
            locationId = pick?.locationId.orEmpty(),
            available = pick?.available ?: pending.stock?.sumOf { it.available },
        )
        _state.update { it.copy(pending = null, enteringQty = false) }
        updateDraft { d ->
            // The same stock again adds to its line.
            val same = d.lines.indexOfFirst {
                it.itemId == line.itemId && it.variant == line.variant && it.locationId == line.locationId &&
                    it.batchId == line.batchId && it.serialId == line.serialId
            }
            if (same >= 0) d.copy(lines = d.lines.mapIndexed { i, l -> if (i == same) l.copy(qty = l.qty + qty) else l })
            else d.copy(lines = d.lines + line)
        }
    }

    fun editLine(line: DraftLine?) = _state.update { it.copy(editing = line) }

    fun changeQuantity(line: DraftLine, qty: Double) {
        _state.update { it.copy(editing = null) }
        updateDraft { d -> d.copy(lines = d.lines.map { if (it.id == line.id) it.copy(qty = qty) else it }) }
    }

    fun removeLine(line: DraftLine) {
        _state.update { it.copy(editing = null) }
        updateDraft { d -> d.copy(lines = d.lines.filterNot { it.id == line.id }) }
    }

    // -- Return ----------------------------------------------------------------------------------

    fun create() {
        val draft = _state.value.draft
        if (_state.value.missing != null || _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val id = repository.submitReturn(userName, draft)
            _state.update {
                ReturnCreateState(
                    draft = ReturnDraft(warehouseId = draft.warehouseId, reasonCode = it.reasons.singleOrNull()?.code.orEmpty(), actionCode = it.actions.singleOrNull()?.code.orEmpty()),
                    vendorCount = it.vendorCount,
                    reasons = it.reasons,
                    actions = it.actions,
                    submittedId = id,
                )
            }
            loadVendors()
        }
    }

    fun discard() {
        viewModelScope.launch {
            repository.discardDraft(OrderKind.RETURN, userName)
            _state.update {
                it.copy(
                    draft = ReturnDraft(
                        warehouseId = it.draft.warehouseId,
                        reasonCode = it.reasons.singleOrNull()?.code.orEmpty(),
                        actionCode = it.actions.singleOrNull()?.code.orEmpty(),
                    ),
                    error = null,
                )
            }
        }
    }

    fun dismissResult() = _state.update { it.copy(submittedId = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun updateDraft(change: (ReturnDraft) -> ReturnDraft) {
        _state.update { it.copy(draft = change(it.draft)) }
        val draft = _state.value.draft
        viewModelScope.launch {
            if (draft.vendorAccount.isBlank() && draft.lines.isEmpty()) repository.discardDraft(OrderKind.RETURN, userName)
            else repository.saveReturnDraft(userName, draft)
        }
    }
}
