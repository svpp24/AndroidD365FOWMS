package com.d365fo.mobileops.presentation.modules.dataenquiry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.domain.repository.PurchaseOrderSummary
import com.d365fo.mobileops.presentation.common.focus.BandKind
import com.d365fo.mobileops.presentation.common.focus.BandState
import com.d365fo.mobileops.presentation.common.focus.FocusListRow
import com.d365fo.mobileops.presentation.common.focus.FocusScreen
import com.d365fo.mobileops.presentation.common.focus.RowState
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusShapes
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.util.Formatters
import java.time.LocalDate

/**
 * Every open purchase order of the store, in Transfer orders' layout (user, 13 Nov): refresh,
 * search by PO number or vendor, chips by delivery date, and rows with Receive on the right and
 * overdue said in words. A tap starts receiving the order.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PoOfTodayScreen(
    onBack: () -> Unit,
    /** Opens PO receiving; the order was handed over through [PoOfTodayViewModel.startReceiving]. */
    onReceive: () -> Unit = {},
    viewModel: PoOfTodayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FocusScreen(
        title = "Purchase orders",
        onBack = onBack,
        counter = state.orders.size.takeIf { it > 0 }?.toString(),
        band = when {
            state.error != null -> com.d365fo.mobileops.presentation.modules.stepflow.bandOf(BandKind.Bad, state.error.orEmpty())
            !state.busy && state.orders.isEmpty() -> BandState(BandKind.Info, "No open purchase orders", state.warehouseId)
            else -> null
        },
        topBarTrailing = {
            IconButton(onClick = viewModel::refresh, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = FocusColors.ink)
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
                    placeholder = "PO number or vendor",
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PoDueFilter.entries.forEach { filter ->
                        com.d365fo.mobileops.presentation.common.focus.FocusChip("${filter.label} ${state.orders.count { it.dueIn(filter) }}", selected = state.filter == filter, onClick = { viewModel.setFilter(filter) })
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val visible = state.visible
                when {
                    state.busy && state.orders.isEmpty() -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    state.error != null || state.orders.isEmpty() -> Unit
                    visible.isEmpty() -> Text(
                        text = "No purchase orders match.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(WarehouseDimens.ScreenPadding),
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = WarehouseDimens.ItemSpacing),
                    ) {
                        items(visible, key = { it.purchId }) { order ->
                            PurchaseOrderRow(order, onClick = {
                                viewModel.startReceiving(order.purchId)
                                onReceive()
                            })
                        }
                    }
                }
            }
        }
    }
}

/** A purchase order as a Focus row, like a transfer's: Receive on the right, overdue said in words. */
@Composable
private fun PurchaseOrderRow(order: PurchaseOrderSummary, onClick: () -> Unit) {
    val day = order.dueDay()
    val late = day?.isBefore(LocalDate.now()) == true
    FocusListRow(
        title = order.purchId,
        titleIsCode = true,
        state = if (late) RowState.Warn else RowState.Open,
        subtitle = listOfNotNull(
            order.vendorAccount.takeIf { it.isNotBlank() }?.let { "Vendor $it" },
            day?.let { "Delivery date " + Formatters.dayLabel(it.toString()) + if (late) " · overdue" else "" },
        ).joinToString(" · ").ifEmpty { null },
        count = "Receive",
        countColor = FocusColors.navy,
        chevron = true,
        onClick = onClick,
    )
}
