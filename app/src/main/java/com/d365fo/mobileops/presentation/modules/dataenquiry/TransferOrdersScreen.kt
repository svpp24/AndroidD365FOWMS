package com.d365fo.mobileops.presentation.modules.dataenquiry

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.local.entity.FlowType
import com.d365fo.mobileops.data.repository.PendingFlowResume
import com.d365fo.mobileops.data.repository.TransferListRepository
import com.d365fo.mobileops.data.repository.TransferOrderSummary
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.components.StatusChip
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.util.Formatters
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar

enum class TransferDirectionFilter(val label: String) { ALL("All"), SHIP("To ship"), RECEIVE("To receive") }

data class TransferOrdersState(
    val warehouseId: String? = null,
    val orders: List<TransferOrderSummary> = emptyList(),
    val filter: TransferDirectionFilter = TransferDirectionFilter.ALL,
    val query: String = "",
    val busy: Boolean = false,
    val error: String? = null,
) {
    val visible: List<TransferOrderSummary>
        get() = orders.filter {
            when (filter) {
                TransferDirectionFilter.ALL -> true
                TransferDirectionFilter.SHIP -> it.outbound
                TransferDirectionFilter.RECEIVE -> !it.outbound
            }
        }.filter {
            query.isBlank() || it.transferId.contains(query.trim(), ignoreCase = true) ||
                it.otherWarehouseId.contains(query.trim(), ignoreCase = true)
        }
}

/**
 * Open transfer orders this warehouse can act on now: to ship from here, or shipped to here and
 * not yet received. Loads itself; a tap opens Transfer - Ship or Receive on that order.
 */
@HiltViewModel
class TransferOrdersViewModel @Inject constructor(
    private val transfers: TransferListRepository,
    private val workerSession: WorkerSessionRepository,
    private val pendingFlowResume: PendingFlowResume,
) : ViewModel() {

    private val _state = MutableStateFlow(TransferOrdersState())
    val state: StateFlow<TransferOrdersState> = _state.asStateFlow()

    init {
        _state.update { it.copy(warehouseId = workerSession.session.value?.selected?.warehouseId) }
        refresh()
    }

    fun refresh() {
        val warehouse = _state.value.warehouseId ?: return run {
            _state.update { it.copy(error = "No warehouse selected") }
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (val result = transfers.openTransferOrders(warehouse)) {
                is NetworkResult.Success -> _state.update { it.copy(busy = false, orders = result.data) }
                is NetworkResult.Error -> _state.update { it.copy(busy = false, error = result.failure.message) }
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun setFilter(filter: TransferDirectionFilter) = _state.update { it.copy(filter = filter) }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    /** Hands [order] to the matching transfer flow; the caller navigates there. */
    fun open(order: TransferOrderSummary) =
        pendingFlowResume.set(if (order.outbound) FlowType.TRANSFER_SHIP else FlowType.TRANSFER_RECEIVE, order.transferId)
}

/**
 * The open transfer orders for this warehouse. [byStore] groups them under the other store - the
 * "TO's by Store" view - instead of one list soonest first.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransferOrdersScreen(
    byStore: Boolean,
    onBack: () -> Unit,
    onOpenShip: () -> Unit,
    onOpenReceive: () -> Unit,
    viewModel: TransferOrdersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val open: (TransferOrderSummary) -> Unit = { order ->
        viewModel.open(order)
        if (order.outbound) onOpenShip() else onOpenReceive()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (byStore) "TO's by Store" else "Transfer orders") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
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
                    placeholder = { Text("Transfer number or store") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransferDirectionFilter.entries.forEach { filter ->
                        val count = when (filter) {
                            TransferDirectionFilter.ALL -> state.orders.size
                            TransferDirectionFilter.SHIP -> state.orders.count { it.outbound }
                            TransferDirectionFilter.RECEIVE -> state.orders.count { !it.outbound }
                        }
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { viewModel.setFilter(filter) },
                            label = { Text("${filter.label} $count") },
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val visible = state.visible
                when {
                    state.busy && state.orders.isEmpty() ->
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    state.error != null -> ErrorBanner(
                        message = state.error.orEmpty(),
                        onDismiss = viewModel::refresh,
                        modifier = Modifier.align(Alignment.TopCenter).padding(WarehouseDimens.ScreenPadding),
                    )
                    visible.isEmpty() -> Text(
                        text = if (state.orders.isEmpty()) {
                            "No open transfer orders for ${state.warehouseId.orEmpty()}."
                        } else {
                            "No transfer orders match."
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
                        if (byStore) {
                            visible.groupBy { it.otherWarehouseId }.toSortedMap().forEach { (store, orders) ->
                                item(key = "store-$store") {
                                    Text(
                                        text = "$store · ${orders.size} " + if (orders.size == 1) "order" else "orders",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                                items(orders, key = { it.transferId }) { TransferRow(it, showStore = false, onClick = { open(it) }) }
                            }
                        } else {
                            items(visible, key = { it.transferId }) { TransferRow(it, showStore = true, onClick = { open(it) }) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransferRow(order: TransferOrderSummary, showStore: Boolean, onClick: () -> Unit) {
    val due = (if (order.outbound) order.shipDate else order.receiveDate).takeIf { Formatters.isoDay(it) != null }.orEmpty()
    val late = Formatters.isoDay(due)?.isBefore(LocalDate.now()) == true
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        order.transferId,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    if (order.outbound) {
                        StatusChip(text = "To ship", color = WarehouseStatusColors.accent, dimColor = WarehouseStatusColors.accentDim)
                    } else {
                        StatusChip(text = "To receive", color = WarehouseStatusColors.green, dimColor = WarehouseStatusColors.greenDim)
                    }
                }
                Text(
                    text = listOfNotNull(
                        (if (order.outbound) "To ${order.toWarehouseId}" else "From ${order.fromWarehouseId}").takeIf { showStore },
                        "${order.lineCount} " + if (order.lineCount == 1) "line" else "lines",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (due.isNotBlank()) {
                    Text(
                        text = (if (order.outbound) "Ship date: " else "Receive date: ") + Formatters.dayLabel(due) +
                            if (late) " · overdue" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (late) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (late) WarehouseStatusColors.red else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (order.outbound) "Ship" else "Receive",
                tint = WarehouseStatusColors.text3,
            )
        }
    }
}
