package com.d365fo.mobileops.presentation.modules.dataenquiry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.domain.model.OrderStatus
import com.d365fo.mobileops.domain.repository.PurchaseOrderSummary
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.components.StatusChip
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.util.Formatters

/**
 * Every open purchase order of the store, soonest delivery first. Loads itself on entry - there
 * is no scan field here, the same as the standard WMS app's own worklist-style screens.
 *
 * The loading state is a spinner dead centre of the page, not a bar pinned to the top: on
 * first entry there is nothing else on screen yet to share space with, so this is the one
 * screen in this package where a full-page centred spinner is the plain right answer rather
 * than a top progress bar layered over a field the operator is about to type into.
 */
@Composable
fun PoOfTodayScreen(
    onBack: () -> Unit,
    /** Opens PO receiving; the order was handed over through [PoOfTodayViewModel.startReceiving]. */
    onReceive: () -> Unit = {},
    viewModel: PoOfTodayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Focus list (brief section 9): one row per PO, its status as a word; tapping an open one
    // starts receiving it.
    com.d365fo.mobileops.presentation.common.focus.FocusScreen(
        title = "Purchase orders",
        onBack = onBack,
        counter = state.orders.size.takeIf { it > 0 }?.toString(),
        band = when {
            state.error != null -> com.d365fo.mobileops.presentation.modules.stepflow.bandOf(
                com.d365fo.mobileops.presentation.common.focus.BandKind.Bad, state.error.orEmpty(),
            )
            !state.busy && state.orders.isEmpty() -> com.d365fo.mobileops.presentation.common.focus.BandState(
                com.d365fo.mobileops.presentation.common.focus.BandKind.Info,
                "No open purchase orders",
                state.warehouseId,
            )
            else -> null
        },
    ) {
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(state.orders, key = { it.purchId }) { order ->
                PurchaseOrderRow(order, onReceive = {
                    viewModel.startReceiving(order.purchId)
                    onReceive()
                })
            }
        }
    }
}

@Composable
private fun PurchaseOrderRow(order: PurchaseOrderSummary, onReceive: () -> Unit) {
    val (statusText, done) = when (order.status) {
        OrderStatus.NONE, OrderStatus.BACKORDER -> "Open" to false
        OrderStatus.RECEIVED -> "Received" to true
        OrderStatus.INVOICED -> "Invoiced" to true
        OrderStatus.CANCELED -> "Canceled" to true
    }
    // Open orders start receiving on tap; received/invoiced/cancelled ones are information only.
    val receivable = order.status.isOpen
    com.d365fo.mobileops.presentation.common.focus.FocusListRow(
        title = order.purchId,
        titleIsCode = true,
        state = if (done) com.d365fo.mobileops.presentation.common.focus.RowState.Done else com.d365fo.mobileops.presentation.common.focus.RowState.Open,
        subtitle = listOfNotNull(
            "Vendor ${order.vendorAccount}".takeIf { order.vendorAccount.isNotBlank() },
            order.warehouseId?.let { "Whs $it" },
            order.deliveryDate?.let { "Delivery ${Formatters.shortDate(it)}" },
        ).joinToString(" · ").ifEmpty { null },
        count = statusText,
        chevron = receivable,
        onClick = if (receivable) onReceive else null,
    )
}
