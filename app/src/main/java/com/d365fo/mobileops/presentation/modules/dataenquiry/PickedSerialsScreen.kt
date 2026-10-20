package com.d365fo.mobileops.presentation.modules.dataenquiry

import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.repository.SerialHistoryEntry
import com.d365fo.mobileops.data.repository.TransferListRepository
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.util.Formatters
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar

data class PickedSerialsState(
    val warehouseId: String? = null,
    val days: Int = 30,
    val query: String = "",
    val entries: List<SerialHistoryEntry> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Serial numbers that left this warehouse recently - picked, shipped or sold - with the order
 * that took each one. Answers "where did serial X go?" without going to D365FO.
 */
@HiltViewModel
class PickedSerialsViewModel @Inject constructor(
    private val transfers: TransferListRepository,
    private val workerSession: WorkerSessionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PickedSerialsState())
    val state: StateFlow<PickedSerialsState> = _state.asStateFlow()
    private var searchJob: Job? = null

    init {
        _state.update { it.copy(warehouseId = workerSession.session.value?.selected?.warehouseId) }
        load()
    }

    fun setDays(days: Int) {
        _state.update { it.copy(days = days) }
        load()
    }

    /** The search goes to D365FO (it searches the whole period, not just what is loaded), so it waits for typing to pause. */
    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_PAUSE_MS)
            load()
        }
    }

    fun load() {
        val s = _state.value
        val warehouse = s.warehouseId ?: return run { _state.update { it.copy(error = "No warehouse selected") } }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (val result = transfers.pickedSerials(warehouse, s.days, s.query)) {
                is NetworkResult.Success -> _state.update { it.copy(busy = false, entries = result.data) }
                is NetworkResult.Error -> _state.update { it.copy(busy = false, error = result.failure.message) }
                NetworkResult.Loading -> Unit
            }
        }
    }

    private companion object {
        const val SEARCH_PAUSE_MS = 500L
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PickedSerialsScreen(onBack: () -> Unit, viewModel: PickedSerialsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Picked serials") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                // Refresh like the other list inquiries (Transfer orders, TO's by Store).
                actions = {
                    IconButton(onClick = viewModel::load, enabled = !state.busy) {
                        Icon(androidx.compose.material.icons.Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.padding(horizontal = WarehouseDimens.ScreenPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Serial or item") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.load() }),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 30, 90).forEach { days ->
                        FilterChip(
                            selected = state.days == days,
                            onClick = { viewModel.setDays(days) },
                            label = { Text("$days days") },
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.busy && state.entries.isEmpty() ->
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    state.error != null -> ErrorBanner(
                        message = state.error.orEmpty(),
                        onDismiss = viewModel::load,
                        modifier = Modifier.align(Alignment.TopCenter).padding(WarehouseDimens.ScreenPadding),
                    )
                    state.entries.isEmpty() -> Text(
                        text = if (state.query.isBlank()) {
                            "No serial numbers left ${state.warehouseId.orEmpty()} in the last ${state.days} days."
                        } else {
                            "Nothing matches \"${state.query}\" in the last ${state.days} days."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(WarehouseDimens.ScreenPadding),
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = WarehouseDimens.ScreenPadding),
                        contentPadding = PaddingValues(bottom = WarehouseDimens.ItemSpacing),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(state.entries, key = { i, e -> "$i-${e.serialId}-${e.reference}" }) { _, entry ->
                            SerialRow(entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SerialRow(entry: SerialHistoryEntry) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.serialId,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(Formatters.dayLabel(entry.transDate), style = MaterialTheme.typography.labelMedium, color = WarehouseStatusColors.text3)
            }
            Text("${entry.itemId} — ${entry.itemName}".trimEnd(' ', '—'), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = listOfNotNull(
                    "${readableType(entry.referenceType)} ${entry.reference}".trim().takeIf { entry.reference.isNotBlank() },
                    readableStatus(entry).takeIf { it.isNotBlank() },
                    entry.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = WarehouseStatusColors.text3,
            )
        }
    }
}

/** D365FO's reference type symbols, as a worker would say them. */
private fun readableType(symbol: String): String = when (symbol) {
    "TransferOrderShip", "TransferOrderReceive", "InventTransfer" -> "Transfer"
    "Sales" -> "Sales order"
    "Purch" -> "Purchase order"
    "InventCounting", "InventLossProfit", "InventTransaction" -> "Journal"
    else -> symbol
}

/** D365FO books a shipped transfer as "Sold" - true in its terms, wrong on a transfer card. */
private fun readableStatus(entry: SerialHistoryEntry): String = when {
    readableType(entry.referenceType) == "Transfer" && entry.status == "Sold" -> "Shipped"
    entry.status == "Deducted" -> "Issued"
    else -> entry.status
}
