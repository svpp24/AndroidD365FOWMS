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
)

/** The store count on this device: the stock count and staging journal, and the lines counted. */
@Serializable
data class StoreCountDraft(
    val warehouseId: String,
    val masterId: String = "",
    val stageId: String = "",
    val lines: List<StoreCountLine> = emptyList(),
)

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

data class StoreCountState(
    val warehouseId: String? = null,
    val draft: StoreCountDraft? = null,
    val locationId: String = "",
    val pending: PendingCount? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val ended: Boolean = false,
    /** The supervisor transferred or cancelled this stock count while lines were still on the device. */
    val countClosed: Boolean = false,
) {
    val unsent: Int get() = draft?.lines?.count { !it.sent } ?: 0
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
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val user = workerSession.session.value?.userName.orEmpty()
    private val warehouse = workerSession.session.value?.selected?.warehouseId
    private val draftKey = "store_count_draft::${warehouse.orEmpty().uppercase()}::${user.uppercase()}"

    private val _state = MutableStateFlow(StoreCountState(warehouseId = warehouse, draft = loadDraft()))
    val state = _state.asStateFlow()

    /** The last Send could not reach D365FO; it is retried when the connection is back. */
    private var waitingForConnection = false

    init {
        viewModelScope.launch { scannerBridge.scans.collect { onScan(it.value) } }
        // A Send that failed for want of a connection goes again by itself once D365FO answers -
        // the offline notice promises it (test, 13 Oct: the counter had to tap Send again).
        viewModelScope.launch {
            syncStatus.status.map { it.fullyOnline }.distinctUntilChanged().collect { online ->
                val s = _state.value
                if (online && waitingForConnection && !s.busy && s.unsent > 0) send(end = false)
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
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val opened = staging.open(wh)) {
                is NetworkResult.Success -> {
                    val current = _state.value.draft ?: StoreCountDraft(warehouseId = wh)
                    saveDraft(current.copy(masterId = opened.data.masterId, stageId = opened.data.stageId))
                    _state.update {
                        it.copy(
                            busy = false,
                            message = "Counting for ${opened.data.masterId}." + if (it.locationId.isBlank()) " Scan a location to start." else "",
                        )
                    }
                }
                is NetworkResult.Error -> _state.update { it.copy(busy = false, error = opened.failure.message) }
                NetworkResult.Loading -> _state.update { it.copy(busy = false) }
            }
        }
    }

    /** A scan: a location first (and whenever none is set), then items at it. */
    fun onScan(raw: String) {
        val value = raw.trim()
        if (value.isBlank() || _state.value.draft?.stageId.isNullOrBlank()) return
        val pending = _state.value.pending
        when {
            pending != null && pending.needsSerial && !pending.needsVariant && !pending.needsBatch -> setSerial(value)
            pending != null && pending.needsBatch && !pending.needsVariant -> setBatch(value)
            _state.value.locationId.isBlank() -> setLocation(value)
            else -> scanItem(value)
        }
    }

    fun changeLocation() = _state.update { it.copy(locationId = "", pending = null) }

    private fun setLocation(value: String) {
        val wh = warehouse ?: return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val checked = lookup.lookupLocation(wh, value)
            val known = (checked as? NetworkResult.Success)?.data
            _state.update {
                when {
                    checked is NetworkResult.Success && known == null ->
                        it.copy(busy = false, error = "No location $value in $wh.")
                    else -> it.copy(busy = false, locationId = known?.locationId ?: value.uppercase(), message = "Counting at ${known?.locationId ?: value.uppercase()}.")
                }
            }
        }
    }

    private fun scanItem(value: String) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val found = items.findItem(value, warehouse.orEmpty())) {
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
        )
        saveDraft(draft.copy(lines = listOf(line) + draft.lines))
        _state.update { it.copy(pending = null, message = "${line.itemId} · ${qtyText(qty)} at ${line.locationId}") }
    }

    /** Removes a line that has not been sent yet. */
    fun remove(line: StoreCountLine) {
        val draft = _state.value.draft ?: return
        if (line.sent) return
        saveDraft(draft.copy(lines = draft.lines.filterNot { it.clientLineId == line.clientLineId }))
    }

    /** Sends the unsent lines; [end] also ends this counter's count. */
    fun send(end: Boolean) {
        val draft = _state.value.draft ?: return
        if (draft.stageId.isBlank()) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
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
                    val sentIds = unsent.map { it.clientLineId }.toSet()
                    val updated = draft.copy(lines = draft.lines.map { if (it.clientLineId in sentIds) it.copy(sent = true) else it })
                    val dupNote = if (result.data.duplicates > 0) " ${result.data.duplicates} item(s) in this stock count are counted more than once - the supervisor decides." else ""
                    if (end) {
                        saveDraft(null)
                        _state.update { it.copy(busy = false, ended = true, message = "Count ended - ${draft.lines.size} line(s) are with the supervisor in ${result.data.masterId}.$dupNote") }
                    } else {
                        saveDraft(updated)
                        _state.update { it.copy(busy = false, message = "${result.data.accepted} line(s) sent to ${result.data.masterId}.$dupNote") }
                    }
                }
                is NetworkResult.Error -> {
                    waitingForConnection = result.failure is AppFailure.Offline || result.failure is AppFailure.Transient
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
        quantity = quantity,
        unitId = unitId,
        countedAtUtc = Formatters.isoUtc(countedAt),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreCountScreen(onBack: () -> Unit, viewModel: StoreCountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    if (typing) {
        com.d365fo.mobileops.presentation.modules.ordercreate.CodeEntryDialog(
            title = if (state.locationId.isBlank()) "Location" else "Item",
            label = if (state.locationId.isBlank()) "Location" else "Item number or barcode",
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
                hint = "At ${state.locationId}",
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
                    "${state.draft?.lines?.size ?: 0} line(s) counted. They go to the supervisor, who checks them and " +
                        "moves them into a counting journal. You can't add to this count afterwards unless you start it again.",
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(WarehouseDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Ended: say so and stay until Done - closing at once took the message with it (13 Oct).
            if (state.ended) {
                Text("Count ended", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                state.message?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Done") }
                return@Column
            }
            if (draft == null || draft.stageId.isBlank()) {
                Text(
                    "Count everything in ${state.warehouseId ?: "your store"}, location by location. Your counts go to your " +
                        "supervisor, who checks them - items counted twice included - before they reach a counting journal.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                state.error?.let { ErrorBanner(message = it) }
                Button(
                    onClick = viewModel::start,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
                ) { Text(if (state.busy) "Starting…" else "Start store count") }
                return@Column
            }

            com.d365fo.mobileops.presentation.common.components.PromptHeroCard(
                prompt = if (state.locationId.isBlank()) "Scan location" else "Scan item",
                hint = if (state.locationId.isBlank()) "Where you are counting now" else "At ${state.locationId} · ${draft.masterId}",
            ) {
                // The same scan field as every other scan screen (feedback, 13 Oct: "where is the
                // barcode scanning field?"): a scan never lands in it, a tap opens typing.
                com.d365fo.mobileops.presentation.modules.ordercreate.OrderScanField(
                    onType = { typing = true },
                    onCamera = { showCamera = true },
                    enabled = !state.busy,
                )
            }
            if (state.locationId.isNotBlank()) {
                OutlinedButton(onClick = viewModel::changeLocation, modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget)) {
                    Text("Change location")
                }
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

            // The newest line is added on top; a list kept scrolled to the line that was first
            // before hid it (test, 13 Oct) - show it.
            val listState = rememberLazyListState()
            val newest = draft.lines.firstOrNull()?.clientLineId
            LaunchedEffect(newest) { if (newest != null) listState.animateScrollToItem(0) }
            LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(draft.lines, key = { it.clientLineId }) { line ->
                    // A white card per line, like the rest of the 20 Oct restyle.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(androidx.compose.ui.graphics.Color.White, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${line.itemId} · ${qtyText(line.quantity)} ${line.unitId}", fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(
                                    line.itemName.takeIf { it.isNotBlank() },
                                    OrderVariant(line.configId, line.sizeId, line.colorId, line.styleId).takeIf { v -> v != OrderVariant() }?.label,
                                    line.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                                    line.serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" },
                                    "At ${line.locationId}",
                                    if (line.sent) "With the supervisor" else "On this device",
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (line.sent) WarehouseStatusColors.text3 else WarehouseStatusColors.amber,
                            )
                        }
                        if (!line.sent) {
                            IconButton(onClick = { viewModel.remove(line) }) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { viewModel.send(end = false) },
                    enabled = !state.busy && state.unsent > 0,
                    modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                ) { Text(if (state.unsent > 0) "Send ${state.unsent}" else "All sent") }
                Button(
                    onClick = { confirmEnd = true },
                    enabled = !state.busy && draft.lines.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                ) { Text("End count") }
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
