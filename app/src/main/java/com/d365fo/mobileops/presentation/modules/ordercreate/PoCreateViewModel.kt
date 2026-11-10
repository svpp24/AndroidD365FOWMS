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
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
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

/** A line waiting for its variant, then its quantity. */
data class PendingLine(val item: OrderItem, val variant: OrderVariant? = null)

data class PoCreateState(
    val draft: PurchaseDraft = PurchaseDraft(),
    val vendorQuery: String = "",
    val vendors: List<VendorEntity> = emptyList(),
    val vendorCount: Int = 0,
    val vendorsSavedAt: Long? = null,
    val refreshingVendors: Boolean = false,
    val busy: Boolean = false,
    val pricing: Boolean = false,
    val error: String? = null,
    /** Item scanned, variant still to choose. */
    val choosingVariant: OrderItem? = null,
    /** Item and variant known, quantity still to enter. */
    val enteringQty: PendingLine? = null,
    /** A line being changed (quantity, price, removal). */
    val editing: DraftLine? = null,
    val editingPrice: DraftLine? = null,
    /** The order just submitted - its card follows it from waiting to created. */
    val submittedId: String? = null,
)

/**
 * PO creation: pick a vendor, scan items, enter quantities, create. Works offline - vendors and
 * items come from what the device has saved, and prices are D365FO's, shown while online and set
 * by D365FO when the order is created.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PoCreateViewModel @Inject constructor(
    private val repository: OrderCreationRepository,
    private val sessions: WorkerSessionRepository,
    private val scannerBridge: HidScannerBridge,
    private val syncStatusProvider: SyncStatusProvider,
    permissionDao: com.d365fo.mobileops.data.local.dao.PermissionDao,
) : ViewModel() {

    /** Whether the worker may also confirm - then a created order offers "Confirm now". */
    val mayConfirm: StateFlow<Boolean> = permissionDao.observePermissions(sessions.session.value?.userName.orEmpty())
        .map { rows -> rows.any { it.workflow == com.d365fo.mobileops.domain.model.WarehouseWorkflow.PO_CONFIRM && it.allowed } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _state = MutableStateFlow(PoCreateState())
    val state: StateFlow<PoCreateState> = _state.asStateFlow()

    val online: StateFlow<Boolean> = syncStatusProvider.status.map { it.fullyOnline }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val orders: StateFlow<List<CreatedOrderEntity>> = repository.observeOrders(OrderKind.PURCHASE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** POs confirmed (or waiting to be) from this device - no "Confirm it now" for those. */
    val confirmedPurchIds: StateFlow<Set<String>> = repository.observeOrders(OrderKind.CONFIRMATION)
        .map { rows -> rows.filter { it.status != com.d365fo.mobileops.data.local.entity.CreatedOrderStatus.FAILED }.mapNotNull { it.documentNumber }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val userName: String get() = sessions.session.value?.userName.orEmpty()

    /** The warehouse the worker is working in now - a new order is for it. */
    private fun currentWarehouse(): String = sessions.session.value?.selected?.warehouseId.orEmpty()

    /** Supervisors may override D365FO's price - see HSMobileOpsOrderCreateService::createPurchaseOrder. */
    val mayOverridePrice: Boolean get() = sessions.session.value?.isSupervisor == true

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
        viewModelScope.launch { scannerBridge.scans.collect { onCode(it.value) } }
    }

    fun onCameraDecoded(value: String) = scannerBridge.submitCameraScan(value)

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
        _state.update { it.copy(vendors = list, vendorCount = repository.vendorCount(), vendorsSavedAt = repository.vendorsSavedAt()) }
    }

    fun refreshVendors(force: Boolean = true) {
        viewModelScope.launch {
            _state.update { it.copy(refreshingVendors = true) }
            val result = repository.refreshVendors(force)
            _state.update { it.copy(refreshingVendors = false, error = if (force && result is NetworkResult.Error) result.failure.message else it.error) }
            loadVendors()
        }
    }

    fun pickVendor(vendor: VendorEntity) {
        updateDraft { it.copy(vendorAccount = vendor.accountNum, vendorName = vendor.name, currencyCode = vendor.currencyCode) }
        priceAll()
    }

    fun changeVendor() = updateDraft { it.copy(vendorAccount = "", vendorName = "", currencyCode = "") }

    // -- Lines ---------------------------------------------------------------------------------

    /** A scan or a typed item number / barcode. */
    fun onCode(code: String) {
        val current = _state.value
        if (current.draft.vendorAccount.isBlank() || current.busy || current.enteringQty != null || current.choosingVariant != null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (val result = repository.findItem(code)) {
                is NetworkResult.Success -> {
                    val item = result.data
                    val scanned = item.scannedVariant
                    when {
                        !item.hasProductDimensions -> _state.update { it.copy(busy = false, enteringQty = PendingLine(item, OrderVariant())) }
                        scanned != null -> _state.update { it.copy(busy = false, enteringQty = PendingLine(item, scanned)) }
                        item.variants.isEmpty() -> _state.update {
                            it.copy(busy = false, error = "${item.itemId} has no released variants to order")
                        }
                        item.variants.size == 1 -> _state.update { it.copy(busy = false, enteringQty = PendingLine(item, item.variants.single())) }
                        else -> _state.update { it.copy(busy = false, choosingVariant = item) }
                    }
                }
                is NetworkResult.Error -> _state.update { it.copy(busy = false, error = itemError(code, result.failure.message)) }
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun pickVariant(variant: OrderVariant) {
        val item = _state.value.choosingVariant ?: return
        _state.update { it.copy(choosingVariant = null, enteringQty = PendingLine(item, variant)) }
    }

    fun cancelPending() = _state.update { it.copy(choosingVariant = null, enteringQty = null) }

    fun quantityRules(item: OrderItem) = QuantityRules(min = item.minQty, max = item.maxQty, multiple = item.multipleQty)

    fun addLine(qty: Double) {
        val pending = _state.value.enteringQty ?: return
        val item = pending.item
        val variant = pending.variant ?: OrderVariant()
        val line = DraftLine(
            itemId = item.itemId,
            itemName = item.itemName,
            variant = variant,
            qty = qty,
            unitId = item.purchUnit.ifBlank { item.inventUnit },
        )
        _state.update { it.copy(enteringQty = null) }
        updateDraft { draft ->
            // The same item and variant again adds to its line rather than making a second one.
            val same = draft.lines.indexOfFirst { it.itemId == line.itemId && it.variant == line.variant && !it.priceOverride }
            if (same >= 0) {
                draft.copy(lines = draft.lines.mapIndexed { i, l -> if (i == same) l.copy(qty = l.qty + qty, price = null, lineAmount = null) else l })
            } else {
                draft.copy(lines = draft.lines + line)
            }
        }
        priceAll()
    }

    fun editLine(line: DraftLine?) = _state.update { it.copy(editing = line) }

    fun changeQuantity(line: DraftLine, qty: Double) {
        _state.update { it.copy(editing = null) }
        updateDraft { d -> d.copy(lines = d.lines.map { if (it.id == line.id) it.copy(qty = qty, lineAmount = null, price = if (it.priceOverride) it.price else null) else it }) }
        priceAll()
    }

    fun removeLine(line: DraftLine) {
        _state.update { it.copy(editing = null) }
        updateDraft { d -> d.copy(lines = d.lines.filterNot { it.id == line.id }) }
    }

    fun editPrice(line: DraftLine?) = _state.update { it.copy(editingPrice = line, editing = null) }

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
            // 1.5 s after the last change: a run of scans or edits is priced once (calls, 10 Nov).
            kotlinx.coroutines.delay(1_500)
            val latest = _state.value.draft
            _state.update { it.copy(pricing = true) }
            when (val result = repository.priceLines(latest)) {
                is NetworkResult.Success -> {
                    val priced = result.data.associateBy { it.id }
                    updateDraft { d -> d.copy(lines = d.lines.map { priced[it.id] ?: it }) }
                }
                // Not an error to the worker: priced when sent.
                is NetworkResult.Error -> android.util.Log.w("PoCreate", "priceLines: ${result.failure.message}")
                NetworkResult.Loading -> Unit
            }
            _state.update { it.copy(pricing = false) }
        }
    }

    // -- Create --------------------------------------------------------------------------------

    fun create() {
        val draft = _state.value.draft
        if (draft.vendorAccount.isBlank() || draft.lines.isEmpty() || _state.value.busy) return
        priceJob?.cancel()
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, pricing = false) }
            val id = repository.submitPurchase(userName, draft)
            val warehouse = currentWarehouse().ifBlank { draft.warehouseId }
            _state.update { PoCreateState(draft = PurchaseDraft(warehouseId = warehouse), submittedId = id, vendorCount = it.vendorCount, vendorsSavedAt = it.vendorsSavedAt) }
            loadVendors()
        }
    }

    fun discard() {
        priceJob?.cancel()
        viewModelScope.launch {
            repository.discardDraft(OrderKind.PURCHASE, userName)
            val warehouse = currentWarehouse().ifBlank { _state.value.draft.warehouseId }
            _state.update { it.copy(draft = PurchaseDraft(warehouseId = warehouse), error = null) }
        }
    }

    fun dismissResult() = _state.update { it.copy(submittedId = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

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

    private fun itemError(code: String, message: String): String =
        if (!online.value) "$code not saved - look it up online once." else message
}
