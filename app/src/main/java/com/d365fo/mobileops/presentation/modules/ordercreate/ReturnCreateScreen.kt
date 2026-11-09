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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
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
                title = { Text("Return to vendor") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            // contentPadding, not padding: the step tabs run edge to edge (Harbour).
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = WarehouseDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(WarehouseDimens.ItemSpacing),
        ) {
            item(key = "tabs") {
                Column {
                    com.d365fo.mobileops.presentation.common.components.StepTrail(
                        names = listOf("Vendor", "Reason", "Items", "Post"),
                        current = when {
                            draft.vendorAccount.isBlank() -> 0
                            draft.lines.isEmpty() && (draft.reasonCode.isBlank() || draft.actionCode.isBlank()) -> 1
                            else -> 2
                        },
                    )
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(4.dp)) { if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                }
            }
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
                    item { com.d365fo.mobileops.presentation.common.components.ListHeader(Icons.Default.Check, "Return saved") }
                    item { CreatedOrderCard(order) }
                    item { SquareOutlinedButton(onClick = viewModel::dismissResult, modifier = Modifier.fillMaxWidth()) { Text("Start another return") } }
                }
            }

            if (draft.vendorAccount.isBlank()) {
                // -- Step 1: vendor - picked, or taken from the original PO ---------------------
                // Keyed so the field keeps focus when the cards above go away as typing starts.
                item(key = "vendor-search") {
                    com.d365fo.mobileops.presentation.common.components.PromptHeroCard(prompt = "Choose vendor") {
                        TapToTypeField(state.vendorQuery, viewModel::onVendorQuery, "Vendor name or account")
                    }
                }
                if (orders.isNotEmpty() && state.submittedId == null && state.vendorQuery.isBlank()) {
                    item { com.d365fo.mobileops.presentation.common.components.ListHeader(Icons.Default.Description, "Your returns", orders.size.coerceAtMost(3), trailing = "Newest") }
                    items(orders.take(3), key = { it.id }) { order -> CreatedOrderCard(order) }
                }
                item {
                    com.d365fo.mobileops.presentation.common.components.ListHeader(
                        icon = Icons.Default.Store,
                        title = "Vendors",
                        count = state.vendorCount.takeIf { it > 0 },
                        trailing = if (state.vendorCount == 0) (if (online) "Getting vendors..." else "No vendors yet - connect once.") else null,
                    )
                }
                items(state.vendors, key = { it.accountNum }) { vendor ->
                    com.d365fo.mobileops.presentation.common.components.ListCard {
                        com.d365fo.mobileops.presentation.common.components.LineRow(
                            title = vendor.name,
                            lines = listOf(vendor.accountNum),
                            lead = {
                                com.d365fo.mobileops.presentation.common.components.LeadDot(
                                    com.d365fo.mobileops.presentation.common.components.Lead.Accent,
                                    text = vendor.accountNum.takeLast(3),
                                )
                            },
                            trailing = { com.d365fo.mobileops.presentation.common.components.RowChevron() },
                            onClick = { viewModel.pickVendor(vendor) },
                        )
                    }
                }
            } else {
                // -- Step 2: reason, RMA and lines ------------------------------------------------
                // Scan card first: it stays in one place however the cards below change (feedback, 10 Oct).
                item {
                    PromptHeroCard(
                        prompt = "Scan item",
                        pills = listOfNotNull(
                            Icons.Default.Store to draft.vendorName.ifBlank { draft.vendorAccount },
                            Icons.Default.Warehouse to "From ${draft.warehouseId}",
                            draft.rmaNumber.takeIf { it.isNotBlank() }?.let { Icons.Default.Description to "RMA $it" },
                        ),
                    ) {
                        OrderScanField(onType = { typing = true }, onCamera = { showCamera = true }, enabled = !state.busy)
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { viewModel.editRma(true) }) {
                                Text(if (draft.rmaNumber.isBlank()) "Add vendor RMA" else "Change RMA")
                            }
                            if (draft.lines.isEmpty()) TextButton(onClick = viewModel::changeVendor) { Text("Change vendor") }
                        }
                    }
                }
                state.error?.let { message -> item { ErrorBanner(message = message, onDismiss = viewModel::dismissError) } }
                if (state.reasons.isNotEmpty() || state.actions.isNotEmpty()) {
                    item {
                        // Reason and action in one white card (Harbour) - dropdowns, as D365FO's
                        // own setup can be any length.
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(androidx.compose.ui.graphics.Color.White, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .padding(12.dp),
                        ) {
                            if (state.reasons.isNotEmpty()) {
                                Text("Return reason", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = WarehouseStatusColors.text3)
                                com.d365fo.mobileops.presentation.common.components.DropdownField(
                                    options = state.reasons.map { com.d365fo.mobileops.presentation.common.components.DropdownOption(it.code, it.label) },
                                    selectedId = draft.reasonCode,
                                    placeholder = "Choose a reason",
                                    onPick = viewModel::pickReason,
                                )
                            }
                            if (state.actions.isNotEmpty()) {
                                Text("Return action", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = WarehouseStatusColors.text3, modifier = Modifier.padding(top = 4.dp))
                                com.d365fo.mobileops.presentation.common.components.DropdownField(
                                    options = state.actions.map { com.d365fo.mobileops.presentation.common.components.DropdownOption(it.code, it.label) },
                                    selectedId = draft.actionCode,
                                    placeholder = "Choose an action",
                                    onPick = viewModel::pickAction,
                                )
                            }
                        }
                    }
                }
                if (state.reasons.isEmpty() && !online) {
                    item {
                        Text(
                            "Reasons load when online.",
                            style = MaterialTheme.typography.bodySmall,
                            color = WarehouseStatusColors.text3,
                        )
                    }
                }
                if (draft.lines.isNotEmpty()) {
                    item {
                        com.d365fo.mobileops.presentation.common.components.ListHeader(
                            icon = Icons.AutoMirrored.Filled.ListAlt,
                            title = "Return lines",
                            count = draft.lines.size,
                        )
                    }
                }
                items(draft.lines, key = { it.id }) { line ->
                    OrderLineCard(line, showPrice = false, currency = "") { viewModel.editLine(line) }
                }
                if (draft.lines.isNotEmpty()) {
                    state.missing?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium, color = WarehouseStatusColors.amber) } }
                }
                item { androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp)) }
            }
        }
        // The dock (Harbour): Discard beside a hold to return and post - posting can't be undone,
        // so a hold rather than a tap and a question.
        if (draft.vendorAccount.isNotBlank()) {
            com.d365fo.mobileops.presentation.common.components.FlowDock {
                com.d365fo.mobileops.presentation.common.components.DockOutlinedButton(
                    onClick = { confirmDiscard = true },
                    text = "Discard",
                    color = WarehouseStatusColors.red,
                    modifier = Modifier.weight(0.38f),
                )
                com.d365fo.mobileops.presentation.common.components.HoldButton(
                    label = "Hold to post",
                    onConfirm = viewModel::create,
                    enabled = !state.busy && draft.lines.isNotEmpty() && state.missing == null,
                    modifier = Modifier.weight(0.62f),
                )
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
            subtitle = listOf(line.itemId, line.variant.label, line.locationId.takeIf { it.isNotBlank() }?.let { "Location $it" }.orEmpty())
                .filter { it.isNotBlank() }.joinToString(" · "),
            initial = line.qty,
            rules = line.available?.let {
                com.d365fo.mobileops.domain.order.QuantityRules(
                    available = it,
                    availableWhere = line.locationId.takeIf { b -> b.isNotBlank() }?.let { b -> "at location $b" } ?: "to return",
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
                    Text("Vendor's return number (optional).", style = MaterialTheme.typography.bodyMedium)
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
                    "${linesText(draft.lines.size)} back to ${draft.vendorName.ifBlank { draft.vendorAccount }}. Stock leaves ${draft.warehouseId}." +
                        if (online) "" else " Offline - posted when back.",
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
            text = { Text("${linesText(draft.lines.size)} removed. Nothing was sent.") },
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
