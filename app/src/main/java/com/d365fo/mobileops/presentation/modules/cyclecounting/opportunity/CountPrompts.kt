package com.d365fo.mobileops.presentation.modules.cyclecounting.opportunity

import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.counting.EndCountResult
import com.d365fo.mobileops.data.counting.OpportunityCounts
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.domain.FeatureFlags
import com.d365fo.mobileops.domain.counting.CountTaskRepository
import com.d365fo.mobileops.domain.counting.DiscrepancyReason
import com.d365fo.mobileops.domain.counting.TaskResult
import com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton
import com.d365fo.mobileops.presentation.common.components.ConfirmButton
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OpportunityPromptViewModel @Inject constructor(
    private val opportunities: OpportunityCounts,
) : ViewModel() {
    val prompt = opportunities.prompt
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun submit(quantity: String) {
        val task = prompt.value ?: return
        viewModelScope.launch {
            when (val result = opportunities.submit(task, quantity)) {
                EndCountResult.Queued -> _error.value = null
                is EndCountResult.Refused -> _error.value = result.message
            }
        }
    }

    fun dismiss() {
        _error.value = null
        opportunities.dismiss()
    }
}

/**
 * The opportunity-count prompt: a dismissible single-line count, blind, offered by the server
 * right after a pick or putaway. Hosted by the receiving and transfer screens.
 */
@Composable
fun OpportunityCountPrompt(viewModel: OpportunityPromptViewModel = hiltViewModel()) {
    val task by viewModel.prompt.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val current = task ?: return
    // The shared quantity picker, like every quantity in the app; the question is its subtitle.
    androidx.compose.runtime.key(current.journalId) {
        com.d365fo.mobileops.presentation.common.components.QuantityPicker(
            subtitle = "Quick count: how many ${current.itemName} are at ${current.locationId ?: "this location"}?",
            hint = listOfNotNull(
                current.itemId.takeIf { it != current.itemName },
                current.batchId?.let { "Batch $it" },
                current.serialId?.let { "Serial $it" },
            ).joinToString(" · ").ifBlank { null },
            allowDecimal = false,
            confirmLabel = "Send count",
            dismissLabel = "Not now",
            error = error,
            onConfirm = viewModel::submit,
            onDismiss = viewModel::dismiss,
        )
    }
}

@HiltViewModel
class DiscrepancyViewModel @Inject constructor(
    private val remote: CountTaskRepository,
    private val workerSession: WorkerSessionRepository,
    private val scannerBridge: com.d365fo.mobileops.presentation.common.scan.HidScannerBridge,
    private val itemDao: com.d365fo.mobileops.data.local.dao.ItemDao,
    flags: FeatureFlags,
) : ViewModel() {
    val enabled: Boolean = flags.countDiscrepancy

    data class UiState(
        val open: Boolean = false,
        val busy: Boolean = false,
        val message: String? = null,
        val done: Boolean = false,
        /** The location scanned or typed in the dialog itself - see [open]. */
        val location: String = "",
        /** Optional item - a bulk location without one is too big to count (TOO_MANY_LINES). */
        val item: String = "",
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    /**
     * Opens the dialog with the screen's location, if it has one. While it is open, a scan is
     * the location - the dialog said "scan the location", and the scan went to the screen
     * underneath instead (Transfer - Ship took bin AAC for a transfer order).
     */
    fun open(locationId: String? = null, itemId: String? = null) {
        _state.update { UiState(open = true, location = locationId.orEmpty(), item = itemId.orEmpty()) }
        // First scan: the location; once there is one, a scan is the item.
        scannerBridge.modalCapture = { value ->
            _state.update {
                if (it.location.isBlank()) it.copy(location = value.trim().uppercase(), message = null)
                else it.copy(item = value.trim(), message = null)
            }
        }
    }

    fun close() {
        scannerBridge.modalCapture = null
        _state.update { UiState() }
    }

    fun setLocation(value: String) = _state.update { it.copy(location = value.trim(), message = null) }
    fun setItem(value: String) = _state.update { it.copy(item = value.trim(), message = null) }

    override fun onCleared() {
        if (_state.value.open) scannerBridge.modalCapture = null
    }

    fun report(locationId: String, itemId: String?, reason: DiscrepancyReason, note: String) {
        val warehouse = workerSession.session.value?.selected?.warehouseId
        if (warehouse.isNullOrBlank() || locationId.isBlank()) {
            _state.update { it.copy(message = "Scan the location first.") }
            return
        }
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            // A scanned barcode is the item it belongs to (the device's SKU cache); typed item numbers pass as they are.
            val item = itemId?.let { code -> runCatching { itemDao.resolveBarcode(code.uppercase()) }.getOrNull()?.itemId ?: code }
            val result = remote.reportDiscrepancy(warehouse, locationId, item, reason, note)
            val message = when (result) {
                is TaskResult.Ok -> result.value?.let { "Reported. D365FO created a count ($it) for this location." }
                    ?: "Reported. Thank you."
                is TaskResult.Refused -> result.message
                is TaskResult.Unreachable -> "Couldn't reach D365FO: ${result.message}"
            }
            // Refused or not sent: the form stays, with the reason, so the worker can add the
            // item ("too many stock lines - name the item") and report again without reopening.
            // The item as reported - the summary under the message showed the scanned barcode.
            _state.update { it.copy(busy = false, message = message, done = result is TaskResult.Ok, item = item ?: it.item) }
        }
    }
}

/**
 * "Report discrepancy" in picking: tell the server something is off at this location. The
 * server decides whether a triggered count follows.
 */
@Composable
fun ReportDiscrepancyAction(
    locationId: String?,
    itemId: String?,
    /**
     * Icon only, for a top bar already holding other actions: the label took the title's room
     * there, and the title wrapped one letter per line into a bar half the screen tall
     * (feedback c5618f98, Transfer - Ship with scanned lines).
     */
    compact: Boolean = false,
    /**
     * False: no button of its own - only the dialog, opened from a screen's overflow menu
     * through the same [DiscrepancyViewModel] (feedback 994bf988, a crowded top bar).
     */
    showTrigger: Boolean = true,
    viewModel: DiscrepancyViewModel = hiltViewModel(),
) {
    if (!viewModel.enabled) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!showTrigger) {
        // Dialog only - see showTrigger.
    } else if (compact) {
        androidx.compose.material3.IconButton(onClick = { viewModel.open(locationId, itemId) }) {
            Icon(Icons.Default.ReportProblem, contentDescription = "Report discrepancy")
        }
    } else {
        // Labelled where there is room: a warning triangle alone does not say what tapping it does.
        // Top-bar height, like the icon buttons beside it: at the full touch-target height the
        // bar grew 8 px on this screen only and moved the scan card below it.
        com.d365fo.mobileops.presentation.common.components.SquareTextButton(
            onClick = { viewModel.open(locationId, itemId) },
            modifier = androidx.compose.ui.Modifier.height(48.dp),
        ) {
            Icon(Icons.Default.ReportProblem, contentDescription = null)
            Text("Report", modifier = androidx.compose.ui.Modifier.padding(start = 4.dp), maxLines = 1)
        }
    }
    if (!state.open) return

    var reason by remember { mutableStateOf(DiscrepancyReason.SHORT_PICK) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = viewModel::close,
        title = { Text("Report discrepancy") },
        text = {
            // Scrolls when taller than the dialog allows - otherwise the last field (Note) was
            // squeezed and its label cut off on the handheld.
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                // First, where it is seen: below the fields it was cut off by the dialog's height.
                state.message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                }
                if (!state.done) {
                    // Scan the location (the scan comes here while the dialog is open) or type it.
                    OutlinedTextField(
                        value = state.location,
                        onValueChange = viewModel::setLocation,
                        label = { Text("Location - scan or type") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.item,
                        onValueChange = viewModel::setItem,
                        label = { Text("Item (optional) - scan or type") },
                        singleLine = true,
                    )
                } else {
                    Text(
                        listOfNotNull("Location ${state.location}", state.item.ifBlank { null }?.let { "Item $it" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (!state.done) {
                    DiscrepancyReason.entries.forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = reason == option, onClick = { reason = option })
                            Text(option.label)
                        }
                    }
                    // Full width and the fields' 64 dp, like the two above - its label was cut off.
                    OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp))
                }

            }
        },
        confirmButton = {
            com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                dismiss = if (state.done) null else ({ CancelOutlinedButton(onClick = viewModel::close, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } }),
                confirm = {
                    if (state.done) {
                        ConfirmButton(onClick = viewModel::close, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("OK") }
                    } else {
                        ConfirmButton(
                            onClick = { viewModel.report(state.location, state.item.ifBlank { null }, reason, note) },
                            enabled = !state.busy && state.location.isNotBlank(),
                            modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                        ) { Text(if (state.busy) "Sending…" else "Report") }
                    }
                },
            )
        },
    )
}
