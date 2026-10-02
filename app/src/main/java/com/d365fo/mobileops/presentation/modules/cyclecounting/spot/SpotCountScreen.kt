package com.d365fo.mobileops.presentation.modules.cyclecounting.spot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.local.entity.TransferMode
import com.d365fo.mobileops.data.repository.CountingDownloadTracker
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.domain.counting.CountTaskRepository
import com.d365fo.mobileops.domain.counting.TaskResult
import com.d365fo.mobileops.domain.model.CountingJournalSummary
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SpotCountState(
    val warehouseId: String? = null,
    val itemId: String = "",
    val locationId: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    /** Set once the server created the journal - the screen then closes. */
    val createdJournalId: String? = null,
)

/**
 * A spot count on demand: item and/or location in the worker's warehouse. The server creates
 * the journal and its lines - the app never inserts journal lines - and the app then fetches it
 * (DIRECT mode) like any other journal. A scan fills whichever field is empty, item first.
 */
@HiltViewModel
class SpotCountViewModel @Inject constructor(
    private val remote: CountTaskRepository,
    private val tracker: CountingDownloadTracker,
    workerSession: WorkerSessionRepository,
    scannerBridge: HidScannerBridge,
) : ViewModel() {

    private val _state = MutableStateFlow(SpotCountState(warehouseId = workerSession.session.value?.selected?.warehouseId))
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            scannerBridge.scans.collect { scan ->
                _state.update {
                    if (it.itemId.isBlank()) it.copy(itemId = scan.value) else it.copy(locationId = scan.value)
                }
            }
        }
    }

    fun setItem(value: String) = _state.update { it.copy(itemId = value.trim(), error = null) }
    fun setLocation(value: String) = _state.update { it.copy(locationId = value.trim(), error = null) }

    fun create() {
        val s = _state.value
        val warehouse = s.warehouseId
        if (warehouse.isNullOrBlank()) {
            _state.update { it.copy(error = "No warehouse is assigned to you.") }
            return
        }
        if (s.itemId.isBlank() && s.locationId.isBlank()) {
            _state.update { it.copy(error = "Enter an item, a location, or both.") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = remote.createSpotCount(warehouse, s.itemId, s.locationId)) {
                is TaskResult.Ok -> {
                    tracker.start(
                        CountingJournalSummary(
                            journalId = result.value,
                            journalNameId = "",
                            description = listOfNotNull(
                                "Spot count",
                                s.itemId.takeIf { it.isNotBlank() },
                                s.locationId.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            journalDate = System.currentTimeMillis(),
                            lineCount = 0,
                            transferMode = TransferMode.DIRECT,
                            originType = "SPOT",
                        ),
                        warehouse,
                    )
                    _state.update { it.copy(busy = false, createdJournalId = result.value) }
                }
                is TaskResult.Refused -> _state.update { it.copy(busy = false, error = result.message) }
                is TaskResult.Unreachable -> _state.update { it.copy(busy = false, error = "Couldn't reach D365FO: ${result.message}") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotCountScreen(onBack: () -> Unit, onCreated: () -> Unit, viewModel: SpotCountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.createdJournalId) { if (state.createdJournalId != null) onCreated() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New spot count") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(WarehouseDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Count an item, a location, or one item in one location, in ${state.warehouseId ?: "your warehouse"}. " +
                    "D365FO creates the count; it appears in your journal list.",
                style = MaterialTheme.typography.bodyMedium,
                color = WarehouseStatusColors.text3,
            )
            OutlinedTextField(
                value = state.itemId,
                onValueChange = viewModel::setItem,
                label = { Text("Item (scan or type)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.locationId,
                onValueChange = viewModel::setLocation,
                label = { Text("Location (scan or type)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let { ErrorBanner(message = it) }
            Button(
                onClick = viewModel::create,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
            ) { Text(if (state.busy) "Creating…" else "Create spot count") }
        }
    }
}
