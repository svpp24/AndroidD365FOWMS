package com.d365fo.mobileops.presentation.modules.cyclecounting.store

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
    val pending: PendingCount? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val ended: Boolean = false,
    /** The supervisor transferred or cancelled this stock count while lines were still on the device. */
    val countClosed: Boolean = false,
) {
    val unsent: Int get() = draft?.lines?.count { !it.sent } ?: 0
    val needsLocation: Boolean get() = locationId.isBlank()
    val needsBin: Boolean get() = !needsLocation && binsRequired && binId.isBlank()
    /** Lines counted in this location, and in this bin. */
    val atLocation: Int get() = draft?.lines?.count { it.locationId.equals(locationId, ignoreCase = true) } ?: 0
    val inBin: Int get() = draft?.lines?.count { it.locationId.equals(locationId, ignoreCase = true) && it.binId.equals(binId, ignoreCase = true) } ?: 0
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
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val user = workerSession.session.value?.userName.orEmpty()
    private val warehouse = workerSession.session.value?.selected?.warehouseId
    private val draftKey = "store_count_draft::${warehouse.orEmpty().uppercase()}::${user.uppercase()}"

    /** Where a count starts: the counting menu's location (user, 8 Nov). */
    private val startLocation = menuRepository.menu.value?.countStartLocation

    private val _state = MutableStateFlow(
        loadDraft().let { draft ->
            StoreCountState(
                warehouseId = warehouse,
                draft = draft,
                startLocation = startLocation,
                // Back into a count already started: in the start location again.
                locationId = if (draft?.counting == true) startLocation.orEmpty() else "",
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
        warehouse?.let { wh ->
            viewModelScope.launch { binRepository.refresh(wh) }
            viewModelScope.launch {
                binRepository.observe(wh).collect { rows ->
                    bins = rows.associateBy { it.binId }
                    _state.update { it.copy(binsRequired = rows.isNotEmpty()) }
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
            when (val opened = staging.open(wh)) {
                is NetworkResult.Success -> {
                    val current = _state.value.draft ?: StoreCountDraft(warehouseId = wh)
                    saveDraft(current.copy(masterId = opened.data.masterId, stageId = opened.data.stageId, started = true))
                    _state.update {
                        val location = it.locationId.ifBlank { startLocation.orEmpty() }
                        it.copy(
                            busy = false,
                            locationId = location,
                            message = "Counting for ${opened.data.masterId}." + nextStep(location, it.binsRequired),
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
        val current = _state.value.draft ?: StoreCountDraft(warehouseId = wh)
        saveDraft(current.copy(started = true))
        waitingForConnection = true
        _state.update {
            val location = it.locationId.ifBlank { startLocation.orEmpty() }
            it.copy(
                busy = false,
                error = null,
                locationId = location,
                message = "Offline - counting on this device. Counts go to D365FO when it answers." + nextStep(location, it.binsRequired),
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
            s.needsBin -> _state.update {
                it.copy(message = null, error = "$value is not a bin in ${s.locationId}. Scan one of its bins, or ask a supervisor to add it in Count bins.")
            }
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
        _state.update { it.copy(binId = bin.binId, back = null, pending = null, error = null, message = "Counting ${s.locationId} \u203A ${bin.binId}.") }
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
                _state.update { it.copy(busy = false, locationId = id, binId = "", back = null, message = nextStep(id, it.binsRequired).trim()) }
                return@launch
            }
            val checked = lookup.lookupLocation(wh, value)
            val known = (checked as? NetworkResult.Success)?.data
            _state.update {
                when {
                    checked is NetworkResult.Success && known == null ->
                        it.copy(busy = false, error = "No location $value in $wh.")
                    else -> (known?.locationId ?: value.uppercase()).let { id ->
                        it.copy(busy = false, locationId = id, binId = "", back = null, message = nextStep(id, it.binsRequired).trim())
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
                when (val opened = staging.open(started.warehouseId)) {
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
                    val dupNote = if (result.data.duplicates > 0) " ${result.data.duplicates} item(s) in ${draft.masterId} were counted more than once - your supervisor decides." else ""
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
private val StoreCountLine.place: String get() = if (binId.isBlank()) locationId else "$locationId \u203A $binId"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreCountScreen(onBack: () -> Unit, viewModel: StoreCountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    var changePlace by remember { mutableStateOf(false) }
    if (changePlace) {
        com.d365fo.mobileops.presentation.modules.cyclecounting.ChangePlaceDialog(
            location = state.locationId,
            bin = state.binId,
            binsUsed = state.binsRequired,
            canChangeLocation = true,
            onChangeLocation = { changePlace = false; viewModel.changeLocation() },
            onChangeBin = { changePlace = false; viewModel.changeBin() },
            onDismiss = { changePlace = false },
        )
    }
    if (typing) {
        com.d365fo.mobileops.presentation.modules.ordercreate.CodeEntryDialog(
            title = when {
                state.needsLocation -> "Location"
                state.needsBin -> "Bin"
                else -> "Item"
            },
            label = when {
                state.needsLocation -> "Location"
                state.needsBin -> "Bin"
                else -> "Item number or barcode"
            },
            onSubmit = { typing = false; viewModel.onScan(it) },
            onDismiss = { typing = false },
        )
    }

    if (showCamera) {
        com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet(
            onDecoded = { viewModel.onCameraDecoded(it); showCamera = false },
            onDismiss = { showCamera = false },
        )
    }
    state.pending?.let { pending ->
        when {
            pending.needsVariant -> VariantPickerDialog(item = pending.item, onPick = viewModel::pickVariant, onDismiss = viewModel::cancelPending)
            pending.needsBatch -> TextEntryDialog("Batch", "Scan or type the batch of ${pending.item.itemId}", viewModel::setBatch, viewModel::cancelPending)
            pending.needsSerial -> TextEntryDialog("Serial number", "Scan or type the serial of ${pending.item.itemId}", viewModel::setSerial, viewModel::cancelPending)
            else -> QuantityPicker(
                subtitle = listOfNotNull(
                    pending.item.itemId,
                    pending.variant?.takeIf { pending.item.hasProductDimensions }?.label,
                    pending.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                    pending.serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" },
                    pending.item.inventUnit,
                ).joinToString(" · "),
                hint = "At " + if (state.binId.isBlank()) state.locationId else "${state.locationId} \u203A ${state.binId}",
                confirmLabel = "Add count",
                initial = if (pending.item.serialActive) "1" else "",
                onConfirm = viewModel::confirmQuantity,
                onDismiss = viewModel::cancelPending,
            )
        }
    }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End count?") },
            text = {
                Text(
                    "${state.draft?.lines?.size ?: 0} line(s) go to the supervisor. You can't add after this.",
                )
            },
            confirmButton = {
                DialogButtonRow(
                    dismiss = {
                        CancelOutlinedButton(onClick = { confirmEnd = false }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) {
                            Text("Keep counting")
                        }
                    },
                    confirm = {
                        ConfirmButton(
                            onClick = { confirmEnd = false; viewModel.send(end = true) },
                            modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                        ) { Text("End count") }
                    },
                )
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Store count") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        val draft = state.draft
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = WarehouseDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Ended: say so and stay until Done - closing at once took the message with it (13 Oct).
            if (state.ended) {
                Text("Count ended", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
                state.message?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Done") }
                return@Column
            }
            if (draft == null || !draft.counting) {
                Text(
                    "Count ${state.warehouseId ?: "the store"}, location by location.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
                state.error?.let { ErrorBanner(message = it) }
                com.d365fo.mobileops.presentation.common.components.GradientButton(
                    onClick = viewModel::start,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(if (state.busy) "Starting…" else "New store count")
                }
                return@Column
            }

            // Location > bin > items as tabs under the bar (Harbour).
            com.d365fo.mobileops.presentation.common.components.StepTrail(
                names = listOfNotNull("Location", "Bin".takeIf { state.binsRequired }, "Items"),
                current = when {
                    state.needsLocation -> 0
                    state.needsBin -> 1
                    else -> if (state.binsRequired) 2 else 1
                },
                // A done tab goes back to that step (user, 9 Nov: no Back / Change / Cancel buttons).
                onStepClick = { i ->
                    if (!state.busy) when (i) {
                        0 -> viewModel.changeLocation()
                        1 -> if (state.binsRequired) viewModel.changeBin()
                    }
                },
            )
            com.d365fo.mobileops.presentation.common.components.PromptHeroCard(
                prompt = when {
                    state.needsLocation -> "Choose a location"
                    state.needsBin -> "Scan a bin"
                    else -> "Scan item"
                },
                pills = buildList {
                    state.warehouseId?.let { add(Icons.Default.Store to it) }
                    state.locationId.takeIf { it.isNotBlank() && !state.needsLocation }?.let { add(Icons.Default.LocationOn to it) }
                    state.binId.takeIf { it.isNotBlank() && state.binsRequired && !state.needsBin && !state.needsLocation }?.let { add(Icons.Default.Inventory2 to it) }
                    draft.masterId.takeIf { it.isNotBlank() }?.let { add(Icons.Default.Description to it) }
                },
            ) {
                // The same scan field as every other scan screen (feedback, 13 Oct: "where is the
                // barcode scanning field?"): a scan never lands in it, a tap opens typing.
                com.d365fo.mobileops.presentation.modules.ordercreate.OrderScanField(
                    onType = { typing = true },
                    onCamera = { showCamera = true },
                    enabled = !state.busy,
                )
            }
            state.error?.let { ErrorBanner(message = it) }
            if (state.countClosed) {
                Button(
                    onClick = viewModel::moveToNewCount,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
                ) { Text("Move ${state.unsent} line(s) to a new stock count") }
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = WarehouseStatusColors.text3) }

            if (draft.lines.isNotEmpty()) {
                com.d365fo.mobileops.presentation.common.components.ListHeader(
                    icon = Icons.Default.Checklist,
                    title = "Counted",
                    count = draft.lines.size,
                    trailing = when {
                        state.needsLocation -> null
                        state.binsRequired && state.binId.isNotBlank() -> "${state.inBin} in this bin"
                        else -> "${state.atLocation} here"
                    },
                )
            }
            // The newest line is added on top; a list kept scrolled to the line that was first
            // before hid it (test, 13 Oct) - show it.
            val listState = rememberLazyListState()
            val newest = draft.lines.firstOrNull()?.clientLineId
            LaunchedEffect(newest) { if (newest != null) listState.animateScrollToItem(0) }
            LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(draft.lines, key = { it.clientLineId }) { line ->
                    com.d365fo.mobileops.presentation.common.components.ListCard {
                        com.d365fo.mobileops.presentation.common.components.LineRow(
                            title = line.itemId,
                            description = line.itemName,
                            lines = listOfNotNull(
                                listOfNotNull(
                                    OrderVariant(line.configId, line.sizeId, line.colorId, line.styleId).takeIf { v -> v != OrderVariant() }?.label,
                                    line.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                                    line.serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" },
                                ).joinToString(" · ").takeIf { it.isNotBlank() },
                                "At ${line.place} · " + if (line.sent) "With the supervisor" else "On this device",
                            ),
                            lead = {
                                com.d365fo.mobileops.presentation.common.components.LeadDot(
                                    if (line.sent) com.d365fo.mobileops.presentation.common.components.Lead.Done else com.d365fo.mobileops.presentation.common.components.Lead.Warn,
                                    icon = Icons.Default.Check,
                                )
                            },
                            trailing = {
                                Row(verticalAlignment = Alignment.Top) {
                                    com.d365fo.mobileops.presentation.common.components.QtyTrail(qtyText(line.quantity), line.unitId)
                                    if (!line.sent) {
                                        IconButton(onClick = { viewModel.remove(line) }) { Icon(Icons.Default.Delete, contentDescription = "Remove", tint = WarehouseStatusColors.text3) }
                                    }
                                }
                            },
                        )
                    }
                }
            }
            // Change location / bin where Send was (user, 8 Nov): lines go by themselves. End
            // count is a hold (Harbour) - the lines go to the supervisor and can't be added to.
            com.d365fo.mobileops.presentation.common.components.FlowDock(modifier = Modifier.bleed(WarehouseDimens.ScreenPadding)) {
                // Change bin only - a location is changed from its tab (user, 9 Nov).
                if (state.binsRequired && !state.needsLocation && !state.needsBin) {
                    com.d365fo.mobileops.presentation.common.components.DockOutlinedButton(
                        onClick = viewModel::changeBin,
                        enabled = !state.busy,
                        icon = Icons.Default.Inventory2,
                        text = "Change bin",
                        modifier = Modifier.weight(0.42f),
                    )
                }
                com.d365fo.mobileops.presentation.common.components.HoldButton(
                    label = "Hold to end count",
                    onConfirm = { viewModel.send(end = true) },
                    enabled = !state.busy && draft.lines.isNotEmpty(),
                    modifier = Modifier.weight(0.58f),
                )
            }
        }
    }
}

@Composable
private fun TextEntryDialog(title: String, hint: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(hint) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            DialogButtonRow(
                dismiss = {
                    CancelOutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") }
                },
                confirm = {
                    ConfirmButton(
                        onClick = { onConfirm(value) },
                        enabled = value.isNotBlank(),
                        modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) { Text("OK") }
                },
            )
        },
    )
}

private fun qtyText(qty: Double): String = java.math.BigDecimal.valueOf(qty).stripTrailingZeros().toPlainString()

/**
 * Where the counter is: "STOCK > BIN-A1" and how many lines are counted there. On the bin step a
 * Back button to the location, on the item step one to the bin (user, 8 Nov). While changing:
 * what is being left, and Cancel back to it.
 */
@Composable
private fun StorePlaceControls(
    state: StoreCountState,
    onBackToLocation: () -> Unit,
    onBackToBin: () -> Unit,
    onCancel: () -> Unit,
) {
    // Where the counter is shows as pills above; here only the way back a step, and while
    // changing, what is being left with Cancel back to it.
    state.back?.let { back ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Changing from " + listOf(back.locationId, back.binId).filter { it.isNotBlank() }.joinToString(" › "),
                style = MaterialTheme.typography.bodyMedium,
                color = WarehouseStatusColors.text3,
                modifier = Modifier.weight(1f),
            )
            CancelOutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") }
        }
    }
    when {
        state.needsLocation -> Unit
        state.needsBin -> com.d365fo.mobileops.presentation.modules.cyclecounting.StepBackButton(
            "Back to location", onBackToLocation, enabled = !state.busy,
        )
        state.binsRequired -> com.d365fo.mobileops.presentation.modules.cyclecounting.StepBackButton(
            "Back to bin", onBackToBin, enabled = !state.busy,
        )
        else -> com.d365fo.mobileops.presentation.modules.cyclecounting.StepBackButton(
            "Back to location", onBackToLocation, enabled = !state.busy,
        )
    }
}
