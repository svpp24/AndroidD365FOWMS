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

/** A transfer line waiting for its variant, stock row and quantity. */
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
    /** False while From / To are being chosen. */
    val routeChosen: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val choosingVariant: OrderItem? = null,
    val pending: PendingTransferLine? = null,
    val enteringQty: Boolean = false,
    val editing: DraftLine? = null,
    val submittedId: String? = null,
    /**
     * Transfers still to ship from this warehouse, from D365FO - null until known (offline).
     * "Ship it now" was offered on every order made here, shipped or not, and on ones that
     * ship from another warehouse (TO-33, AAS → AAC, shown at AAC after it was received).
     */
    val shippableIds: Set<String>? = null,
)

/**
 * TO creation: From and To from the worker's own warehouses, then scan items. Online, the stock
 * at From is shown per batch and serial and a quantity above it is refused here; offline it is
 * not known, and D365FO refuses a short order when it is sent.
 */
@HiltViewModel
class ToCreateViewModel @Inject constructor(
    private val repository: OrderCreationRepository,
    private val sessions: WorkerSessionRepository,
    private val scannerBridge: HidScannerBridge,
    private val pendingFlowResume: com.d365fo.mobileops.data.repository.PendingFlowResume,
    private val transferList: com.d365fo.mobileops.data.repository.TransferListRepository,
    syncStatusProvider: SyncStatusProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(ToCreateState())
    val state: StateFlow<ToCreateState> = _state.asStateFlow()

    val online: StateFlow<Boolean> = syncStatusProvider.status.map { it.fullyOnline }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val orders: StateFlow<List<CreatedOrderEntity>> = repository.observeOrders(OrderKind.TRANSFER)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val userName: String get() = sessions.session.value?.userName.orEmpty()

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
        viewModelScope.launch { scannerBridge.scans.collect { onCode(it.value) } }
        refreshShippable()
    }

    private fun refreshShippable() {
        val warehouseId = sessions.session.value?.selected?.warehouseId?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            (transferList.openTransferOrders(warehouseId) as? NetworkResult.Success)
                ?.data?.let { open ->
                    _state.update { s -> s.copy(shippableIds = open.filter { it.outbound }.map { it.transferId.uppercase() }.toSet()) }
                }
        }
    }

    fun onCameraDecoded(value: String) = scannerBridge.submitCameraScan(value)

    // -- Route -----------------------------------------------------------------------------------

    fun pickFrom(warehouseId: String) = updateDraft {
        val to = if (it.toWarehouseId == warehouseId) "" else it.toWarehouseId
        it.copy(fromWarehouseId = warehouseId, toWarehouseId = to.ifBlank { onlyOtherWarehouse(_state.value.warehouses, warehouseId) })
    }

    /** The To warehouse when the worker has just one other - nothing to choose. */
    private fun onlyOtherWarehouse(warehouses: List<AssignedWarehouse>, from: String): String =
        warehouses.filter { it.warehouseId != from }.singleOrNull()?.warehouseId.orEmpty()

    fun pickTo(warehouseId: String) = updateDraft { it.copy(toWarehouseId = warehouseId) }

    fun confirmRoute() {
        val d = _state.value.draft
        if (d.fromWarehouseId.isBlank() || d.toWarehouseId.isBlank() || d.fromWarehouseId == d.toWarehouseId) return
        _state.update { it.copy(routeChosen = true) }
    }

    fun changeRoute() {
        if (_state.value.draft.lines.isEmpty()) _state.update { it.copy(routeChosen = false) }
    }

    // -- Lines -----------------------------------------------------------------------------------

    fun onCode(code: String) {
        val s = _state.value
        if (!s.routeChosen || s.busy || s.pending != null || s.choosingVariant != null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (val result = repository.findItem(code, _state.value.draft.fromWarehouseId)) {
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

    /** Asks D365FO what is at From (online); then a batch / serial choice when tracked, then the quantity. */
    private suspend fun withVariant(item: OrderItem, variant: OrderVariant) {
        val from = _state.value.draft.fromWarehouseId
        val stock = if (online.value) {
            (repository.stock(item.itemId, variant, from) as? NetworkResult.Success)?.data
        } else {
            null
        }
        // An item allowed physical negative inventory may be transferred without stock on hand,
        // as D365FO allows - so neither this refusal nor the quantity cap applies to it.
        if (stock != null && stock.isEmpty() && !item.negativePhysical) {
            _state.update { it.copy(busy = false, error = "No stock of ${item.itemId} ${variant.label} at $from".trim()) }
            return
        }
        val tracked = item.batchActive || item.serialActive
        val pending = PendingTransferLine(item, variant, stock)
        _state.update {
            when {
                stock != null && tracked -> it.copy(busy = false, pending = pending.copy(choosingStock = true))
                else -> it.copy(busy = false, pending = pending, enteringQty = true)
            }
        }
    }

    fun pickStock(row: StockRow?) {
        val pending = _state.value.pending ?: return
        _state.update { it.copy(pending = pending.copy(pick = row, choosingStock = false), enteringQty = true) }
    }

    fun cancelPending() = _state.update { it.copy(choosingVariant = null, pending = null, enteringQty = false) }

    /** What the quantity may be: the stock picked (or all of it), when D365FO could be asked. */
    fun quantityRules(pending: PendingTransferLine): QuantityRules {
        val available = if (pending.item.negativePhysical) null else pending.pick?.available ?: pending.stock?.sumOf { it.available }
        val alreadyOnOrder = _state.value.draft.lines
            .filter { it.itemId == pending.item.itemId && it.variant == pending.variant && it.batchId == pending.pick?.batchId.orEmpty() && it.serialId == pending.pick?.serialId.orEmpty() }
            .sumOf { it.qty }
        return QuantityRules(available = available?.let { (it - alreadyOnOrder).coerceAtLeast(0.0) })
    }

    fun addLine(qty: Double) {
        val pending = _state.value.pending ?: return
        val line = DraftLine(
            itemId = pending.item.itemId,
            itemName = pending.item.itemName,
            variant = pending.variant ?: OrderVariant(),
            qty = qty,
            unitId = pending.item.inventUnit,
            batchId = pending.pick?.batchId.orEmpty(),
            serialId = pending.pick?.serialId.orEmpty(),
            available = pending.pick?.available ?: pending.stock?.sumOf { it.available },
            allowsNegative = pending.item.negativePhysical,
        )
        _state.update { it.copy(pending = null, enteringQty = false) }
        updateDraft { d -> d.copy(lines = d.lines + line) }
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

    // -- Create ----------------------------------------------------------------------------------

    fun create() {
        val draft = _state.value.draft
        if (draft.lines.isEmpty() || _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val id = repository.submitTransfer(userName, draft)
            _state.update {
                ToCreateState(
                    draft = TransferDraft(
                        fromWarehouseId = draft.fromWarehouseId,
                        toWarehouseId = onlyOtherWarehouse(it.warehouses, draft.fromWarehouseId),
                    ),
                    warehouses = it.warehouses,
                    submittedId = id,
                )
            }
        }
    }

    fun discard() {
        viewModelScope.launch {
            repository.discardDraft(OrderKind.TRANSFER, userName)
            val from = _state.value.draft.fromWarehouseId
            _state.update {
                it.copy(
                    draft = TransferDraft(fromWarehouseId = from, toWarehouseId = onlyOtherWarehouse(it.warehouses, from)),
                    routeChosen = false,
                    error = null,
                )
            }
        }
    }

    fun dismissResult() {
        _state.update { it.copy(submittedId = null) }
        refreshShippable()
    }

    /** Hands the created order to Transfer - Ship; the caller navigates there. */
    fun ship(transferId: String) =
        pendingFlowResume.set(com.d365fo.mobileops.data.local.entity.FlowType.TRANSFER_SHIP, transferId)

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun updateDraft(change: (TransferDraft) -> TransferDraft) {
        _state.update { it.copy(draft = change(it.draft)) }
        val draft = _state.value.draft
        viewModelScope.launch {
            if (draft.lines.isEmpty()) repository.discardDraft(OrderKind.TRANSFER, userName)
            else repository.saveTransferDraft(userName, draft)
        }
    }
}
