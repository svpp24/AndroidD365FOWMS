package com.d365fo.mobileops.presentation.modules.cyclecounting.store

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import com.d365fo.mobileops.presentation.common.focus.BandKind
import com.d365fo.mobileops.presentation.common.focus.BandState
import com.d365fo.mobileops.presentation.common.focus.FocusButton
import com.d365fo.mobileops.presentation.common.focus.FocusCenter
import com.d365fo.mobileops.presentation.common.focus.FocusListRow
import com.d365fo.mobileops.presentation.common.focus.FocusScreen
import com.d365fo.mobileops.presentation.common.focus.HoldToConfirmButton
import com.d365fo.mobileops.presentation.common.focus.QuantityPadSheet
import com.d365fo.mobileops.presentation.common.focus.RowState
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.modules.stepflow.bandOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Check
import com.d365fo.mobileops.presentation.common.components.bleed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.counting.CountStagingRepository
import com.d365fo.mobileops.data.remote.dto.CountStageLineDto
import com.d365fo.mobileops.data.repository.OrderCreationRepository
import com.d365fo.mobileops.data.repository.OrderItem
import com.d365fo.mobileops.data.repository.OrderVariant
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.domain.repository.OnDemandLookupRepository
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar
import com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton
import com.d365fo.mobileops.presentation.common.components.ConfirmButton
import com.d365fo.mobileops.presentation.common.components.DialogButtonRow
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.components.QuantityPicker
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton as OutlinedButton
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.presentation.modules.ordercreate.VariantPickerDialog
import com.d365fo.mobileops.util.Formatters
import com.d365fo.mobileops.data.sync.SyncStatusProvider
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

/** One counted line of a store count, kept on the device until D365FO has it. */
@Serializable
data class StoreCountLine(
    val clientLineId: String,
    val itemId: String,
    val itemName: String,
    val configId: String = "",
    val sizeId: String = "",
    val colorId: String = "",
    val styleId: String = "",
    val batchId: String = "",
    val serialId: String = "",
    val locationId: String,
    val quantity: Double,
    val unitId: String,
    val countedAt: Long,
    val sent: Boolean = false,
    /** The bin it was counted in - for counting only (user, 8 Nov). */
    val binId: String = "",
)

/** The requested items D365FO lists for a hazard or blind count; kept on the device for offline counting. */
private fun List<com.d365fo.mobileops.data.counting.RequestedItem>.allows(itemId: String, location: String, variant: OrderVariant? = null): Boolean =
    isEmpty() || any { it.matches(itemId, location, variant?.configId, variant?.sizeId, variant?.colorId, variant?.styleId) }

/** The store count on this device: the stock count and staging journal, and the lines counted. */
@Serializable
data class StoreCountDraft(
    val warehouseId: String,
    val masterId: String = "",
    val stageId: String = "",
    val lines: List<StoreCountLine> = emptyList(),
    /**
     * Counting has started on the device. Offline the stock count is not opened yet ([stageId]
     * blank): it is opened in D365FO with the first Send that gets through (user, 8 Nov: counting
     * should work offline).
     */
    val started: Boolean = false,
    /** A hazard or blind count: what it asks for (empty = everything). */
    val requested: List<com.d365fo.mobileops.data.counting.RequestedItem> = emptyList(),
) {
    val counting: Boolean get() = started || stageId.isNotBlank()
}

/** An item scanned and waiting for its variant, batch or serial, or its quantity. */
data class PendingCount(
    val item: OrderItem,
    val variant: OrderVariant? = null,
    val batchId: String = "",
    val serialId: String = "",
) {
    val needsVariant: Boolean get() = item.hasProductDimensions && variant == null
    val needsBatch: Boolean get() = item.batchActive && batchId.isBlank()
    val needsSerial: Boolean get() = item.serialActive && serialId.isBlank()
}

/** Where Change location / Change bin started, so Cancel goes back to it. */
data class StoreCountPlace(val locationId: String, val binId: String)

data class StoreCountState(
    /** Wall to wall count, Hazard count or Blind count (user, 10 Nov). */
    val title: String = com.d365fo.mobileops.data.counting.CountType.WALL_TO_WALL.label,
    /** The hazard or blind count being counted; blank for the store's wall to wall count. */
    val countId: String = "",
    val warehouseId: String? = null,
    val draft: StoreCountDraft? = null,
    val locationId: String = "",
    /** The bin being counted; blank while one is asked for. */
    val binId: String = "",
    /** The store has bins: location, then bin, then items. None: location, then items. */
    val binsRequired: Boolean = false,
    /** Set while changing location or bin: Cancel goes back here. */
    val back: StoreCountPlace? = null,
    /** The counting menu's location, where a count starts. */
    val startLocation: String? = null,
    /** A hazard or blind count's items read before this device started counting. */
    val preview: List<com.d365fo.mobileops.data.counting.RequestedItem> = emptyList(),
    val pending: PendingCount? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val ended: Boolean = false,
    /** The supervisor transferred or cancelled this stock count while lines were still on the device. */
    val countClosed: Boolean = false,
    /** The store's locations, listed under the scan card on the location step (user, 9 Nov). */
    val knownLocations: List<String> = emptyList(),
    /** The store's bins, listed by location on the bin step. */
    val knownBins: List<com.d365fo.mobileops.data.local.entity.CountBinEntity> = emptyList(),
) {
    /** The bins of the current location - and any bin counted here that is not in the master. */
    val binsHere: List<String>
        get() = (knownBins.filter { it.isIn(locationId) }.map { it.binId } +
            draft?.lines.orEmpty().filter { it.locationId.equals(locationId, ignoreCase = true) && it.binId.isNotBlank() }.map { it.binId })
            .distinctBy { it.uppercase() }
    /** Lines counted in [location] (and in [bin] there). */
    fun countedAt(location: String, bin: String? = null): Int = draft?.lines?.count {
        it.locationId.equals(location, ignoreCase = true) && (bin == null || it.binId.equals(bin, ignoreCase = true))
    } ?: 0
    val unsent: Int get() = draft?.lines?.count { !it.sent } ?: 0
    val needsLocation: Boolean get() = locationId.isBlank()
    val needsBin: Boolean get() = !needsLocation && binsRequired && binId.isBlank()
    /** Lines counted in this location, and in this bin. */
    val atLocation: Int get() = draft?.lines?.count { it.locationId.equals(locationId, ignoreCase = true) } ?: 0
    val inBin: Int get() = draft?.lines?.count { it.locationId.equals(locationId, ignoreCase = true) && it.binId.equals(binId, ignoreCase = true) } ?: 0
    val requested: List<com.d365fo.mobileops.data.counting.RequestedItem> get() = draft?.requested?.takeIf { it.isNotEmpty() } ?: preview
    /** Requested rows with at least one line counted on this device. */
    val requestedDone: Int get() = requested.count { r ->
        draft?.lines.orEmpty().any { r.matches(it.itemId, it.locationId, it.configId, it.sizeId, it.colorId, it.styleId) }
    }
}

/**
 * Store count - a free count of the store for the Stock count control center (feedback, 11 Oct).
 * The counter scans a location, then each item there and its quantity; the lines are kept on the
 * device and staged in this counter's own journal in the store's stock count. The supervisor sees
 * every counter's lines in D365FO - items counted twice flagged - and transfers them into a
 * counting journal. Nothing here touches a counting journal itself.
 */
@HiltViewModel
class StoreCountViewModel @Inject constructor(
    private val staging: CountStagingRepository,
    private val items: OrderCreationRepository,
    private val lookup: OnDemandLookupRepository,
    private val secureStore: SecureStore,
    private val workerSession: WorkerSessionRepository,
    private val scannerBridge: HidScannerBridge,
    private val syncStatus: SyncStatusProvider,
    private val locationDao: com.d365fo.mobileops.data.local.dao.LocationDao,
    private val binRepository: com.d365fo.mobileops.data.counting.CountBinRepository,
    private val menuRepository: com.d365fo.mobileops.data.repository.HandheldMenuRepository,
    private val countRequests: com.d365fo.mobileops.data.counting.CountRequestRepository,
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val user = workerSession.session.value?.userName.orEmpty()
    private val warehouse = workerSession.session.value?.selected?.warehouseId

    /** A hazard or blind count chosen from its list; blank = the store's wall to wall count. */
    private val masterId: String = savedStateHandle.get<String>("masterId").orEmpty()
    private val countType = com.d365fo.mobileops.data.counting.CountType.fromSymbol(savedStateHandle.get<String>("type"))
        ?: com.d365fo.mobileops.data.counting.CountType.WALL_TO_WALL
    private val draftKey = if (masterId.isBlank()) {
        "store_count_draft::${warehouse.orEmpty().uppercase()}::${user.uppercase()}"
    } else {
        "count_draft::${masterId.uppercase()}::${user.uppercase()}"
    }

    /**
     * The counting menu's location: listed first on the location step, never picked for the
     * counter - every count goes location, then bin in it, then item (user, 10 Nov).
     */
    private val startLocation = menuRepository.menu.value?.countStartLocation

    private val _state = MutableStateFlow(
        loadDraft().let { draft ->
            StoreCountState(
                title = countType.label,
                countId = masterId,
                warehouseId = warehouse,
                draft = draft,
                startLocation = startLocation,
                // Every count, new or rejoined, starts on the location step (user, 10 Nov).
                locationId = "",
            )
        },
    )
    val state = _state.asStateFlow()

    /** The store's bins, with the location each is in. Kept on the device: works offline. */
    private var bins: Map<String, com.d365fo.mobileops.data.local.entity.CountBinEntity> = emptyMap()

    /** The last Send could not reach D365FO; it is retried when the connection is back. */
    private var waitingForConnection = false

    /** End count was pressed offline: the retry ends the count too. */
    private var endWhenOnline = false

    /** D365FO answers: checked before every call, so offline never waits for a timeout. */
    private var online = true

    init {
        viewModelScope.launch { scannerBridge.scans.collect { onScan(it.value) } }
        // A hazard or blind count's list, kept with the draft so counting works offline.
        if (masterId.isNotBlank()) viewModelScope.launch { refreshRequested() }
        warehouse?.let { wh ->
            viewModelScope.launch { binRepository.refresh(wh) }
            viewModelScope.launch {
                binRepository.observe(wh).collect { rows ->
                    bins = rows.associateBy { it.binId }
                    _state.update {
                        it.copy(
                            binsRequired = rows.isNotEmpty(),
                            knownBins = rows,
                            knownLocations = (it.knownLocations + rows.mapNotNull { b -> b.locationId?.takeIf(String::isNotBlank) })
                                .distinctBy { l -> l.uppercase() },
                        )
                    }
                }
            }
            // The store's locations from the device's copy, with any the bins name and the
            // count's start location - the list under the location step's scan card.
            viewModelScope.launch {
                val stored = runCatching { locationDao.search(wh, "", 200).map { it.locationId } }.getOrDefault(emptyList())
                _state.update { s ->
                    s.copy(
                        knownLocations = (listOfNotNull(startLocation) + stored + s.knownBins.mapNotNull { it.locationId?.takeIf(String::isNotBlank) })
                            .distinctBy { it.uppercase() },
                    )
                }
            }
        }
        // A Send that failed for want of a connection goes again by itself once D365FO answers -
        // the offline notice promises it (test, 13 Oct: the counter had to tap Send again).
        viewModelScope.launch {
            syncStatus.status.map { it.fullyOnline }.distinctUntilChanged().collect { up ->
                online = up
                val s = _state.value
                if (up && waitingForConnection && !s.busy && (s.unsent > 0 || endWhenOnline)) send(end = endWhenOnline)
            }
        }
    }

    /** Reads what the hazard or blind count asks for into the draft (once counting has started). */
    private suspend fun refreshRequested() {
        val rows = (countRequests.items(masterId) as? NetworkResult.Success)?.data ?: return
        _state.update { it.copy(preview = rows) }
        _state.value.draft?.let { saveDraft(it.copy(requested = rows)) } ?: run { pendingRequested = rows }
    }

    /** The list read before counting started; it goes into the draft when the count opens. */
    private var pendingRequested: List<com.d365fo.mobileops.data.counting.RequestedItem> = emptyList()

    private fun loadDraft(): StoreCountDraft? = runCatching {
        secureStore.getString(draftKey)?.let { json.decodeFromString(StoreCountDraft.serializer(), it) }
    }.getOrNull()

    private fun saveDraft(draft: StoreCountDraft?) {
        if (draft == null) secureStore.remove(draftKey) else secureStore.putString(draftKey, json.encodeToString(StoreCountDraft.serializer(), draft))
        _state.update { it.copy(draft = draft) }
    }

    fun onCameraDecoded(value: String) = scannerBridge.submitCameraScan(value)

    fun dismissMessage() = _state.update { it.copy(message = null, error = null) }

    /** Opens (or rejoins) this counter's journal in the store's stock count. */
    fun start() {
        val wh = warehouse ?: return _state.update { it.copy(error = "No warehouse is assigned to you.") }
        // Offline: count on the device now; the stock count is opened with the first Send.
        if (!online) return startOffline(wh)
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val opened = staging.open(wh, masterId = masterId)) {
                is NetworkResult.Success -> {
                    val current = _state.value.draft ?: StoreCountDraft(warehouseId = wh, requested = pendingRequested)
                    saveDraft(current.copy(masterId = opened.data.masterId, stageId = opened.data.stageId, started = true))
                    if (masterId.isNotBlank() && _state.value.requested.isEmpty()) refreshRequested()
                    _state.update {
                        it.copy(
                            busy = false,
                            message = null,
                        )
                    }
                }
                is NetworkResult.Error ->
                    if (opened.failure.retryable) startOffline(wh)
                    else _state.update { it.copy(busy = false, error = opened.failure.message) }
                NetworkResult.Loading -> _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun startOffline(wh: String) {
        val current = _state.value.draft ?: StoreCountDraft(warehouseId = wh, requested = pendingRequested)
        saveDraft(current.copy(started = true))
        waitingForConnection = true
        _state.update {
            it.copy(
                busy = false,
                error = null,
                message = "Offline - counting on this device. Counts go to D365FO when it answers.",
            )
        }
    }

    private fun nextStep(location: String, binsRequired: Boolean): String = when {
        location.isBlank() -> " Scan a location to start."
        binsRequired -> " Counting $location - scan a bin."
        else -> " Counting $location."
    }

    /** A scan: a location first (and whenever none is set), then items at it. */
    fun onScan(raw: String) {
        val value = raw.trim()
        if (value.isBlank() || _state.value.draft?.counting != true) return
        val s = _state.value
        val pending = s.pending
        val bin = bins[com.d365fo.mobileops.data.counting.normalizeBin(value)]
        when {
            pending != null && pending.needsSerial && !pending.needsVariant && !pending.needsBatch -> setSerial(value)
            pending != null && pending.needsBatch && !pending.needsVariant -> setBatch(value)
            s.needsLocation -> setLocation(value)
            bin != null -> setBin(bin)
            // A bin not on the store's list is counted as scanned (user, 9 Nov) - for this count
            // only; nothing is added to the bin master.
            s.needsBin -> setBin(
                com.d365fo.mobileops.data.local.entity.CountBinEntity(
                    warehouseId = warehouse.orEmpty(),
                    binId = com.d365fo.mobileops.data.counting.normalizeBin(value).ifBlank { value.uppercase() },
                    locationId = s.locationId,
                ),
            )
            else -> scanItem(value)
        }
    }

    private fun setBin(bin: com.d365fo.mobileops.data.local.entity.CountBinEntity) {
        val s = _state.value
        if (!bin.isIn(s.locationId)) {
            return _state.update {
                it.copy(message = null, error = "${bin.binId} is in ${bin.locationId}, not ${s.locationId}. Change location to count it.")
            }
        }
        _state.update { it.copy(binId = bin.binId, back = null, pending = null, error = null, message = null) }
    }

    /** A location tapped in the list under the scan card - the same as scanning it. */
    fun pickLocation(locationId: String) {
        if (!_state.value.busy) setLocation(locationId)
    }

    /** A bin tapped in the list under the scan card - the same as scanning it. */
    fun pickBin(binId: String) {
        val s = _state.value
        if (s.busy) return
        setBin(
            bins[com.d365fo.mobileops.data.counting.normalizeBin(binId)]
                ?: com.d365fo.mobileops.data.local.entity.CountBinEntity(warehouseId = warehouse.orEmpty(), binId = binId, locationId = s.locationId),
        )
    }

    /** Change location: scan another location (Damaged, Claim, Return...), then its bins. */
    fun changeLocation() {
        _state.update {
            it.copy(back = it.back ?: StoreCountPlace(it.locationId, it.binId), locationId = "", binId = "", pending = null, error = null, message = null)
        }
        sendInBackground()
    }

    /** Change bin: the next bin in the same location. */
    fun changeBin() {
        _state.update {
            it.copy(back = it.back ?: StoreCountPlace(it.locationId, it.binId), binId = "", pending = null, error = null, message = null)
        }
        sendInBackground()
    }

    /**
     * There is no Send button (user, 8 Nov): what was counted goes to the supervisor by itself
     * each time the counter moves on to another bin or location, and with End count. Offline it
     * stays on the device and goes when D365FO answers.
     */
    private fun sendInBackground() {
        val s = _state.value
        if (s.unsent == 0 || s.draft?.counting != true) return
        // Offline: they go when D365FO answers again (the connection watcher sends them).
        if (!online) {
            waitingForConnection = true
            return
        }
        if (!s.busy) send(end = false)
    }

    /** Cancel on Change location / Change bin: back where counting was, nothing changed. */
    fun cancelChange() = _state.update {
        val back = it.back ?: return@update it
        it.copy(locationId = back.locationId, binId = back.binId, back = null, error = null, message = null)
    }

    private fun setLocation(value: String) {
        val wh = warehouse ?: return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            // Offline: the device's copy of the store's locations; one not in it is taken as typed.
            if (!online) {
                val known = runCatching { locationDao.find(wh, value) ?: locationDao.find(wh, value.uppercase()) }.getOrNull()
                val id = known?.locationId ?: value.uppercase()
                _state.update { it.copy(busy = false, locationId = id, binId = "", back = null, message = null) }
                return@launch
            }
            val checked = lookup.lookupLocation(wh, value)
            val known = (checked as? NetworkResult.Success)?.data
            _state.update {
                when {
                    checked is NetworkResult.Success && known == null ->
                        it.copy(busy = false, error = "No location $value in $wh.")
                    else -> (known?.locationId ?: value.uppercase()).let { id ->
                        it.copy(busy = false, locationId = id, binId = "", back = null, message = null)
                    }
                }
            }
        }
    }

    private fun scanItem(value: String) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val found = items.findItem(value, warehouse.orEmpty(), offline = !online)) {
                is NetworkResult.Success -> {
                    val item = found.data
                    val variant = item.scannedVariant ?: if (!item.hasProductDimensions) OrderVariant() else item.variants.singleOrNull()
                    val s = _state.value
                    if (!s.requested.allows(item.itemId, s.locationId)) {
                        return@launch _state.update {
                            it.copy(busy = false, message = null, error = "${item.itemId} is not on this count's list here - count only the items asked for.")
                        }
                    }
                    _state.update { it.copy(busy = false, pending = PendingCount(item = item, variant = variant)) }
                }
                is NetworkResult.Error -> _state.update { it.copy(busy = false, error = found.failure.message) }
                NetworkResult.Loading -> _state.update { it.copy(busy = false) }
            }
        }
    }

    fun pickVariant(variant: OrderVariant) = _state.update { s -> s.copy(pending = s.pending?.copy(variant = variant)) }
    fun setBatch(value: String) = _state.update { s -> s.copy(pending = s.pending?.copy(batchId = value.trim())) }
    fun setSerial(value: String) = _state.update { s -> s.copy(pending = s.pending?.copy(serialId = value.trim())) }
    fun cancelPending() = _state.update { it.copy(pending = null) }

    /** The quantity for the pending item: one line on the device, sent with the next Send. */
    fun confirmQuantity(text: String) {
        val s = _state.value
        val pending = s.pending ?: return
        val draft = s.draft ?: return
        val qty = text.toDoubleOrNull() ?: return
        val variant = pending.variant ?: OrderVariant()
        if (!s.requested.allows(pending.item.itemId, s.locationId, variant.takeIf { pending.item.hasProductDimensions })) {
            return _state.update { it.copy(pending = null, error = "${pending.item.itemId} ${variant.label} is not on this count's list - not counted.") }
        }
        val line = StoreCountLine(
            clientLineId = UUID.randomUUID().toString(),
            itemId = pending.item.itemId,
            itemName = pending.item.itemName,
            configId = variant.configId,
            sizeId = variant.sizeId,
            colorId = variant.colorId,
            styleId = variant.styleId,
            batchId = pending.batchId,
            serialId = pending.serialId,
            locationId = s.locationId,
            quantity = qty,
            unitId = pending.item.inventUnit,
            countedAt = System.currentTimeMillis(),
            binId = s.binId,
        )
        saveDraft(draft.copy(lines = listOf(line) + draft.lines))
        _state.update { it.copy(pending = null, message = "${line.itemId} · ${qtyText(qty)} at ${line.place}") }
    }

    /** Removes a line that has not been sent yet. */
    fun remove(line: StoreCountLine) {
        val draft = _state.value.draft ?: return
        if (line.sent) return
        saveDraft(draft.copy(lines = draft.lines.filterNot { it.clientLineId == line.clientLineId }))
    }

    /** Sends the unsent lines; [end] also ends this counter's count. */
    fun send(end: Boolean) {
        val started = _state.value.draft ?: return
        if (!started.counting) return
        // Offline: kept on the device, and sent - ended too, if End count was pressed - by itself
        // when D365FO answers.
        if (!online) {
            waitingForConnection = true
            if (end) endWhenOnline = true
            _state.update {
                it.copy(
                    message = null,
                    error = "Offline - your counts are kept on this device and " +
                        (if (endWhenOnline) "the count is ended" else "sent") + " when D365FO answers.",
                )
            }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            // Started offline: the stock count is opened now, with this first Send.
            val draft = if (started.stageId.isNotBlank()) started else {
                when (val opened = staging.open(started.warehouseId, masterId = masterId)) {
                    is NetworkResult.Success -> started.copy(masterId = opened.data.masterId, stageId = opened.data.stageId)
                        .also { saveDraft(it) }
                    is NetworkResult.Error -> {
                        waitingForConnection = opened.failure.retryable
                        if (end && waitingForConnection) endWhenOnline = true
                        _state.update {
                            it.copy(
                                busy = false,
                                error = opened.failure.message + " Your counts are kept on this device" +
                                    if (waitingForConnection) " and sent when the connection is back." else ".",
                            )
                        }
                        return@launch
                    }
                    NetworkResult.Loading -> return@launch _state.update { it.copy(busy = false) }
                }
            }
            val unsent = draft.lines.filterNot { it.sent }
            val result = staging.submit(
                stageId = draft.stageId,
                submissionId = UUID.randomUUID().toString(),
                lines = unsent.map { it.toDto() },
                endCount = end,
            )
            when (result) {
                is NetworkResult.Success -> {
                    waitingForConnection = false
                    endWhenOnline = false
                    val sentIds = unsent.map { it.clientLineId }.toSet()
                    val updated = draft.copy(lines = draft.lines.map { if (it.clientLineId in sentIds) it.copy(sent = true) else it })
                    // The whole stock count's figure, every counter's lines - not just these (test, 8 Nov).
                    val dupNote = (if (result.data.duplicates > 0) " ${result.data.duplicates} item(s) in ${draft.masterId} were counted more than once - your supervisor decides." else "") +
                        (if (result.data.rejected > 0) " ${result.data.rejected} line(s) not on the count's list were left out." else "")
                    if (end) {
                        saveDraft(null)
                        _state.update { it.copy(busy = false, ended = true, message = "Ended - ${draft.lines.size} line(s) sent.$dupNote") }
                    } else {
                        saveDraft(updated)
                        _state.update { it.copy(busy = false, message = "${result.data.accepted} line(s) sent.$dupNote") }
                    }
                }
                is NetworkResult.Error -> {
                    waitingForConnection = result.failure is AppFailure.Offline || result.failure is AppFailure.Transient
                    if (end && waitingForConnection) endWhenOnline = true
                    val code = (result.failure as? AppFailure.BusinessRule)?.d365ErrorCode
                    val closed = code == "COUNT_TRANSFERRED" || code == "COUNT_CANCELLED"
                    if (closed && unsent.isEmpty()) {
                        // Everything was already sent: nothing to keep or move, the count is over (test, 13 Oct:
                        // End count on a cancelled count offered "Move 0 line(s)" and could not be left).
                        saveDraft(null)
                        _state.update { it.copy(busy = false, ended = true, message = result.failure.message.substringBefore(" - ") + ".") }
                    } else {
                        _state.update {
                            it.copy(
                                busy = false,
                                message = null,
                                error = result.failure.message + " Your counts are kept on this device" +
                                    if (waitingForConnection) " and sent when the connection is back." else ".",
                                countClosed = closed,
                            )
                        }
                    }
                }
                NetworkResult.Loading -> _state.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * The stock count was transferred or cancelled with lines still on the device (test, 13 Oct:
     * every Send was then refused and the lines were stuck): they move to the store's next stock
     * count - D365FO opens one when none is open - and the lines already sent stay with the old one.
     */
    fun moveToNewCount() {
        val draft = _state.value.draft ?: return
        // A hazard or blind count has no "next" count: once it is closed, it is over here.
        if (masterId.isNotBlank()) {
            saveDraft(null)
            return _state.update { it.copy(countClosed = false, ended = true, error = null, message = "$masterId is closed - lines not sent were not added.") }
        }
        saveDraft(draft.copy(masterId = "", stageId = "", lines = draft.lines.filterNot { it.sent }))
        _state.update { it.copy(countClosed = false, error = null, message = null) }
        start()
    }

    private fun StoreCountLine.toDto() = CountStageLineDto(
        clientLineId = clientLineId,
        itemId = itemId,
        configId = configId.ifBlank { null },
        sizeId = sizeId.ifBlank { null },
        colorId = colorId.ifBlank { null },
        styleId = styleId.ifBlank { null },
        batchId = batchId.ifBlank { null },
        serialId = serialId.ifBlank { null },
        locationId = locationId.ifBlank { null },
        binId = binId.ifBlank { null },
        quantity = quantity,
        unitId = unitId,
        countedAtUtc = Formatters.isoUtc(countedAt),
    )
}

/** "AAC > BIN-A1", or the location alone. */
private val StoreCountLine.place: String get() = if (binId.isBlank()) locationId else "$locationId › $binId"

/**
 * Store count in the Focus design (brief sections 6 and 9), on the existing steps: location, bin
 * (where the store has bins), then items and their quantity. The band says what the last scan
 * did, the centre shows the place or the last line counted, and the lists of locations, bins and
 * counted lines open on demand from the bottom button. A step shows its caption, never "Scan ...".
 */
@Composable
fun StoreCountScreen(onBack: () -> Unit, viewModel: StoreCountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    var showList by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showItems by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val draft = state.draft
    val counting = draft?.counting == true && !state.ended

    if (showCamera) {
        com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet(
            onDecoded = { viewModel.onCameraDecoded(it); showCamera = false },
            onDismiss = { showCamera = false },
        )
    }
    val pending = state.pending
    if (pending != null && pending.needsVariant) {
        VariantPickerDialog(item = pending.item, onPick = viewModel::pickVariant, onDismiss = viewModel::cancelPending)
    }

    if (showList && counting && draft != null) {
        androidx.activity.compose.BackHandler { showList = false }
        StoreCountList(
            state = state,
            draft = draft,
            onClose = { showList = false },
            onPickLocation = { showList = false; viewModel.pickLocation(it) },
            onPickBin = { showList = false; viewModel.pickBin(it) },
            onRemove = viewModel::remove,
        )
        return
    }

    if (showItems && state.requested.isNotEmpty()) {
        androidx.activity.compose.BackHandler { showItems = false }
        RequestedItemsList(state = state, onClose = { showItems = false })
        return
    }

    val padOpen = pending != null && !pending.needsVariant && !pending.needsBatch && !pending.needsSerial
    FocusScreen(
        title = state.title,
        onBack = onBack,
        counter = when {
            // A hazard or blind count's progress stays in view on every step (user, 10 Nov).
            counting && state.requested.isNotEmpty() -> "${state.requestedDone}/${state.requested.size}"
            !counting || state.needsLocation -> null
            state.binId.isNotBlank() -> state.binId
            else -> state.locationId
        },
        band = storeCountBand(state),
        remaining = if (counting && draft != null) sentLine(draft) else null,
        scanPill = if (counting && !state.countClosed) {
            { com.d365fo.mobileops.presentation.common.focus.ScanPillField(enabled = !state.busy) }
        } else {
            null
        },
        topBarTrailing = if (!counting) null else { {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More", tint = FocusColors.ink)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Camera scan") },
                        leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                        enabled = !state.busy,
                        onClick = { menuOpen = false; showCamera = true },
                        modifier = Modifier.heightIn(min = 56.dp),
                    )
                    if (!state.needsLocation) {
                        DropdownMenuItem(
                            text = { Text("Change location") },
                            leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                            enabled = !state.busy,
                            onClick = { menuOpen = false; viewModel.changeLocation() },
                            modifier = Modifier.heightIn(min = 56.dp),
                        )
                    }
                    if (state.binsRequired && !state.needsLocation && !state.needsBin) {
                        DropdownMenuItem(
                            text = { Text("Change bin") },
                            leadingIcon = { Icon(Icons.Default.Inventory2, contentDescription = null) },
                            enabled = !state.busy,
                            onClick = { menuOpen = false; viewModel.changeBin() },
                            modifier = Modifier.heightIn(min = 56.dp),
                        )
                    }
                }
            }
        } },
        actions = storeCountActions(
            state = state,
            counting = counting,
            onStart = viewModel::start,
            onDone = onBack,
            onMove = viewModel::moveToNewCount,
            onUndo = viewModel::cancelPending,
            onCancelChange = viewModel::cancelChange,
            onList = { showList = true },
            onItems = { showItems = true },
            onEnd = { viewModel.send(end = true) },
        ),
        overlay = if (padOpen) { {
            QuantityPadSheet(
                initial = if (pending!!.item.serialActive) "1" else "",
                onSet = viewModel::confirmQuantity,
                onDismiss = viewModel::cancelPending,
                allowDecimal = true,
            )
        } } else {
            null
        },
    ) {
        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
        }
        StoreCountCenter(state, counting)
    }
}

/** What the last action did; with nothing to report, where counting stands. */
internal fun storeCountBand(state: StoreCountState): BandState? {
    val message = state.message
    val error = state.error
    return when {
        state.ended -> BandState(BandKind.Ok, "Count ended", message)
        error != null -> if (error.startsWith("Offline")) bandOf(BandKind.Warn, error) else bandOf(BandKind.Bad, error)
        message != null -> when {
            message.startsWith("Offline") -> bandOf(BandKind.Warn, message)
            message.contains("line(s) sent") -> BandState(BandKind.Ok, "Sent", message)
            else -> BandState(BandKind.Ok, "Counted", message)
        }
        state.draft?.counting != true -> null
        state.back != null -> BandState(
            BandKind.Info,
            "Changing place",
            "from " + listOfNotNull(state.back?.locationId, state.back?.binId).filter { it.isNotBlank() }.joinToString(" › "),
        )
        state.needsLocation -> BandState(
            BandKind.Info,
            "Count open",
            state.draft?.masterId.orEmpty().ifBlank { "On this device" }.let { "$it · ${state.warehouseId.orEmpty()}" }.trimEnd(' ', '·'),
        )
        else -> BandState(
            BandKind.Info,
            (if (state.binId.isBlank()) state.locationId else "${state.locationId} › ${state.binId}") + " open",
            state.draft?.masterId?.takeIf { it.isNotBlank() }?.let { "Count $it" },
        )
    }
}

/** "3 on this device · 7 sent" - how much is counted, never what to count. */
private fun sentLine(draft: StoreCountDraft): String? {
    val sent = draft.lines.count { it.sent }
    val unsent = draft.lines.size - sent
    return listOfNotNull(
        "$unsent on this device".takeIf { unsent > 0 },
        "$sent sent".takeIf { sent > 0 },
    ).joinToString(" · ").ifEmpty { null }
}

private fun storeCountActions(
    state: StoreCountState,
    counting: Boolean,
    onStart: () -> Unit,
    onDone: () -> Unit,
    onMove: () -> Unit,
    onUndo: () -> Unit,
    onCancelChange: () -> Unit,
    onList: () -> Unit,
    onItems: () -> Unit,
    onEnd: () -> Unit,
): (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val pending = state.pending
    val hasItems = state.requested.isNotEmpty()
    return {
        when {
            state.ended -> FocusButton("Done", onClick = onDone, primary = true, modifier = Modifier.weight(1f))
            !counting -> {
                // A hazard or blind count: its items can be read before starting (user, 10 Nov).
                if (hasItems) {
                    FocusButton("Items", onClick = onItems, icon = Icons.AutoMirrored.Filled.ListAlt, enabled = !state.busy, modifier = Modifier.weight(1f))
                }
                FocusButton(
                if (state.busy) "Starting…" else "New count",
                onClick = onStart,
                primary = true,
                icon = Icons.Default.Add,
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            )
            }
            state.countClosed -> FocusButton(
                "Move ${state.unsent} line(s) to a new count",
                onClick = onMove,
                primary = true,
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            )
            pending != null && (pending.needsBatch || pending.needsSerial) -> FocusButton(
                "Undo", onClick = onUndo, icon = Icons.AutoMirrored.Filled.Undo, modifier = Modifier.weight(1f),
            )
            state.needsLocation || state.needsBin -> {
                if (state.back != null) {
                    FocusButton("Cancel", onClick = onCancelChange, enabled = !state.busy, modifier = Modifier.weight(1f))
                } else if (hasItems) {
                    FocusButton("Items", onClick = onItems, icon = Icons.AutoMirrored.Filled.ListAlt, enabled = !state.busy, modifier = Modifier.weight(1f))
                }
                FocusButton(
                    if (state.needsLocation) "Locations" else "Bins",
                    onClick = onList,
                    icon = if (state.needsLocation) Icons.Default.LocationOn else Icons.Default.Inventory2,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                )
            }
            else -> {
                if (hasItems || state.draft?.lines?.isNotEmpty() == true) {
                    FocusButton(
                        if (hasItems) "Items" else "Lines", onClick = onList, icon = Icons.AutoMirrored.Filled.ListAlt,
                        enabled = !state.busy, modifier = Modifier.weight(1f),
                    )
                }
                HoldToConfirmButton(
                    "Hold to end count",
                    onConfirm = onEnd,
                    enabled = !state.busy && state.draft?.lines?.isNotEmpty() == true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The centre: the store before a count, the place being chosen, the item in hand, or the last line counted. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.StoreCountCenter(state: StoreCountState, counting: Boolean) {
    val draft = state.draft
    val pending = state.pending
    when {
        state.ended -> FocusCenter(caption = state.title, code = "Ended", description = state.countId.ifBlank { state.warehouseId })
        !counting || draft == null -> FocusCenter(
            caption = state.title,
            code = state.countId.ifBlank { state.warehouseId ?: "–" },
            description = if (state.countId.isBlank()) "No count open" else "Not started on this device",
        )
        pending != null && !pending.needsVariant -> FocusCenter(
            caption = when {
                pending.needsBatch -> "Batch"
                pending.needsSerial -> "Serial"
                else -> "Quantity"
            },
            code = pending.item.itemId,
            description = listOfNotNull(
                pending.item.itemName.takeIf { it.isNotBlank() && !it.equals(pending.item.itemId, ignoreCase = true) },
                pending.variant?.takeIf { pending.item.hasProductDimensions }?.label,
                pending.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                pending.serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" },
            ).joinToString(" · ").ifEmpty { null },
            unitLine = pending.item.inventUnit.takeIf { it.isNotBlank() },
        )
        state.needsLocation -> {
            val places = draft.lines.map { it.locationId.uppercase() }.distinct().size
            FocusCenter(
                caption = "Location",
                code = if (places == 1) "1 location" else "$places locations",
                description = "counted · ${linesText(draft.lines.size)}",
            )
        }
        state.needsBin -> {
            val bins = draft.lines.filter { it.locationId.equals(state.locationId, ignoreCase = true) && it.binId.isNotBlank() }
                .map { it.binId.uppercase() }.distinct().size
            FocusCenter(
                caption = "Bin",
                code = if (bins == 1) "1 bin" else "$bins bins",
                description = "counted in ${state.locationId}",
            )
        }
        else -> {
            val last = draft.lines.firstOrNull {
                it.locationId.equals(state.locationId, ignoreCase = true) && it.binId.equals(state.binId, ignoreCase = true)
            }
            if (last == null) {
                FocusCenter(caption = "Counted here", code = linesText(0))
            } else {
                FocusCenter(
                    caption = "Last counted",
                    code = last.itemId,
                    description = listOfNotNull(
                        last.itemName.takeIf { it.isNotBlank() && !it.equals(last.itemId, ignoreCase = true) },
                        OrderVariant(last.configId, last.sizeId, last.colorId, last.styleId).takeIf { v -> v != OrderVariant() }?.label,
                        last.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                        last.serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" },
                    ).joinToString(" · ").ifEmpty { null },
                    quantity = qtyText(last.quantity),
                    unitLine = listOfNotNull(
                        last.unitId.takeIf { it.isNotBlank() },
                        if (last.sent) "With the supervisor" else "On this device",
                        "${state.inBin.takeIf { state.binsRequired } ?: state.atLocation} here",
                    ).joinToString(" · "),
                )
            }
        }
    }
}

private fun linesText(n: Int) = if (n == 1) "1 line" else "$n lines"

/**
 * The lists on demand: the store's locations on the location step, the location's bins on the
 * bin step (tap = the same as scanning it), and the lines counted once items are being counted -
 * a line not sent yet can be removed.
 */
@Composable
private fun StoreCountList(
    state: StoreCountState,
    draft: StoreCountDraft,
    onClose: () -> Unit,
    onPickLocation: (String) -> Unit,
    onPickBin: (String) -> Unit,
    onRemove: (StoreCountLine) -> Unit,
) {
    val title = when {
        state.needsLocation -> "Locations"
        state.needsBin -> "Bins in ${state.locationId}"
        else -> "Counted lines"
    }
    FocusScreen(
        title = title,
        onBack = onClose,
        counter = when {
            state.needsLocation -> state.knownLocations.size.toString()
            state.needsBin -> state.binsHere.size.toString()
            else -> draft.lines.size.toString()
        },
    ) {
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.needsLocation || state.needsBin -> {
                    val picking = if (state.needsLocation) state.knownLocations else state.binsHere
                    if (picking.isEmpty()) {
                        item {
                            Text(
                                if (state.needsLocation) "No locations on this device." else "No bins listed here.",
                                style = com.d365fo.mobileops.presentation.common.theme.FocusType.description,
                                color = FocusColors.muted,
                                modifier = Modifier.padding(20.dp),
                            )
                        }
                    }
                    items(picking, key = { it.uppercase() }) { id ->
                        val counted = if (state.needsLocation) state.countedAt(id) else state.countedAt(state.locationId, id)
                        FocusListRow(
                            title = id,
                            titleIsCode = true,
                            state = if (counted > 0) RowState.Done else RowState.Open,
                            count = if (counted > 0) linesText(counted) else null,
                            chevron = true,
                            onClick = { if (state.needsLocation) onPickLocation(id) else onPickBin(id) },
                        )
                    }
                }
                else -> {
                    // A hazard or blind count: what it asks for, ticked off as it is counted.
                    if (draft.requested.isNotEmpty()) {
                        item(key = "requested-caption") {
                            com.d365fo.mobileops.presentation.common.focus.FocusCaption(
                                "To count · ${state.requestedDone} of ${draft.requested.size} done",
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(draft.requested.size, key = { "requested-$it" }) { index ->
                            RequestedRow(draft.requested[index], draft.lines)
                        }
                        item(key = "lines-caption") {
                            com.d365fo.mobileops.presentation.common.focus.FocusCaption(
                                "Counted lines",
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                    }
                    items(draft.lines, key = { it.clientLineId }) { line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FocusListRow(
                            title = line.itemId,
                            titleIsCode = true,
                            state = if (line.sent) RowState.Done else RowState.Open,
                            subtitle = listOfNotNull(
                                line.itemName.takeIf { it.isNotBlank() && !it.equals(line.itemId, ignoreCase = true) },
                                OrderVariant(line.configId, line.sizeId, line.colorId, line.styleId).takeIf { v -> v != OrderVariant() }?.label,
                                line.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                                line.serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" },
                                line.place,
                                if (line.sent) "With the supervisor" else "On this device",
                            ).joinToString(" · "),
                            count = qtyText(line.quantity) + line.unitId.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty(),
                            modifier = Modifier.weight(1f),
                        )
                        if (!line.sent) {
                            IconButton(onClick = { onRemove(line) }, modifier = Modifier.size(56.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = FocusColors.badIcon)
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

private fun qtyText(qty: Double): String = java.math.BigDecimal.valueOf(qty).stripTrailingZeros().toPlainString()

/** Store count samples for the layout test: 0 new, 1 location step, 2 counted, 3 quantity pad, 4 error. */
@Composable
internal fun StoreCountFocusSample(kind: Int) {
    val line = StoreCountLine(
        clientLineId = "1", itemId = "BSR-11397-0", itemName = "Panadol Extra 24s", locationId = "AAC",
        quantity = 3.0, unitId = "Pack", countedAt = 0L, binId = "BIN-A1",
    )
    val draft = StoreCountDraft(warehouseId = "AAC", masterId = "AAC-20261108-3", stageId = "S1", lines = listOf(line), started = true)
    val state = when (kind) {
        0 -> StoreCountState(warehouseId = "AAC")
        1 -> StoreCountState(warehouseId = "AAC", draft = draft, binsRequired = true)
        3 -> StoreCountState(
            warehouseId = "AAC", draft = draft, locationId = "AAC", binId = "BIN-A1", binsRequired = true,
            pending = PendingCount(
                item = OrderItem("HSN002", "Handset charger 20W", "Each", "Each", false, false, false, false, false, false, 1.0, 0.0, 0.0, 1.0, emptyList(), null),
                variant = OrderVariant(),
            ),
        )
        4 -> StoreCountState(
            warehouseId = "AAC", draft = draft, locationId = "AAC", binId = "BIN-A1", binsRequired = true,
            error = "BIN-B4 is in DAMAGED, not AAC. Change location to count it.",
        )
        else -> StoreCountState(
            warehouseId = "AAC", draft = draft, locationId = "AAC", binId = "BIN-A1", binsRequired = true,
            message = "BSR-11397-0 · 3 at AAC › BIN-A1",
        )
    }
    val counting = state.draft?.counting == true
    FocusScreen(
        title = "Store count",
        onBack = {},
        counter = if (counting && !state.needsLocation) state.binId.ifBlank { state.locationId } else null,
        band = storeCountBand(state),
        remaining = state.draft?.let { sentLine(it) },
        scanPill = if (counting) { { com.d365fo.mobileops.presentation.common.focus.ScanPill(onTap = {}) } } else null,
        actions = storeCountActions(state, counting, {}, {}, {}, {}, {}, {}, {}, {}),
        overlay = if (kind == 3) { { QuantityPadSheet(initial = "", onSet = {}, onDismiss = {}, allowDecimal = true) } } else null,
    ) {
        StoreCountCenter(state, counting)
    }
}

/** One item a hazard or blind count asks for, ticked once this device has counted it. */
@Composable
private fun RequestedRow(r: com.d365fo.mobileops.data.counting.RequestedItem, lines: List<StoreCountLine>) {
    val done = lines.any { r.matches(it.itemId, it.locationId, it.configId, it.sizeId, it.colorId, it.styleId) }
    FocusListRow(
        title = r.itemId.ifBlank { "Everything at ${r.locationId}" },
        titleIsCode = r.itemId.isNotBlank(),
        state = if (done) RowState.Done else RowState.Open,
        subtitle = listOfNotNull(
            r.itemName.takeIf { it.isNotBlank() && !it.equals(r.itemId, ignoreCase = true) },
            listOf(r.configId, r.sizeId, r.colorId, r.styleId).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { null },
            r.locationId.takeIf { it.isNotBlank() && r.itemId.isNotBlank() }?.let { "at $it" },
        ).joinToString(" · ").ifBlank { null },
        count = if (done) "Counted" else null,
    )
}

/** The items a hazard or blind count asks for - from the start screen and the location and bin steps (user, 10 Nov). */
@Composable
private fun RequestedItemsList(state: StoreCountState, onClose: () -> Unit) {
    val lines = state.draft?.lines.orEmpty()
    FocusScreen(
        title = "Items to count",
        onBack = onClose,
        counter = "${state.requestedDone}/${state.requested.size}",
    ) {
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(state.requested.size, key = { "requested-$it" }) { index ->
                RequestedRow(state.requested[index], lines)
            }
        }
    }
}
