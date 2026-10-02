package com.d365fo.mobileops.presentation.modules.cyclecounting.opportunity

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
    var quantity by remember(current.journalId) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text("Quick count?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("While you're here: how many ${current.itemName} are at ${current.locationId ?: "this location"}?")
                listOfNotNull(
                    current.itemId.takeIf { it != current.itemName },
                    current.batchId?.let { "Batch $it" },
                    current.serialId?.let { "Serial $it" },
                ).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.text3)
                }
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it.filter(Char::isDigit).take(12) },
                    label = { Text("Quantity counted") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { ConfirmButton(onClick = { viewModel.submit(quantity) }, enabled = quantity.isNotEmpty()) { Text("Send count") } },
        dismissButton = { CancelOutlinedButton(onClick = viewModel::dismiss) { Text("Not now") } },
    )
}

@HiltViewModel
class DiscrepancyViewModel @Inject constructor(
    private val remote: CountTaskRepository,
    private val workerSession: WorkerSessionRepository,
    flags: FeatureFlags,
) : ViewModel() {
    val enabled: Boolean = flags.countDiscrepancy

    data class UiState(val open: Boolean = false, val busy: Boolean = false, val message: String? = null, val done: Boolean = false)

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    fun open() = _state.update { UiState(open = true) }
    fun close() = _state.update { UiState() }

    fun report(locationId: String, itemId: String?, reason: DiscrepancyReason, note: String) {
        val warehouse = workerSession.session.value?.selected?.warehouseId
        if (warehouse.isNullOrBlank() || locationId.isBlank()) {
            _state.update { it.copy(message = "Scan the location first.") }
            return
        }
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = when (val result = remote.reportDiscrepancy(warehouse, locationId, itemId, reason, note)) {
                is TaskResult.Ok -> result.value?.let { "Reported. D365FO created a count ($it) for this location." }
                    ?: "Reported. Thank you."
                is TaskResult.Refused -> result.message
                is TaskResult.Unreachable -> "Couldn't reach D365FO: ${result.message}"
            }
            _state.update { it.copy(busy = false, message = message, done = true) }
        }
    }
}

/**
 * "Report discrepancy" in picking: tell the server something is off at this location. The
 * server decides whether a triggered count follows.
 */
@Composable
fun ReportDiscrepancyAction(locationId: String?, itemId: String?, viewModel: DiscrepancyViewModel = hiltViewModel()) {
    if (!viewModel.enabled) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    IconButton(onClick = viewModel::open) {
        Icon(Icons.Default.ReportProblem, contentDescription = "Report discrepancy")
    }
    if (!state.open) return

    var reason by remember { mutableStateOf(DiscrepancyReason.SHORT_PICK) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = viewModel::close,
        title = { Text("Report discrepancy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    listOfNotNull(locationId?.let { "Location $it" }, itemId?.let { "Item $it" })
                        .joinToString(" · ").ifEmpty { "Scan the location first." },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!state.done) {
                    DiscrepancyReason.entries.forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = reason == option, onClick = { reason = option })
                            Text(option.label)
                        }
                    }
                    OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") })
                }
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (state.done) {
                ConfirmButton(onClick = viewModel::close) { Text("OK") }
            } else {
                ConfirmButton(
                    onClick = { viewModel.report(locationId.orEmpty(), itemId, reason, note) },
                    enabled = !state.busy && !locationId.isNullOrBlank(),
                ) { Text(if (state.busy) "Sending…" else "Report") }
            }
        },
        dismissButton = { if (!state.done) CancelOutlinedButton(onClick = viewModel::close) { Text("Cancel") } },
    )
}
