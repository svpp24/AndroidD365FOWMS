package com.d365fo.mobileops.presentation.common

import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.local.D365Database
import com.d365fo.mobileops.data.repository.WorkerContextRepositoryImpl
import com.d365fo.mobileops.data.security.ClientCredentialsTokenProvider
import com.d365fo.mobileops.data.security.ConnectionQr
import com.d365fo.mobileops.data.security.ConnectionSettings
import com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton
import com.d365fo.mobileops.presentation.common.components.ConfirmButton
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

sealed interface ConnectionQrState {
    data object Idle : ConnectionQrState
    data object Scanning : ConnectionQrState
    /** A PIN-protected code was scanned; [wrongPin] after a failed attempt. */
    data class NeedsPin(val raw: String, val wrongPin: Boolean = false) : ConnectionQrState
    data object Applying : ConnectionQrState
    data class Done(val message: String) : ConnectionQrState
    data class Failed(val message: String) : ConnectionQrState
    /** The code points somewhere new, and work on this device hasn't reached D365FO yet. */
    data class UnsentWork(val description: String, val ready: ConnectionQr.Result.Ready) : ConnectionQrState
}

/**
 * Applies a scanned connection QR code (see [ConnectionQr]): saves the connection, wipes local
 * data when it points the device somewhere new (order and item data from two environments must
 * never mix - the same rule as Settings), sets the company, and proves it by fetching a token.
 */
@HiltViewModel
class ConnectionQrViewModel @Inject constructor(
    private val connectionSettings: ConnectionSettings,
    private val tokenProvider: ClientCredentialsTokenProvider,
    private val workerContextRepository: WorkerContextRepositoryImpl,
    private val database: D365Database,
    private val unsentWork: com.d365fo.mobileops.data.sync.UnsentWork,
) : ViewModel() {

    private val _state = MutableStateFlow<ConnectionQrState>(ConnectionQrState.Idle)
    val state: StateFlow<ConnectionQrState> = _state.asStateFlow()

    fun startScan() {
        _state.value = ConnectionQrState.Scanning
    }

    fun dismiss() {
        _state.value = ConnectionQrState.Idle
    }

    fun onScanned(raw: String) {
        if (!ConnectionQr.isConnectionQr(raw)) {
            _state.value = ConnectionQrState.Failed("Not a connection code.")
            return
        }
        apply(raw, pin = null)
    }

    fun onPin(raw: String, pin: String) = apply(raw, pin)

    private fun apply(raw: String, pin: String?) {
        _state.value = ConnectionQrState.Applying
        viewModelScope.launch {
            // PBKDF2 at 120,000 rounds takes a second or two on a handheld: off the main thread.
            val result = withContext(Dispatchers.Default) { ConnectionQr.read(raw, pin) }
            _state.value = when (result) {
                ConnectionQr.Result.NeedsPin -> ConnectionQrState.NeedsPin(raw)
                is ConnectionQr.Result.Invalid ->
                    if (pin != null && result.reason.startsWith("Wrong PIN")) {
                        ConnectionQrState.NeedsPin(raw, wrongPin = true)
                    } else {
                        ConnectionQrState.Failed(result.reason)
                    }
                is ConnectionQr.Result.Ready -> save(result)
            }
        }
    }

    /** The worker chose to delete the unsent work and change the connection anyway. */
    fun deleteUnsentAndApply(ready: ConnectionQr.Result.Ready) {
        _state.value = ConnectionQrState.Applying
        viewModelScope.launch { _state.value = save(ready, discardConfirmed = true) }
    }

    private suspend fun save(ready: ConnectionQr.Result.Ready, discardConfirmed: Boolean = false): ConnectionQrState {
        // A new target wipes the local database - never over work that hasn't been sent.
        if (!discardConfirmed && connectionSettings.wouldChangeTarget(ready.config)) {
            val unsent = unsentWork.summary()
            if (!unsent.isEmpty) return ConnectionQrState.UnsentWork(unsent.describe(), ready)
        }
        // First setup has nothing to wipe: skipping it there keeps a fresh install from sitting
        // on "Setting up…" (30 Oct: the code was saved, but the screen never moved on until the
        // app was reopened).
        val hadTarget = connectionSettings.tenant.value.resourceUrl.isNotBlank()
        try {
            withContext(Dispatchers.IO) {
                val changed = connectionSettings.saveTenant(ready.config)
                Timber.i("connection QR: saved (changed=%s, wipe=%s)", changed, changed && hadTarget)
                if (changed && hadTarget) database.clearAllTables()
                tokenProvider.invalidateCache()
                if (ready.company.isNotBlank()) {
                    workerContextRepository.setRegistration(personnelNumber = null, dataAreaId = ready.company)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "connection QR: save failed")
            return ConnectionQrState.Failed("Couldn't save the connection: ${e.message}")
        }
        val apps = ready.config.credentials.size
        val connected = "${ready.config.host} · $apps app registration" + if (apps == 1) "" else "s"
        // Bounded: the connection is saved either way, and the device gate re-checks the token.
        val token = withTimeoutOrNull(TOKEN_CHECK_MS) { tokenProvider.acquireSilent() }
        Timber.i("connection QR: token check %s", token?.javaClass?.simpleName ?: "timed out")
        return when (token) {
            is NetworkResult.Success -> ConnectionQrState.Done("Connected to $connected.")
            is NetworkResult.Error -> ConnectionQrState.Failed(
                "Saved $connected, but signing in failed: ${token.failure.message}",
            )
            NetworkResult.Loading, null -> ConnectionQrState.Done("Saved $connected.")
        }
    }

    private companion object {
        const val TOKEN_CHECK_MS = 45_000L
    }
}

/**
 * The "Scan connection QR" flow: camera, then a PIN prompt for a protected code, then the
 * result. Camera only - a hardware scanner's keyboard path upper-cases what it reads, which
 * would corrupt the case-sensitive code.
 */
@Composable
fun ConnectionQrSetup(
    modifier: Modifier = Modifier,
    /** Called once a scanned code is saved and signed in - e.g. to re-check the session. */
    onConnected: () -> Unit = {},
    viewModel: ConnectionQrViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(state) {
        if (state is ConnectionQrState.Done) onConnected()
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        com.d365fo.mobileops.presentation.common.focus.FocusButton(
            text = if (state is ConnectionQrState.Applying) "Setting up…" else "Scan connection QR",
            onClick = viewModel::startScan,
            primary = true,
            modifier = Modifier.fillMaxWidth(),
        )
        when (val s = state) {
            is ConnectionQrState.Done -> com.d365fo.mobileops.presentation.common.components.SuccessBanner(s.message, viewModel::dismiss)
            is ConnectionQrState.Failed -> com.d365fo.mobileops.presentation.common.components.ErrorBanner(s.message, onDismiss = viewModel::dismiss)
            else -> Unit
        }
    }

    when (val s = state) {
        ConnectionQrState.Scanning -> CameraScannerSheet(
            onDecoded = viewModel::onScanned,
            onDismiss = viewModel::dismiss,
        )
        is ConnectionQrState.UnsentWork -> com.d365fo.mobileops.presentation.common.components.UnsentWorkDialog(
            description = s.description,
            onKeep = viewModel::dismiss,
            onDelete = { viewModel.deleteUnsentAndApply(s.ready) },
        )
        is ConnectionQrState.NeedsPin -> PinDialog(
            wrongPin = s.wrongPin,
            onPin = { viewModel.onPin(s.raw, it) },
            onCancel = viewModel::dismiss,
        )
        else -> Unit
    }
}

@Composable
private fun PinDialog(wrongPin: Boolean, onPin: (String) -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Setup PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (wrongPin) "That PIN did not open the code. Try again."
                    else "Enter the setup PIN.",
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
                confirm = { com.d365fo.mobileops.presentation.common.components.ConfirmButton(onClick = { onPin(pin) }, enabled = pin.isNotBlank(), modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Open") } },
            )
        },
    )
}
