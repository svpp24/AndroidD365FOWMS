package com.d365fo.mobileops.presentation.modules.ordercreate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.d365fo.mobileops.presentation.common.components.SquareTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.presentation.common.components.AppCard
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.components.PromptHeroCard
import com.d365fo.mobileops.presentation.common.components.SquareButton
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar

/** Purchase return - see [ReturnCreateViewModel]. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReturnCreateScreen(onBack: () -> Unit, viewModel: ReturnCreateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val orders by viewModel.orders.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmSend by remember { mutableStateOf(false) }
    val draft = state.draft

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Purchase return") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = WarehouseDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(WarehouseDimens.ItemSpacing),
        ) {
            item { androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(4.dp)) { if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) } }
            // Scan card first, above the saved return and the history: it stays in one place.
            if (draft.vendorAccount.isBlank()) {
                item {
                    PromptHeroCard(prompt = "Scan the original PO", hint = "Or pick the vendor below") {
                        OrderScanField(onType = { typing = true }, onCamera = { showCamera = true }, enabled = !state.busy)
                    }
                }
            }
            // Below the scan card on a step that has one - see the item after it in step 2.
            if (draft.vendorAccount.isBlank()) state.error?.let { message -> item { ErrorBanner(message = message, onDismiss = viewModel::dismissError) } }

            state.submittedId?.let { id ->
                val order = orders.firstOrNull { it.id == id }
                if (order != null) {
                    item { Text("Return saved", style = MaterialTheme.typography.titleMedium) }
                    item { CreatedOrderCard(order) }
                    item { SquareOutlinedButton(onClick = viewModel::dismissResult, modifier = Modifier.fillMaxWidth()) { Text("Start another return") } }
                }
            }

            if (draft.vendorAccount.isBlank()) {
                // -- Step 1: vendor - picked, or taken from the original PO ---------------------
                // Keyed so the field keeps focus when the cards above go away as typing starts.
                item(key = "vendor-title") { Text("Vendor", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                item(key = "vendor-search") { TapToTypeField(state.vendorQuery, viewModel::onVendorQuery, "Search by name or account") }
                // After the search field, which comes first as the thing to act on (the cards
                // still hide while searching).
                if (orders.isNotEmpty() && state.submittedId == null && state.vendorQuery.isBlank()) {
                    item { Text("Made on this device", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                    items(orders.take(3), key = { it.id }) { order -> CreatedOrderCard(order) }
                }
                if (state.vendorCount == 0) {
                    item {
                        Text(
                            if (online) "Getting the vendor list from D365FO..." else "No vendors saved on this device yet - connect once to get the list.",
                            style = MaterialTheme.typography.bodySmall,
                            color = WarehouseStatusColors.text3,
                        )
                    }
                }
                items(state.vendors, key = { it.accountNum }) { vendor ->
                    AppCard(onClick = { viewModel.pickVendor(vendor) }, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(vendor.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(vendor.accountNum, style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.text3)
                        }
                    }
                }
            } else {
                // -- Step 2: reason, RMA and lines ------------------------------------------------
                // Scan card first: it stays in one place however the cards below change (feedback, 10 Oct).
                item {
                    PromptHeroCard(
                        prompt = "Scan item",
                    ) {
                        OrderScanField(onType = { typing = true }, onCamera = { showCamera = true }, enabled = !state.busy)
                    }
                }
                state.error?.let { message -> item { ErrorBanner(message = message, onDismiss = viewModel::dismissError) } }
                item {
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(draft.vendorName.ifBlank { draft.vendorAccount }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Vendor ${draft.vendorAccount} · from ${draft.warehouseId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = WarehouseStatusColors.text3,
                            )
                            TextButton(onClick = { viewModel.editRma(true) }) {
                                Text(if (draft.rmaNumber.isBlank()) "Add the vendor's RMA number" else "RMA ${draft.rmaNumber} - change")
                            }
                            if (draft.lines.isEmpty()) TextButton(onClick = viewModel::changeVendor) { Text("Change vendor") }
                        }
                    }
                }
                if (state.reasons.isNotEmpty()) {
                    item { Text("Return reason", style = MaterialTheme.typography.titleMedium) }
                    item {
                        // A dropdown, like TO creation's warehouses: the list is D365FO's
                        // own setup and can be any length.
                        com.d365fo.mobileops.presentation.common.components.DropdownField(
                            options = state.reasons.map { com.d365fo.mobileops.presentation.common.components.DropdownOption(it.code, it.label) },
                            selectedId = draft.reasonCode,
                            placeholder = "Choose a reason",
                            onPick = viewModel::pickReason,
                        )
                    }
                }
                if (state.actions.isNotEmpty()) {
                    item { Text("Return action", style = MaterialTheme.typography.titleMedium) }
                    item {
                        // A dropdown, like TO creation's warehouses: the list is D365FO's
                        // own setup and can be any length.
                        com.d365fo.mobileops.presentation.common.components.DropdownField(
                            options = state.actions.map { com.d365fo.mobileops.presentation.common.components.DropdownOption(it.code, it.label) },
                            selectedId = draft.actionCode,
                            placeholder = "Choose an action",
                            onPick = viewModel::pickAction,
                        )
                    }
                }
                if (state.reasons.isEmpty() && !online) {
                    item {
                        Text(
                            "Return reasons and actions show once D365FO has been reached.",
                            style = MaterialTheme.typography.bodySmall,
                            color = WarehouseStatusColors.text3,
                        )
                    }
                }
                items(draft.lines, key = { it.id }) { line ->
                    OrderLineCard(line, showPrice = false, currency = "") { viewModel.editLine(line) }
                }
                if (draft.lines.isNotEmpty()) {
                    state.missing?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium, color = WarehouseStatusColors.amber) } }
                    item {
                        SquareButton(
                            onClick = { confirmSend = true },
                            enabled = !state.busy && state.missing == null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
                        ) { Text("Return and post") }
                    }
                }
                item {
                    TextButton(onClick = { confirmDiscard = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Discard this return", color = WarehouseStatusColors.red)
                    }
                }
            }
        }
    }

    state.choosingVariant?.let { item ->
        VariantPickerDialog(item = item, onPick = viewModel::pickVariant, onDismiss = viewModel::cancelPending)
    }
    state.pending?.let { pending ->
        if (pending.choosingStock && pending.stock != null) {
            StockPickerDialog(
                item = pending.item,
                rows = pending.stock,
                onPick = viewModel::pickStock,
                onDismiss = viewModel::cancelPending,
                allowAny = false,
            )
        } else if (state.enteringQty) {
            NumberDialog(
                title = "Quantity",
                subtitle = listOf(pending.item.itemId, pending.variant?.label.orEmpty(), pending.pick?.label.orEmpty(), pending.item.inventUnit)
                    .filter { it.isNotBlank() }.joinToString(" · "),
                initial = if (pending.pick?.serialId?.isNotBlank() == true) 1.0 else null,
                rules = viewModel.quantityRules(pending),
                confirmLabel = "Add line",
                onConfirm = viewModel::addLine,
                onDismiss = viewModel::cancelPending,
            )
        }
    }
    state.editing?.let { line ->
        NumberDialog(
            title = "Quantity",
            subtitle = listOf(line.itemId, line.variant.label, line.locationId.takeIf { it.isNotBlank() }?.let { "Bin $it" }.orEmpty())
                .filter { it.isNotBlank() }.joinToString(" · "),
            initial = line.qty,
            rules = line.available?.let {
                com.d365fo.mobileops.domain.order.QuantityRules(
                    available = it,
                    availableWhere = line.locationId.takeIf { b -> b.isNotBlank() }?.let { b -> "in bin $b" } ?: "to return",
                )
            },
            confirmLabel = "Save",
            onConfirm = { viewModel.changeQuantity(line, it) },
            onDismiss = { viewModel.editLine(null) },
            onRemove = { viewModel.removeLine(line) },
        )
    }
    if (state.editingRma) {
        var value by remember { mutableStateOf(draft.rmaNumber) }
        AlertDialog(
            onDismissRequest = { viewModel.editRma(false) },
            title = { Text("Vendor's RMA number") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The number the vendor gave for this return. Leave it empty and the return order's own number is used.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(value = value, onValueChange = { value = it.take(10) }, singleLine = true, label = { Text("RMA number (up to 10)") })
                }
            },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = { viewModel.editRma(false) }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
                    confirm = { com.d365fo.mobileops.presentation.common.components.ConfirmButton(onClick = { viewModel.setRma(value) }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Save") } },
                )
            },
        )
    }
    if (typing) {
        CodeEntryDialog(
            title = if (draft.vendorAccount.isBlank()) "Original PO" else "Item",
            label = if (draft.vendorAccount.isBlank()) "Purchase order number" else "Item number or barcode",
            onSubmit = { typing = false; viewModel.onCode(it) },
            onDismiss = { typing = false },
        )
    }
    if (confirmSend) {
        AlertDialog(
            onDismissRequest = { confirmSend = false },
            title = { Text("Return and post?") },
            text = {
                Text(
                    "${linesText(draft.lines.size)} ${if (draft.lines.size == 1) "goes" else "go"} back to ${draft.vendorName.ifBlank { draft.vendorAccount }}. D365FO creates the return order " +
                        "and posts its packing slip - the stock leaves ${draft.warehouseId}." +
                        if (online) "" else " You are offline: it is posted as soon as D365FO can be reached.",
                )
            },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = { confirmSend = false }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Not yet") } },
                    confirm = { com.d365fo.mobileops.presentation.common.components.ConfirmButton(onClick = { confirmSend = false; viewModel.create() }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Return and post") } },
                )
            },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this return?") },
            text = { Text("${linesText(draft.lines.size)} will be removed from this device. Nothing was sent to D365FO.") },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = { confirmDiscard = false }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Keep") } },
                    confirm = { com.d365fo.mobileops.presentation.common.components.CancelButton(onClick = { confirmDiscard = false; viewModel.discard() }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Discard") } },
                )
            },
        )
    }
    if (showCamera) {
        CameraScannerSheet(onDecoded = { viewModel.onCameraDecoded(it); showCamera = false }, onDismiss = { showCamera = false })
    }
}
