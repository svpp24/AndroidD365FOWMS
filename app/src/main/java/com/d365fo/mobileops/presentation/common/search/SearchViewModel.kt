package com.d365fo.mobileops.presentation.common.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.local.dao.ItemDao
import com.d365fo.mobileops.data.local.entity.FlowType
import com.d365fo.mobileops.data.repository.PendingFlowResume
import com.d365fo.mobileops.data.repository.SearchHit
import com.d365fo.mobileops.data.repository.SearchRepository
import com.d365fo.mobileops.data.repository.SearchResult
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.domain.repository.OnDemandLookupRepository
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

/** An item from this device's item list whose number or name matches what is typed. */
data class ItemSuggestion(val itemId: String, val itemName: String)

data class SearchState(
    val query: String = "",
    /** Items matching as the worker types - from the device, no network. */
    val suggestions: List<ItemSuggestion> = emptyList(),
    val busy: Boolean = false,
    /** The query the results below are for; null until a search ran. */
    val searched: String? = null,
    val result: SearchResult? = null,
    /** On-hand at this warehouse when the result is an item: available, on hand. */
    val available: BigDecimal? = null,
    val onHand: BigDecimal? = null,
    val error: String? = null,
    /** Why a tapped result didn't open - received in full, already shipped, posted. */
    val notice: String? = null,
)

/**
 * Home's search (20 Oct: "search items, orders, journals ... show data related to that item").
 * Typing suggests items from the device; a scan, Enter or a suggestion asks D365FO, which answers
 * a PO, transfer or counting journal number with that record, and an item with its open work at
 * this warehouse - POs to receive, transfers to ship or receive, journals to count.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val search: SearchRepository,
    private val lookups: OnDemandLookupRepository,
    private val itemDao: ItemDao,
    private val workerSession: WorkerSessionRepository,
    private val pendingFlowResume: PendingFlowResume,
    private val scannerBridge: HidScannerBridge,
    permissionDao: com.d365fo.mobileops.data.local.dao.PermissionDao,
    private val journalDao: com.d365fo.mobileops.data.local.dao.JournalDao,
    private val moduleSwitches: com.d365fo.mobileops.data.sync.ModuleSwitches,
) : ViewModel() {

    /**
     * A journal already on this device opens at its lines (as the list's View lines does);
     * one that isn't opens the journal list, which downloads it.
     */
    fun openJournal(journalId: String, go: (onDevice: Boolean) -> Unit) {
        viewModelScope.launch { go(journalDao.getJournal(journalId) != null) }
    }

    /** Menu access, as on Home: a result opens only a module this worker may use. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val blocked: StateFlow<Set<WarehouseWorkflow>> = workerSession.session
        .flatMapLatest { session ->
            val userName = session?.userName
            if (userName.isNullOrBlank()) flowOf(emptyList()) else permissionDao.observePermissions(userName)
        }
        .combine(moduleSwitches.disabled) { permissions, switchedOff ->
            com.d365fo.mobileops.domain.model.blockedWorkflows(
                allowed = permissions.filter { it.allowed }.map { it.workflow }.toSet(),
                hasRows = permissions.isNotEmpty(),
            ) + switchedOff.keys
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var suggest: Job? = null
    private var running: Job? = null

    init {
        viewModelScope.launch {
            scannerBridge.scans.collect { submit(it.value) }
        }
    }

    fun onCameraDecoded(value: String) = submit(value)

    fun setQuery(value: String) {
        _state.update { it.copy(query = value) }
        suggest?.cancel()
        val q = value.trim()
        if (q.length < 2) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggest = viewModelScope.launch {
            delay(150)
            val items = itemDao.searchItems(q).map { ItemSuggestion(it.itemId, it.itemName) }
            _state.update { if (it.query.trim() == q) it.copy(suggestions = items) else it }
        }
    }

    fun submit(value: String = _state.value.query) {
        val q = value.trim()
        if (q.length < 2) return
        val warehouse = workerSession.session.value?.selected?.warehouseId ?: return run {
            _state.update { it.copy(error = "No warehouse selected") }
        }
        suggest?.cancel()
        running?.cancel()
        _state.update { it.copy(query = q, suggestions = emptyList(), busy = true, error = null, notice = null) }
        running = viewModelScope.launch {
            when (val r = search.search(q, warehouse)) {
                is NetworkResult.Success -> {
                    val itemId = r.data.itemId
                    var available: BigDecimal? = null
                    var onHand: BigDecimal? = null
                    if (itemId != null) {
                        val rows = async { lookups.lookupOnHand(itemId, warehouse) }.await()
                        if (rows is NetworkResult.Success) {
                            available = rows.data.fold(BigDecimal.ZERO) { a, l -> a + l.available }
                            onHand = rows.data.fold(BigDecimal.ZERO) { a, l -> a + l.onHand }
                        }
                    }
                    if (itemId == null && r.data.hits.isEmpty()) {
                        // Not a whole number - part of an item number or name: offer the items it
                        // matches on this device rather than just "nothing found".
                        val items = itemDao.searchItems(q).map { ItemSuggestion(it.itemId, it.itemName) }
                        if (items.isNotEmpty()) {
                            _state.update { it.copy(busy = false, searched = q, result = null, suggestions = items) }
                            return@launch
                        }
                    }
                    _state.update {
                        it.copy(busy = false, searched = q, result = r.data, available = available, onHand = onHand)
                    }
                }
                is NetworkResult.Error -> _state.update {
                    it.copy(busy = false, searched = q, result = null, error = r.failure.message)
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    /**
     * Hands an order to its flow, as the Transfer orders screen does: a PO to receiving, a
     * transfer to ship or receive. False when there is nothing to do with it from here.
     */
    fun prepareOpen(hit: SearchHit): Boolean {
        val workflow = when (hit.kind) {
            com.d365fo.mobileops.data.repository.SearchHitKind.PO -> WarehouseWorkflow.PO_RECEIVING
            com.d365fo.mobileops.data.repository.SearchHitKind.TO ->
                if (hit.outbound) WarehouseWorkflow.TRANSFER_SHIP else WarehouseWorkflow.TRANSFER_RECEIVE
            com.d365fo.mobileops.data.repository.SearchHitKind.JOURNAL -> WarehouseWorkflow.INVENTORY_COUNT
        }
        moduleSwitches.messageFor(workflow)?.let { message ->
            _state.update { it.copy(notice = "${workflow.label}: $message") }
            return false
        }
        if (workflow in blocked.value) {
            _state.update { it.copy(notice = "${workflow.label} isn't on your menu") }
            return false
        }
        return openable(hit).also { ok ->
            _state.update { it.copy(notice = if (ok) null else "${hit.reference} is ${closedAs(hit)}") }
        }
    }

    private fun closedAs(hit: SearchHit): String = when (hit.kind) {
        com.d365fo.mobileops.data.repository.SearchHitKind.PO -> if (hit.status == "Received") "received in full" else "not open (${hit.status})"
        com.d365fo.mobileops.data.repository.SearchHitKind.TO -> if (hit.outbound) "already shipped" else "not shipped yet, or received"
        com.d365fo.mobileops.data.repository.SearchHitKind.JOURNAL -> "posted"
    }

    private fun openable(hit: SearchHit): Boolean = when (hit.kind) {
        com.d365fo.mobileops.data.repository.SearchHitKind.PO -> {
            // A line still to receive, or an order still open (Backorder) - not one received in full.
            val open = !hit.orderMatch || hit.status == "Backorder"
            if (open) pendingFlowResume.set(FlowType.RECEIVING, hit.reference)
            open
        }
        com.d365fo.mobileops.data.repository.SearchHitKind.TO -> {
            val open = if (hit.outbound) hit.status == "Created" else hit.status == "Shipped"
            if (open) pendingFlowResume.set(if (hit.outbound) FlowType.TRANSFER_SHIP else FlowType.TRANSFER_RECEIVE, hit.reference)
            open
        }
        com.d365fo.mobileops.data.repository.SearchHitKind.JOURNAL -> hit.status == "Open"
    }

    fun setScannerInterception(enabled: Boolean) {
        scannerBridge.enabled = enabled
    }
}
