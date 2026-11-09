package com.d365fo.mobileops.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.remote.dto.DeviceWorkItemDto
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.sync.ModuleSwitches
import com.d365fo.mobileops.data.sync.WorkBackup
import com.d365fo.mobileops.data.sync.WorkKind
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import com.d365fo.mobileops.presentation.common.components.AppCard
import com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton
import com.d365fo.mobileops.presentation.common.components.ConfirmButton
import com.d365fo.mobileops.presentation.common.components.DialogButtonRow
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The worker's work saved from one other device, as offered on Home. */
data class SavedWork(val deviceId: String, val items: List<DeviceWorkItemDto>) {
    /** "2 postings, 1 job" - what it holds. */
    fun describe(): String {
        fun n(kind: String, one: String, many: String) =
            items.count { it.kind == kind }.takeIf { it > 0 }?.let { "$it ${if (it == 1) one else many}" }
        return listOfNotNull(
            n(WorkKind.POSTING, "posting", "postings"),
            n(WorkKind.REFUSED, "refused posting", "refused postings"),
            n(WorkKind.JOB, "job", "jobs"),
            n(WorkKind.ORDER_DRAFT, "order draft", "order drafts"),
            n(WorkKind.COUNT, "journal of counts", "journals of counts"),
        ).joinToString(", ")
    }
}

data class RestoreState(
    val saved: SavedWork? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * After sign-in: work this worker left on another device, or on an earlier install of this app,
 * saved in D365FO by [WorkBackup] - offered once on Home to bring here, or to let go.
 */
@HiltViewModel
class RestoreWorkViewModel @Inject constructor(
    private val backup: WorkBackup,
    private val sessions: WorkerSessionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RestoreState())
    val state: StateFlow<RestoreState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sessions.session.map { it?.userName }.distinctUntilChanged().collect { user ->
                _state.value = RestoreState()
                if (!user.isNullOrBlank()) look(user)
            }
        }
    }

    private suspend fun look(user: String) {
        val result = backup.savedElsewhere(user)
        val items = (result as? NetworkResult.Success)?.data.orEmpty()
        // One device at a time - the most recent first.
        val device = items.firstOrNull()?.deviceId ?: return
        _state.value = RestoreState(saved = SavedWork(device, items.filter { it.deviceId == device }))
    }

    fun restore() {
        val saved = _state.value.saved ?: return
        val user = sessions.session.value?.userName ?: return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val result = backup.restore(saved.items)
            // Only a full restore lets the other copy go; otherwise it stays for another try.
            if (result.complete) backup.releaseFrom(saved.deviceId, user)
            _state.value = RestoreState(
                saved = if (result.complete) null else saved,
                message = if (result.complete) "Restored ${result.restored} - see Activity and Home"
                else (listOf("Restored ${result.restored}") + result.notes).joinToString(". "),
            )
        }
    }

    fun discard() {
        val saved = _state.value.saved ?: return
        val user = sessions.session.value?.userName ?: return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val ok = backup.releaseFrom(saved.deviceId, user)
            _state.value = if (ok) RestoreState(message = "Removed from D365FO") else _state.value.copy(busy = false, message = "Couldn't reach D365FO - try again")
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }
}

/** Home's card for [RestoreWorkViewModel]; nothing at all when there is no saved work. */
@Composable
fun RestoreWorkCard(modifier: Modifier = Modifier, viewModel: RestoreWorkViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            title = { Text("Saved work") },
            text = { Text(message) },
            confirmButton = { ConfirmButton(onClick = viewModel::dismissMessage) { Text("OK") } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Remove saved work?") },
            text = { Text("It is deleted from D365FO and can't be restored.") },
            confirmButton = {
                DialogButtonRow(
                    dismiss = {
                        CancelOutlinedButton(onClick = { confirmDiscard = false }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Keep") }
                    },
                    confirm = {
                        com.d365fo.mobileops.presentation.common.components.CancelButton(
                            onClick = { confirmDiscard = false; viewModel.discard() },
                            modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                        ) { Text("Remove") }
                    },
                )
            },
        )
    }

    val saved = state.saved ?: return
    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Saved from another device", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
            Text(saved.describe(), style = MaterialTheme.typography.bodyMedium, color = WarehouseStatusColors.text3)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                CancelOutlinedButton(
                    onClick = { confirmDiscard = true },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                ) { Text("Remove") }
                ConfirmButton(
                    onClick = viewModel::restore,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                ) { Text(if (state.busy) "Restoring…" else "Restore") }
            }
        }
    }
}

/** Modules switched off for everyone (Mobile module switches) - for the navigation guard. */
@HiltViewModel
class ModuleSwitchViewModel @Inject constructor(switches: ModuleSwitches) : ViewModel() {
    val disabled: StateFlow<Map<WarehouseWorkflow, String>> = switches.disabled
}

/** The module a route opens, from Home's module list - null for routes that aren't a module. */
internal fun workflowForRoute(route: String?): WarehouseWorkflow? {
    val base = route?.substringBefore('?')?.substringBefore("/{") ?: return null
    return MODULES.firstOrNull { tile -> tile.route?.substringBefore('?')?.substringBefore("/{") == base }?.workflow
}
