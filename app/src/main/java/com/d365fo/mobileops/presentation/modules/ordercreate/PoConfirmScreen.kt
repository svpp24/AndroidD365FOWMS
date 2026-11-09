package com.d365fo.mobileops.presentation.modules.ordercreate

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.d365fo.mobileops.presentation.common.components.SquareTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.data.local.entity.CreatedOrderEntity
import com.d365fo.mobileops.data.local.entity.CreatedOrderStatus
import com.d365fo.mobileops.data.repository.PurchaseOrderLine
import com.d365fo.mobileops.data.repository.PurchaseOrderSummary
import com.d365fo.mobileops.domain.order.amountText
import com.d365fo.mobileops.domain.order.qtyText
import com.d365fo.mobileops.presentation.common.components.AppCard
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.components.PromptHeroCard
import com.d365fo.mobileops.presentation.common.components.SquareButton
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton
import com.d365fo.mobileops.presentation.common.components.StatusChip
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar

/** PO confirmation - see [PoConfirmViewModel]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoConfirmScreen(onBack: () -> Unit, viewModel: PoConfirmViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val confirmations by viewModel.confirmations.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    val detail = state.detail

    BackHandler(enabled = detail != null) { viewModel.closeDetail() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.summary?.purchId ?: "PO confirmation") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = { if (detail != null) viewModel.closeDetail() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (detail == null && online) {
                        IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = WarehouseDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(WarehouseDimens.ItemSpacing),
        ) {
            item { if (state.loading || state.opening) LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            state.error?.let { message -> item { ErrorBanner(message = message, onDismiss = viewModel::dismissError) } }

            if (detail != null) {
                // -- One order: header, lines, confirm ------------------------------------------
                val summary = detail.summary
                val local = viewModel.pendingOrDone(summary.purchId, confirmations)
                item { OrderHeaderCard(summary) }
                items(detail.lines, key = { it.lineNumber }) { PurchaseLineCard(it, summary.currencyCode) }
                item {
                    Text(
                        "${linesText(detail.lines.size)} · total ${amountText(detail.lines.sumOf { it.lineAmount })} ${summary.currencyCode}".trim(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                item {
                    when {
                        local != null -> CreatedOrderCard(local)
                        summary.confirmed -> Text(
                            "Already confirmed" + (summary.confirmationNumber.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        !summary.confirmable -> Text(
                            "${summary.approvalLabel} - must be approved first.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = WarehouseStatusColors.red,
                        )
                        else -> SquareButton(
                            onClick = viewModel::askConfirm,
                            modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
                        ) { Text("Confirm purchase order") }
                    }
                }
            } else {
                // -- The list -------------------------------------------------------------------
                state.submittedId?.let { id ->
                    confirmations.firstOrNull { it.id == id }?.let { order ->
                        item { Text("Confirmation saved", style = MaterialTheme.typography.titleMedium) }
                        item { CreatedOrderCard(order) }
                        item { SquareOutlinedButton(onClick = viewModel::dismissResult, modifier = Modifier.fillMaxWidth()) { Text("OK") } }
                    }
                }
                item {
                    PromptHeroCard(prompt = "Scan a PO") {
                        OrderScanField(onType = { typing = true }, onCamera = { showCamera = true }, enabled = !state.opening)
                    }
                }
                state.listError?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium, color = WarehouseStatusColors.text3) } }
                item {
                    Text(
                        "To confirm at ${state.warehouseId}" + if (state.orders.isNotEmpty()) " · ${state.orders.size}" else "",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (state.orders.isEmpty() && !state.loading && state.listError == null) {
                    item { Text("Nothing waiting for confirmation.", style = MaterialTheme.typography.bodyMedium, color = WarehouseStatusColors.text3) }
                }
                items(state.orders, key = { it.purchId }) { order ->
                    OrderSummaryCard(order, viewModel.pendingOrDone(order.purchId, confirmations)) { viewModel.open(order.purchId) }
                }
                val done = confirmations.filter { it.id != state.submittedId }
                if (done.isNotEmpty()) {
                    item { Text("Confirmed on this device", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                    items(done.take(10), key = { it.id }) { CreatedOrderCard(it) }
                }
            }
        }
    }

    if (state.askConfirm && detail != null) {
        val s = detail.summary
        AlertDialog(
            onDismissRequest = viewModel::cancelConfirm,
            title = { Text("Confirm ${s.purchId}?") },
            text = {
                Text(
                    "${s.vendorAccount} ${s.vendorName} · ${linesText(detail.lines.size)} · " +
                        "${amountText(detail.lines.sumOf { it.lineAmount })} ${s.currencyCode}.\n\n" +
                        if (online) "Confirm now?" else "Offline - confirmed when back.",
                )
            },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = viewModel::cancelConfirm, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
                    confirm = { com.d365fo.mobileops.presentation.common.components.ConfirmButton(onClick = viewModel::confirm, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Confirm") } },
                )
            },
        )
    }
    if (typing) {
        CodeEntryDialog(
            title = "Purchase order",
            label = "PO number",
            onSubmit = { typing = false; viewModel.open(it) },
            onDismiss = { typing = false },
        )
    }
    if (showCamera) {
        CameraScannerSheet(onDecoded = { viewModel.onCameraDecoded(it); showCamera = false }, onDismiss = { showCamera = false })
    }
}

@Composable
private fun OrderSummaryCard(order: PurchaseOrderSummary, local: CreatedOrderEntity?, onClick: () -> Unit) {
    AppCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(order.purchId, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                when (local?.status) {
                    CreatedOrderStatus.WAITING -> StatusChip("Waiting", WarehouseStatusColors.amber, WarehouseStatusColors.amberDim)
                    CreatedOrderStatus.CREATED -> StatusChip("Confirmed", WarehouseStatusColors.green, WarehouseStatusColors.greenDim)
                    else -> Unit
                }
            }
            Text("${order.vendorAccount} ${order.vendorName}".trim(), style = MaterialTheme.typography.bodyMedium)
            Text(
                listOfNotNull(
                    linesText(order.lineCount),
                    "${amountText(order.total)} ${order.currencyCode}".trim(),
                    order.deliveryDate.takeIf { it.isNotBlank() }?.let { "delivery $it" },
                    order.createdBy.takeIf { it.isNotBlank() }?.let { "by $it" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = WarehouseStatusColors.text3,
            )
        }
    }
}

@Composable
private fun OrderHeaderCard(s: PurchaseOrderSummary) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${s.vendorAccount} ${s.vendorName}".trim(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                when {
                    s.confirmed -> StatusChip("Confirmed", WarehouseStatusColors.green, WarehouseStatusColors.greenDim)
                    s.confirmable -> StatusChip("Approved", WarehouseStatusColors.amber, WarehouseStatusColors.amberDim)
                    else -> StatusChip(s.approvalLabel, WarehouseStatusColors.red, WarehouseStatusColors.redDim)
                }
            }
            Text(
                listOfNotNull(
                    "to ${s.warehouseId}",
                    s.deliveryDate.takeIf { it.isNotBlank() }?.let { "delivery $it" },
                    s.createdBy.takeIf { it.isNotBlank() }?.let { "by $it" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = WarehouseStatusColors.text3,
            )
            s.confirmationNumber.takeIf { it.isNotBlank() }?.let {
                Text("Confirmation $it", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PurchaseLineCard(line: PurchaseOrderLine, currency: String) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(line.itemId, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(line.itemName, style = MaterialTheme.typography.bodyMedium)
                    line.variant.label.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.text3)
                    }
                }
                Text(com.d365fo.mobileops.util.Formatters.qtyUnit(java.math.BigDecimal.valueOf(line.qty), line.unitId), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                buildString {
                    append("${amountText(line.price)} $currency".trim())
                    if (line.discPct > 0) append(" · −${qtyText(line.discPct)}%")
                    append(" · line ${amountText(line.lineAmount)}")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
