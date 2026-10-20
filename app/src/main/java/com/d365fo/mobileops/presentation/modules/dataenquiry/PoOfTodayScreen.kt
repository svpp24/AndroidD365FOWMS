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
 * Purchase orders requested for delivery today. Loads itself on entry - there is no scan field
 * here, the same as the standard WMS app's own worklist-style screens.
 *
 * The loading state is a spinner dead centre of the page, not a bar pinned to the top: on
 * first entry there is nothing else on screen yet to share space with, so this is the one
 * screen in this package where a full-page centred spinner is the plain right answer rather
 * than a top progress bar layered over a field the operator is about to type into.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoOfTodayScreen(
    onBack: () -> Unit,
    /** Opens PO receiving; the order was handed over through [PoOfTodayViewModel.startReceiving]. */
    onReceive: () -> Unit = {},
    viewModel: PoOfTodayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PO's of today") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.busy && state.orders.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                state.error != null -> {
                    ErrorBanner(
                        message = state.error.orEmpty(),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(WarehouseDimens.ScreenPadding),
                    )
                }

                state.orders.isEmpty() -> {
                    Text(
                        text = state.warehouseId
                            ?.let { "No purchase orders due today in $it." }
                            ?: "No purchase orders due today.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(WarehouseDimens.ScreenPadding),
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = WarehouseDimens.ScreenPadding),
                        contentPadding = PaddingValues(vertical = WarehouseDimens.ItemSpacing),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.orders, key = { it.purchId }) { order ->
                            PurchaseOrderRow(order, onReceive = {
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

@Composable
private fun PurchaseOrderRow(order: PurchaseOrderSummary, onReceive: () -> Unit) {
    val (statusText, statusColor, statusDim) = when (order.status) {
        OrderStatus.NONE, OrderStatus.BACKORDER -> Triple(
            "Open", WarehouseStatusColors.accent, WarehouseStatusColors.accentDim,
        )
        OrderStatus.RECEIVED -> Triple(
            "Received", WarehouseStatusColors.green, WarehouseStatusColors.greenDim,
        )
        OrderStatus.INVOICED -> Triple(
            "Invoiced", WarehouseStatusColors.green, WarehouseStatusColors.greenDim,
        )
        OrderStatus.CANCELED -> Triple(
            "Canceled", WarehouseStatusColors.border, WarehouseStatusColors.surface2,
        )
    }

    // Open orders start receiving on tap; received/invoiced/cancelled ones are information only.
    val receivable = order.status.isOpen
    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = if (receivable) 4.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = order.purchId,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StatusChip(text = statusText, color = statusColor, dimColor = statusDim)
                }
                Text(
                    text = listOfNotNull(
                        "Vendor ${order.vendorAccount}".takeIf { order.vendorAccount.isNotBlank() },
                        order.warehouseId?.let { "Whs $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                order.deliveryDate?.let {
                    Text(
                        text = "Requested ${Formatters.shortDate(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarehouseStatusColors.text3,
                    )
                }
            }
            if (receivable) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Receive",
                    tint = WarehouseStatusColors.text3,
                )
            }
        }
    }
    if (receivable) {
        Card(onClick = onReceive, modifier = Modifier.fillMaxWidth()) { body() }
    } else {
        Card(modifier = Modifier.fillMaxWidth()) { body() }
    }
}
