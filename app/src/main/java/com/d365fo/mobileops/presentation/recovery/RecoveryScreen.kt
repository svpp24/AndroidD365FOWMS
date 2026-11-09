package com.d365fo.mobileops.presentation.recovery

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.sync.UnsentWork
import com.d365fo.mobileops.data.sync.WorkBackup
import com.d365fo.mobileops.data.sync.WorkFile
import com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton
import com.d365fo.mobileops.presentation.common.components.ConfirmButton
import com.d365fo.mobileops.presentation.common.components.DialogButtonRow
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class WorkSaveState(
    /** "2 postings waiting to send, 1 unfinished job" - or null when nothing is unsent. */
    val unsent: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

/** Saving unsent work: to D365FO, or to a PIN-protected file - and reading such a file back. */
@HiltViewModel
class WorkSaveViewModel @Inject constructor(
    private val unsentWork: UnsentWork,
    private val backup: WorkBackup,
    private val workFile: WorkFile,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkSaveState())
    val state: StateFlow<WorkSaveState> = _state.asStateFlow()

    val lastBackupAt: StateFlow<Long?> = backup.lastBackupAt
    val waiting: StateFlow<Boolean> = backup.waiting

    init {
        refreshSummary()
    }

    fun refreshSummary() {
        viewModelScope.launch {
            val summary = runCatching { unsentWork.summary() }.getOrNull()
            _state.value = _state.value.copy(unsent = summary?.takeIf { !it.isEmpty }?.describe())
        }
    }

    fun backUp() = run("Saving to D365FO…") {
        if (backup.backUpNow()) "Saved in D365FO" else "Couldn't reach D365FO - save to a file instead"
    }

    fun export(uri: Uri, pin: String) = run("Saving…") {
        val n = workFile.export(uri, pin)
        "Saved $n to the file. Keep the PIN - the file can't be opened without it."
    }

    fun import(uri: Uri, pin: String) = run("Restoring…") {
        val items = workFile.read(uri, pin) ?: return@run "Wrong PIN, or not a work file"
        val result = backup.restore(items)
        (listOf("Restored ${result.restored}") + result.notes).joinToString(". ")
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun run(busyText: String, block: suspend () -> String) {
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            val message = runCatching { block() }.getOrElse {
                Timber.e(it, "saving unsent work failed")
                "Couldn't save: ${it.message}"
            }
            _state.value = _state.value.copy(busy = false, message = message)
            refreshSummary()
        }
    }
}

/**
 * Shown instead of the app after it stopped [com.d365fo.mobileops.data.crash.CrashLoopGuard.CRASHES]
 * times in a row: nothing else is started, so whatever keeps failing can't fail again. The worker
 * saves the unsent work (to D365FO, or a file), then tries the app again.
 */
@Composable
fun RecoveryScreen(
    lastCrash: String?,
    onStartNormally: () -> Unit,
    viewModel: WorkSaveViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("The app stopped several times", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                state.unsent?.let { "On this device: $it. Save it before anything else." } ?: "No unsent work on this device.",
                style = MaterialTheme.typography.bodyLarge,
            )
            WorkSaveButtons(viewModel = viewModel, showImport = false)
            ConfirmButton(onClick = onStartNormally, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget)) {
                Text("Start the app")
            }
            Text(
                "Still stopping? Install the fixed app over this one. Don't uninstall - that deletes unsent work.",
                style = MaterialTheme.typography.bodySmall,
                color = WarehouseStatusColors.text3,
            )
            lastCrash?.let {
                Text("Last error: $it", style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.text3)
            }
        }
    }
}

/**
 * "Save to D365FO" and "Save to a file" (and "Restore from a file") - on the recovery screen and
 * in Settings. A file asks for a PIN first; the system's file picker says where it goes.
 */
@Composable
fun WorkSaveButtons(viewModel: WorkSaveViewModel = hiltViewModel(), showImport: Boolean = true, showBackup: Boolean = true) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pinFor by remember { mutableStateOf<PinPurpose?>(null) }
    var pendingPin by remember { mutableStateOf("") }

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(WorkFile.MIME)) { uri ->
        if (uri != null) viewModel.export(uri, pendingPin)
        pendingPin = ""
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri, pendingPin)
        pendingPin = ""
    }
    LaunchedEffect(Unit) { viewModel.refreshSummary() }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            title = { Text("Unsent work") },
            text = { Text(message) },
            confirmButton = { ConfirmButton(onClick = viewModel::dismissMessage) { Text("OK") } },
        )
    }
    pinFor?.let { purpose ->
        PinDialog(
            purpose = purpose,
            onPin = { pin ->
                pinFor = null
                pendingPin = pin
                when (purpose) {
                    PinPurpose.EXPORT -> createFile.launch(WorkFile.suggestedName())
                    PinPurpose.IMPORT -> openFile.launch(arrayOf("*/*"))
                }
            },
            onCancel = { pinFor = null },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        if (showBackup) {
            SquareOutlinedButton(onClick = viewModel::backUp, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget)) {
                Text(if (state.busy) "Working…" else "Save to D365FO")
            }
        }
        SquareOutlinedButton(onClick = { pinFor = PinPurpose.EXPORT }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget)) {
            Text("Save to a file")
        }
        if (showImport) {
            SquareOutlinedButton(onClick = { pinFor = PinPurpose.IMPORT }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget)) {
                Text("Restore from a file")
            }
        }
    }
}

private enum class PinPurpose { EXPORT, IMPORT }

@Composable
private fun PinDialog(purpose: PinPurpose, onPin: (String) -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val export = purpose == PinPurpose.EXPORT
    val ok = pin.length >= 4 && (!export || pin == again)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (export) "Choose a PIN" else "File PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (export) "At least 4 digits. Needed to open the file." else "The PIN chosen when the file was saved.")
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit) },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (export) {
                    OutlinedTextField(
                        value = again,
                        onValueChange = { again = it.filter(Char::isDigit) },
                        label = { Text("PIN again") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            DialogButtonRow(
                dismiss = { CancelOutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
                confirm = { ConfirmButton(onClick = { onPin(pin) }, enabled = ok, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Next") } },
            )
        },
    )
}
