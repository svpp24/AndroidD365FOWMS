package com.d365fo.mobileops.presentation.modules.cyclecounting.store

import com.d365fo.mobileops.presentation.common.focus.FocusTask
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Place
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
    /**
     * Lines per location, and per location and bin - counted once per draft, not once per row of
     * the location and bin lists (performance plan, phase 3: rows x lines on every recomposition).
     */
    private val linesByPlace: Map<String, Int> by lazy {
        val counts = HashMap<String, Int>()
        draft?.lines.orEmpty().forEach { line ->
            counts.merge(placeKey(line.locationId, null), 1, Int::plus)
            counts.merge(placeKey(line.locationId, line.binId), 1, Int::plus)
        }
        counts
    }

    /** The draft's lines by item number, for [isRequestedDone]. */
    private val linesByItem: Map<String, List<StoreCountLine>> by lazy {
        draft?.lines.orEmpty().groupBy { it.itemId.uppercase() }
    }

    /** Lines counted in [location] (and in [bin] there). */
    fun countedAt(location: String, bin: String? = null): Int = linesByPlace[placeKey(location, bin)] ?: 0
    val unsent: Int get() = draft?.lines?.count { !it.sent } ?: 0
    val needsLocation: Boolean get() = locationId.isBlank()
    val needsBin: Boolean get() = !needsLocation && binsRequired && binId.isBlank()
    /** Lines counted in this location, and in this bin. */
    val atLocation: Int get() = countedAt(locationId)
    val inBin: Int get() = countedAt(locationId, binId)
    val requested: List<com.d365fo.mobileops.data.counting.RequestedItem> get() = draft?.requested?.takeIf { it.isNotEmpty() } ?: preview
    /** Whether this device has counted a line for [r]: only the lines of its item are checked. */
    fun isRequestedDone(r: com.d365fo.mobileops.data.counting.RequestedItem): Boolean {
        val candidates = if (r.itemId.isBlank()) draft?.lines.orEmpty() else linesByItem[r.itemId.uppercase()].orEmpty()
        return candidates.any { r.matches(it.itemId, it.locationId, it.configId, it.sizeId, it.colorId, it.styleId) }
    }

    /** Requested rows with at least one line counted on this device. */
    val requestedDone: Int by lazy { requested.count(::isRequestedDone) }

    private fun placeKey(location: String, bin: String?): String =
        if (bin == null) location.uppercase() else location.uppercase() + Char(0) + bin.uppercase()
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
    private val scanFeedback: com.d365fo.mobileops.presentation.common.scan.ScanFeedback,
    private val syncStatus: SyncStatusProvider,
    private val locationDao: com.d365fo.mobileops.data.local.dao.LocationDao,
    private val binRepository: com.d365fo.mobileops.data.counting.CountBinRepository,
    private val menuRepository: com.d365fo.mobileops.data.repository.HandheldMenuRepository,
    private val countRequests: com.d365fo.mobileops.data.counting.CountRequestRepository,
    private val draftWriter: com.d365fo.mobileops.data.local.DraftWriter,
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val user = workerSession.session.value?.userName.orEmpty()
    private val warehouse = workerSession.session.value?.selected?.warehouseId

    /** A hazard or blind count chosen from its list; blank = the store's wall to wall count. */
    private val masterId: String = savedStateHandle.get<String>("masterId").orEmpty()
    /** Opened from an open count on the list: straight into it (user, 17 Nov 2026). */
    private val join: Boolean = savedStateHandle.get<String>("join") == "1"
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

    /**
     * The list read before counting started; it goes into the draft when the count opens.
     * Declared before init: joining an open count starts it from init (18 Nov 2026 crash).
     */
    private var pendingRequested: List<com.d365fo.mobileops.data.counting.RequestedItem> = emptyList()

    /** End count was pressed offline: the retry ends the count too. */
    private var endWhenOnline = false

    /** D365FO answers: checked before every call, so offline never waits for a timeout. */
    private var online = true

    init {
        viewModelScope.launch { scannerBridge.scans.collect { onScan(it.value) } }
        // Tapped an open count on the list: join it now instead of showing "Start a count".
        if (join && _state.value.draft?.counting != true) start()
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

    private fun loadDraft(): StoreCountDraft? = runCatching {
        secureStore.getString(draftKey)?.let { json.decodeFromString(StoreCountDraft.serializer(), it) }
    }.getOrNull()

    /**
     * The screen takes the new draft at once. Encoding and saving it run on [DraftWriter]'s thread,
     * because the draft is the whole count and this runs after every scan (performance plan, phase 2).
     */
    private fun saveDraft(draft: StoreCountDraft?) {
        _state.update { it.copy(draft = draft) }
        val key = draftKey
        draftWriter.submit(key) {
            if (draft == null) secureStore.remove(key) else secureStore.putString(key, json.encodeToString(StoreCountDraft.serializer(), draft))
        }
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
    /** What the last scan did, as a sound and a buzz (user, 16 Nov 2026) - see [ScanFeedback]. */
    private fun soundFor(before: StoreCountState) {
        val after = _state.value
        when {
            after.error != null && after.error != before.error ->
                if (after.error.startsWith("Offline")) scanFeedback.warning() else scanFeedback.error()
            after.message != null && after.message != before.message ->
                if (after.message.startsWith("Offline")) scanFeedback.warning() else scanFeedback.success()
            after.binId != before.binId || after.locationId != before.locationId || after.pending != before.pending -> scanFeedback.success()
        }
    }

    fun onScan(raw: String) {
        val before = _state.value
        viewModelScope.launch {
            // Let the scan's own work settle, then say how it went.
            kotlinx.coroutines.delay(SOUND_SETTLE_MS)
            soundFor(before)
        }
        onScanInner(raw)
    }

    private fun onScanInner(raw: String) {
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

    /** A new count for a line not sent yet (the lines screen's - / + and pad, 18 Nov 2026). */
    fun setLineQuantity(line: StoreCountLine, quantity: Double) {
        val draft = _state.value.draft ?: return
        if (line.sent || quantity <= 0.0) return
        saveDraft(draft.copy(lines = draft.lines.map { if (it.clientLineId == line.clientLineId) it.copy(quantity = quantity) else it }))
    }

    /** Removes a line - one already sent is removed in D365FO first. */
    fun remove(line: StoreCountLine) {
        val draft = _state.value.draft ?: return
        if (!line.sent) {
            saveDraft(draft.copy(lines = draft.lines.filterNot { it.clientLineId == line.clientLineId }))
            return
        }
        // Already with the supervisor: removed in D365FO first (user, 17 Nov 2026).
        if (!online || draft.stageId.isBlank()) {
            _state.update { it.copy(error = "Offline - a line already sent is removed once D365FO answers. Try again then.") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = staging.removeLines(draft.stageId, listOf(line.clientLineId))) {
                is NetworkResult.Success -> {
                    _state.value.draft?.let { d -> saveDraft(d.copy(lines = d.lines.filterNot { it.clientLineId == line.clientLineId })) }
                    _state.update { it.copy(busy = false, message = null) }
                }
                is NetworkResult.Error -> _state.update { it.copy(busy = false, error = r.failure.message) }
                NetworkResult.Loading -> _state.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * "Discard my lines" (user, 17 Nov 2026): this counter's lines go - the sent ones from D365FO,
     * then the device's copy - and [onDone] leaves the count. Other counters' lines stay. Nothing
     * is dropped on the device unless D365FO removed the sent ones first.
     */
    fun discardMine(onDone: () -> Unit) {
        val draft = _state.value.draft ?: return onDone()
        val sent = draft.lines.filter { it.sent }.map { it.clientLineId }
        if (sent.isNotEmpty() && (!online || draft.stageId.isBlank())) {
            _state.update { it.copy(error = "Offline - your sent lines can be discarded once D365FO answers. Nothing was removed.") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = staging.removeLines(draft.stageId, sent)) {
                is NetworkResult.Success -> {
                    saveDraft(null)
                    _state.update { it.copy(busy = false, message = null, pending = null) }
                    onDone()
                }
                is NetworkResult.Error -> _state.update { it.copy(busy = false, error = r.failure.message + " - nothing was removed.") }
                NetworkResult.Loading -> _state.update { it.copy(busy = false) }
            }
        }
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
                        _state.update { it.copy(busy = false, ended = true, message = "${linesText(draft.lines.size)} sent.$dupNote") }
                    } else {
                        saveDraft(updated)
                        _state.update { it.copy(busy = false, message = "${linesText(result.data.accepted)} sent.$dupNote") }
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
    var confirmDiscard by remember { mutableStateOf(false) }
    val draft = state.draft
    val counting = draft?.counting == true && !state.ended
    if (confirmDiscard && draft != null) {
        val n = draft.lines.size
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard your lines?") },
            text = {
                Text(
                    (if (n == 1) "1 line" else "$n lines") + " you counted" +
                        (draft.masterId.takeIf { it.isNotBlank() }?.let { " in $it" } ?: "") +
                        (if (n == 1) " is" else " are") + " removed, from D365FO too. Other counters' lines stay.",
                )
            },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = { confirmDiscard = false }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Keep") } },
                    confirm = { com.d365fo.mobileops.presentation.common.components.CancelButton(onClick = { confirmDiscard = false; viewModel.discardMine(onBack) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Discard") } },
                )
            },
        )
    }

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
            onSetQuantity = viewModel::setLineQuantity,
            busy = state.busy,
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
            // The location and bin are on the card, not the title bar (user, 17 Nov 2026).
            else -> null
        },
        band = storeCountBand(state),
        // No "28 on this device" line (user, 10 Nov).
        remaining = null,
        scanPill = if (counting && !state.countClosed) {
            {
                com.d365fo.mobileops.presentation.common.focus.ScanPillField(
                    label = when {
                        state.needsLocation -> "Scan the location"
                        state.needsBin -> "Scan the bin"
                        pending?.needsBatch == true -> "Scan the batch number"
                        pending?.needsSerial == true -> "Scan the serial number"
                        else -> "Scan an item to count"
                    },
                    enabled = !state.busy,
                )
            }
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
                    // This counter's lines go, from D365FO too (user, 17 Nov 2026) - asked first.
                    DropdownMenuItem(
                        text = { Text("Discard my lines", color = FocusColors.badIcon) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = FocusColors.badIcon) },
                        enabled = !state.busy,
                        onClick = { menuOpen = false; confirmDiscard = true },
                        modifier = Modifier.heightIn(min = 56.dp),
                    )
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
                title = "Enter the count",
                subtitle = listOf(
                    pending.item.itemId,
                    listOfNotNull(
                        pending.item.itemName.takeIf { it.isNotBlank() && !it.equals(pending.item.itemId, true) },
                        pending.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                        pending.serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" },
                        pending.item.inventUnit.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                ),
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
        // No "Count ended" bar over the Ended card (user, 18 Nov 2026) - the card says it.
        state.ended -> null
        error != null -> if (error.startsWith("Offline")) bandOf(BandKind.Warn, error) else bandOf(BandKind.Bad, error)
        message != null -> when {
            message.startsWith("Offline") -> bandOf(BandKind.Warn, message)
            SENT_MESSAGE.containsMatchIn(message) -> BandState(BandKind.Ok, "Sent", message)
            // No "Counted" bar: the green task box and the card say it (user, 17 Nov 2026).
            else -> null
        }
        state.draft?.counting != true -> null
        state.back != null -> BandState(
            BandKind.Info,
            "Changing place",
            "from " + listOfNotNull(state.back?.locationId, state.back?.binId).filter { it.isNotBlank() }.joinToString(" › "),
        )
        // Where counting stands is in the task box now (user, 17 Nov 2026).
        else -> null
    }
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
        state.ended -> FocusCenter(
            task = FocusTask("Count ended", null, com.d365fo.mobileops.presentation.common.focus.TaskIcons.Done, done = true),
            caption = state.title,
            code = state.countId.ifBlank { state.warehouseId ?: "Ended" },
            description = state.message,
        )
        !counting || draft == null -> FocusCenter(
            // Only when there is no count: the New count button below is the way on.
            task = if (state.countId.isBlank()) FocusTask("Start a count", null, androidx.compose.material.icons.Icons.Outlined.Add) else null,
            caption = state.title,
            code = state.countId.ifBlank { state.warehouseId ?: "–" },
            description = if (state.countId.isBlank()) "No count open" else "Not started on this device",
        )
        pending != null && !pending.needsVariant -> FocusCenter(
            task = when {
                pending.needsBatch -> FocusTask("Scan the batch", "on the box of ${pending.item.itemId}", androidx.compose.material.icons.Icons.Outlined.QrCodeScanner)
                pending.needsSerial -> FocusTask("Scan the serial", "on ${pending.item.itemId}", androidx.compose.material.icons.Icons.Outlined.QrCodeScanner)
                else -> FocusTask("Enter the count", "how many you see", androidx.compose.material.icons.Icons.Outlined.Dialpad)
            },
            // The card is the item - "Batch" over an item code read wrong (user, 18 Nov 2026).
            caption = if (pending.needsBatch || pending.needsSerial) "Item" else "Quantity",
            // Where it is counted, on the card - not on the title bar (user, 17 Nov 2026).
            code = pending.item.itemId,
            description = listOfNotNull(
                pending.item.itemName.takeIf { it.isNotBlank() && !it.equals(pending.item.itemId, ignoreCase = true) },
                pending.variant?.takeIf { pending.item.hasProductDimensions }?.label,
                pending.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                pending.serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" },
            ).joinToString(" · ").ifEmpty { null },
            unitLine = listOfNotNull(
                pending.item.inventUnit.takeIf { it.isNotBlank() },
                (if (state.binId.isBlank()) state.locationId else "${state.locationId} › ${state.binId}").takeIf { it.isNotBlank() },
            ).joinToString(" · "),
        )
        state.needsLocation -> {
            val places = draft.lines.map { it.locationId.uppercase() }.distinct().size
            FocusCenter(
                task = FocusTask("Scan the location", null, androidx.compose.material.icons.Icons.Outlined.Place),
                // The count it is, when there is one (an open count joined from the list).
                caption = draft.masterId.ifBlank { "Locations counted" },
                code = if (places == 1) "1 location" else "$places locations",
                description = "counted · ${linesText(draft.lines.size)}",
            )
        }
        state.needsBin -> {
            val bins = draft.lines.filter { it.locationId.equals(state.locationId, ignoreCase = true) && it.binId.isNotBlank() }
                .map { it.binId.uppercase() }.distinct().size
            FocusCenter(
                task = FocusTask("Scan the bin", null, androidx.compose.material.icons.Icons.Outlined.Place),
                caption = "Location",
                code = state.locationId,
                description = (if (bins == 1) "1 bin" else "$bins bins") + " counted here",
            )
        }
        else -> {
            val last = draft.lines.firstOrNull {
                it.locationId.equals(state.locationId, ignoreCase = true) && it.binId.equals(state.binId, ignoreCase = true)
            }
            if (last == null) {
                FocusCenter(
                    task = FocusTask("Scan an item", null, androidx.compose.material.icons.Icons.Outlined.QrCodeScanner),
                    caption = if (state.binId.isBlank()) "Location" else "Bin",
                    code = if (state.binId.isBlank()) state.locationId else "${state.locationId} › ${state.binId}",
                    description = "${linesText(0)} counted here",
                )
            } else {
                FocusCenter(
                    task = FocusTask("Scan an item", null, androidx.compose.material.icons.Icons.Outlined.QrCodeScanner, done = true),
                    caption = "Last counted",
                    code = last.itemId,
                    description = listOfNotNull(
                        last.itemName.takeIf { it.isNotBlank() && !it.equals(last.itemId, ignoreCase = true) },
                        OrderVariant(last.configId, last.sizeId, last.colorId, last.styleId).takeIf { v -> v != OrderVariant() }?.label,
                        last.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                        last.serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" },
                    ).joinToString(" · ").ifEmpty { null },
                    quantity = qtyText(last.quantity),
                    // The unit and where (user, 17 Nov 2026) - not "On this device · 29 here" (10 Nov).
                    unitLine = listOfNotNull(
                        last.unitId.takeIf { it.isNotBlank() },
                        (if (state.binId.isBlank()) state.locationId else "${state.locationId} › ${state.binId}").takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                )
            }
        }
    }
}

private fun linesText(n: Int) = if (n == 1) "1 line" else "$n lines"

/** "1 line sent." / "3 lines sent." - what Send says. */
private val SENT_MESSAGE = Regex("^\\d+ lines? sent\\.")

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
    onSetQuantity: (StoreCountLine, Double) -> Unit = { _, _ -> },
    busy: Boolean = false,
) {
    var confirming by remember { mutableStateOf<StoreCountLine?>(null) }
    var typing by remember { mutableStateOf<StoreCountLine?>(null) }
    confirming?.let { line ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Remove this line?") },
            text = { Text("${line.itemId} · ${qtyText(line.quantity)} ${line.unitId}".trim() + (if (line.sent) " - removed from D365FO too." else "")) },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = { confirming = null }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Keep") } },
                    confirm = { com.d365fo.mobileops.presentation.common.components.CancelButton(onClick = { confirming = null; onRemove(line) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Remove") } },
                )
            },
        )
    }
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
        overlay = typing?.let { line ->
            {
                QuantityPadSheet(
                    initial = qtyText(line.quantity),
                    onSet = { t -> typing = null; t.toDoubleOrNull()?.let { onSetQuantity(line, it) } },
                    onDismiss = { typing = null },
                    allowDecimal = true,
                    title = "Enter the count",
                    subtitle = listOf(line.itemId, listOfNotNull(line.itemName.takeIf { it.isNotBlank() }, line.unitId.takeIf { it.isNotBlank() }).joinToString(" · ")),
                )
            }
        },
    ) {
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.needsLocation || state.needsBin -> {
                    val picking = if (state.needsLocation) state.knownLocations else state.binsHere
                    // Every list has its task box (user, 18 Nov 2026).
                    item(key = "task") {
                        com.d365fo.mobileops.presentation.common.focus.ListTaskBox(
                            if (state.needsLocation) "Choose a location" else "Choose a bin",
                            com.d365fo.mobileops.presentation.common.focus.TaskIcons.Place,
                        )
                    }
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
                    item(key = "task") {
                        com.d365fo.mobileops.presentation.common.focus.ListTaskBox("Check the lines", com.d365fo.mobileops.presentation.common.focus.TaskIcons.Lines)
                    }
                    // A hazard or blind count: what it asks for, ticked off as it is counted.
                    if (draft.requested.isNotEmpty()) {
                        item(key = "requested-caption") {
                            com.d365fo.mobileops.presentation.common.focus.FocusCaption(
                                "To count · ${state.requestedDone} of ${draft.requested.size} done",
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(draft.requested.size, key = { "requested-$it" }) { index ->
                            draft.requested[index].let { r -> RequestedRow(r, state.isRequestedDone(r)) }
                        }
                        item(key = "lines-caption") {
                            com.d365fo.mobileops.presentation.common.focus.FocusCaption(
                                "Counted lines",
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                    }
                    items(draft.lines, key = { it.clientLineId }) { line ->
                        // As the other lines screens: the text full width, - / count / + under it
                        // (user, 18 Nov 2026). A sent line's count is D365FO's now - it can only be removed.
                        val editable = !line.sent && line.serialId.isBlank() && !busy
                        com.d365fo.mobileops.presentation.common.focus.FocusLineCard(
                            code = line.itemId,
                            name = line.itemName,
                            details = listOf(
                                listOfNotNull(
                                    OrderVariant(line.configId, line.sizeId, line.colorId, line.styleId).takeIf { v -> v != OrderVariant() }?.label,
                                    line.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                                    line.serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" },
                                    line.place,
                                ).joinToString(" · "),
                                if (line.sent) "With the supervisor" else "On this device",
                            ),
                            quantity = qtyText(line.quantity),
                            unit = line.unitId,
                            canMinus = editable,
                            canPlus = editable,
                            onMinus = if (line.sent || line.serialId.isNotBlank()) null else {
                                { if (line.quantity <= 1.0) confirming = line else onSetQuantity(line, line.quantity - 1.0) }
                            },
                            onPlus = if (line.sent || line.serialId.isNotBlank()) null else {
                                { onSetQuantity(line, line.quantity + 1.0) }
                            },
                            onQuantityTap = if (editable) { { typing = line } } else null,
                            // Any of this counter's lines - a sent one is removed in D365FO (17 Nov 2026).
                            onRemove = { confirming = line },
                            removeEnabled = !busy,
                        )
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
        remaining = null,
        scanPill = if (counting) { { com.d365fo.mobileops.presentation.common.focus.ScanPill(onTap = {}) } } else null,
        actions = storeCountActions(state, counting, {}, {}, {}, {}, {}, {}, {}, {}),
        overlay = if (kind == 3) { { QuantityPadSheet(initial = "", onSet = {}, onDismiss = {}, allowDecimal = true) } } else null,
    ) {
        StoreCountCenter(state, counting)
    }
}

/** One item a hazard or blind count asks for, ticked once this device has counted it. */
@Composable
private fun RequestedRow(r: com.d365fo.mobileops.data.counting.RequestedItem, done: Boolean) {
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
    FocusScreen(
        title = "Items to count",
        onBack = onClose,
        counter = "${state.requestedDone}/${state.requested.size}",
    ) {
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Every list has its task box (user, 18 Nov 2026).
            item(key = "task") {
                com.d365fo.mobileops.presentation.common.focus.ListTaskBox("Items to count", com.d365fo.mobileops.presentation.common.focus.TaskIcons.Lines)
            }
            items(state.requested.size, key = { "requested-$it" }) { index ->
                state.requested[index].let { r -> RequestedRow(r, state.isRequestedDone(r)) }
            }
        }
    }
}

/** How long a store-count scan gets to land before its sound plays. */
private const val SOUND_SETTLE_MS = 350L
