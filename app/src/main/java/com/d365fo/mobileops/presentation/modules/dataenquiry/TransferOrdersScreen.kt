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
import androidx.compose.foundation.layout.heightIn
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

    fun refresh(fresh: Boolean = false) {
        val warehouse = _state.value.warehouseId ?: return run {
            _state.update { it.copy(error = "No warehouse selected") }
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (val result = transfers.openTransferOrders(warehouse, fresh)) {
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
 * The open transfer orders for this warehouse, soonest first.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransferOrdersScreen(
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

    com.d365fo.mobileops.presentation.common.focus.FocusScreen(
        title = "Transfer orders",
        onBack = onBack,
        counter = state.orders.size.takeIf { it > 0 }?.toString(),
        band = when {
            state.error != null -> com.d365fo.mobileops.presentation.modules.stepflow.bandOf(
                com.d365fo.mobileops.presentation.common.focus.BandKind.Bad, state.error.orEmpty(),
            )
            !state.busy && state.orders.isEmpty() -> com.d365fo.mobileops.presentation.common.focus.BandState(
                com.d365fo.mobileops.presentation.common.focus.BandKind.Info, "No open transfer orders", state.warehouseId,
            )
            else -> null
        },
        topBarTrailing = {
            IconButton(onClick = { viewModel.refresh(fresh = true) }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = com.d365fo.mobileops.presentation.common.theme.FocusColors.ink)
            }
        },
    ) {
        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = WarehouseDimens.ScreenPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                com.d365fo.mobileops.presentation.common.focus.FocusSearchField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = "Transfer number or store",
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransferDirectionFilter.entries.forEach { filter ->
                        val count = when (filter) {
                            TransferDirectionFilter.ALL -> state.orders.size
                            TransferDirectionFilter.SHIP -> state.orders.count { it.outbound }
                            TransferDirectionFilter.RECEIVE -> state.orders.count { !it.outbound }
                        }
                        com.d365fo.mobileops.presentation.common.focus.FocusChip("${filter.label} $count", selected = state.filter == filter, onClick = { viewModel.setFilter(filter) })
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val visible = state.visible
                when {
                    state.busy && state.orders.isEmpty() ->
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    state.error != null || state.orders.isEmpty() -> Unit
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
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = WarehouseDimens.ItemSpacing),
                    ) {
                        items(visible, key = { it.transferId }) { TransferRow(it, showStore = true, onClick = { open(it) }) }
                    }
                }
            }
        }
    }
}

/** A transfer as a Focus row: Ship or Receive on the right (brief section 9), overdue said in words. */
@Composable
private fun TransferRow(order: TransferOrderSummary, showStore: Boolean, onClick: () -> Unit) {
    val due = (if (order.outbound) order.shipDate else order.receiveDate).takeIf { Formatters.isoDay(it) != null }.orEmpty()
    val late = Formatters.isoDay(due)?.isBefore(LocalDate.now()) == true
    com.d365fo.mobileops.presentation.common.focus.FocusListRow(
        title = order.transferId,
        titleIsCode = true,
        state = if (late) com.d365fo.mobileops.presentation.common.focus.RowState.Warn else com.d365fo.mobileops.presentation.common.focus.RowState.Open,
        subtitle = listOfNotNull(
            (if (order.outbound) "To ${order.toWarehouseId}" else "From ${order.fromWarehouseId}").takeIf { showStore },
            "${order.lineCount} " + if (order.lineCount == 1) "line" else "lines",
            due.takeIf { it.isNotBlank() }?.let { (if (order.outbound) "Ship date " else "Receive date ") + Formatters.dayLabel(it) + if (late) " · overdue" else "" },
        ).joinToString(" · "),
        count = if (order.outbound) "Ship" else "Receive",
        countColor = com.d365fo.mobileops.presentation.common.theme.FocusColors.navy,
        chevron = true,
        onClick = onClick,
    )
}
