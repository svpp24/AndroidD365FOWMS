package com.d365fo.mobileops.presentation.modules.ordercreate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhotoCamera
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.data.local.entity.CreatedOrderStatus
import com.d365fo.mobileops.presentation.common.components.AppCard
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.components.PromptHeroCard
import com.d365fo.mobileops.presentation.common.components.SquareButton
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar

/** TO creation - see [ToCreateViewModel]. [onShip] opens Transfer - Ship for a created order. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ToCreateScreen(onBack: () -> Unit, onShip: () -> Unit, viewModel: ToCreateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val orders by viewModel.orders.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val draft = state.draft

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TO creation") },
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
            // Below the scan card on a step that has one - see the item after it in step 2.
            if (!state.routeChosen) state.error?.let { message -> item { ErrorBanner(message = message, onDismiss = viewModel::dismissError) } }

            state.submittedId?.let { id ->
                val order = orders.firstOrNull { it.id == id }
                if (order != null) {
                    item { Text("Order saved", style = MaterialTheme.typography.titleMedium) }
                    item { CreatedOrderCard(order) }
                    if (order.status == CreatedOrderStatus.CREATED) {
                        item { SquareButton(onClick = { viewModel.ship(order.documentNumber.orEmpty()); onShip() }, modifier = Modifier.fillMaxWidth()) { Text("Ship ${order.documentNumber} now") } }
                    }
                    item { SquareOutlinedButton(onClick = viewModel::dismissResult, modifier = Modifier.fillMaxWidth()) { Text("Start another order") } }
                }
            }

            if (!state.routeChosen) {
                // -- Step 1: From and To --------------------------------------------------------
                if (state.warehouses.size < 2) {
                    item {
                        Text(
                            "A transfer needs two warehouses assigned to you. Ask a supervisor to add another in Mobile device users.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else {
                    // Dropdowns, not a chip per warehouse: a worker assigned many warehouses got
                    // two walls of chips before the first item (feedback, 10 Oct).
                    item { Text("From", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                    item {
                        WarehouseDropdown(
                            options = state.warehouses,
                            selectedId = draft.fromWarehouseId,
                            placeholder = "Choose the warehouse it ships from",
                            onPick = viewModel::pickFrom,
                        )
                    }
                    item { Text("To", style = MaterialTheme.typography.titleMedium) }
                    item {
                        WarehouseDropdown(
                            options = state.warehouses.filter { it.warehouseId != draft.fromWarehouseId },
                            selectedId = draft.toWarehouseId,
                            placeholder = "Choose the warehouse it goes to",
                            onPick = viewModel::pickTo,
                        )
                    }
                    item {
                        SquareButton(
                            onClick = viewModel::confirmRoute,
                            enabled = draft.fromWarehouseId.isNotBlank() && draft.toWarehouseId.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
                        ) { Text("Continue to items") }
                    }
                }
                // Below the route and Continue - what the worker acts on comes first; then the
                // latest three, the same as PO creation and Purchase return.
                if (orders.isNotEmpty() && state.submittedId == null) {
                    item { Text("Made on this device", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                    items(orders.take(3), key = { it.id }) { order ->
                        val shippable = state.shippableIds?.let { ids -> order.documentNumber?.uppercase() in ids } ?: true
                        // No longer to ship from here: it shipped - the card still said "Created".
                        val shipped = state.shippableIds != null && !shippable && order.status == com.d365fo.mobileops.data.local.entity.CreatedOrderStatus.CREATED
                        CreatedOrderCard(order, actionLabel = "Ship it now".takeIf { shippable }, laterStatus = "Shipped".takeIf { shipped }, onAction = {
                            viewModel.ship(order.documentNumber.orEmpty()); onShip()
                        })
                    }
                }
            } else {
                // -- Step 2: lines --------------------------------------------------------------
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
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("${draft.fromWarehouseId} → ${draft.toWarehouseId}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Ships ${draft.shipDate}", style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.text3)
                            if (draft.lines.isEmpty()) TextButton(onClick = viewModel::changeRoute) { Text("Change warehouses") }
                        }
                    }
                }
                items(draft.lines, key = { it.id }) { line ->
                    OrderLineCard(line, showPrice = false, currency = "") { viewModel.editLine(line) }
                }
                if (draft.lines.isNotEmpty()) {
                    item {
                        SquareButton(onClick = viewModel::create, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget)) {
                            Text("Create transfer order")
                        }
                    }
                    item {
                        TextButton(onClick = { confirmDiscard = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Discard this order", color = WarehouseStatusColors.red)
                        }
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
            StockPickerDialog(item = pending.item, rows = pending.stock, onPick = viewModel::pickStock, onDismiss = viewModel::cancelPending)
        } else if (state.enteringQty) {
            NumberDialog(
                title = "Quantity",
                subtitle = listOf(
                    pending.item.itemId,
                    pending.variant?.label.orEmpty(),
                    pending.pick?.batchId?.takeIf { it.isNotBlank() }?.let { "Batch $it" }.orEmpty(),
                    pending.pick?.serialId?.takeIf { it.isNotBlank() }?.let { "Serial $it" }.orEmpty(),
                    pending.item.inventUnit,
                ).filter { it.isNotBlank() }.joinToString(" · "),
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
            subtitle = listOf(line.itemId, line.variant.label).filter { it.isNotBlank() }.joinToString(" · "),
            initial = line.qty,
            // The same rule as adding the line: an item allowed negative stock is not capped by
            // what is on hand (adding 50 with 3 there was allowed, editing it then refused).
            rules = line.available?.takeUnless { line.allowsNegative }
                ?.let { com.d365fo.mobileops.domain.order.QuantityRules(available = it) },
            confirmLabel = "Save",
            onConfirm = { viewModel.changeQuantity(line, it) },
            onDismiss = { viewModel.editLine(null) },
            onRemove = { viewModel.removeLine(line) },
        )
    }
    if (typing) {
        CodeEntryDialog(
            title = "Item",
            label = "Item number or barcode",
            onSubmit = { typing = false; viewModel.onCode(it) },
            onDismiss = { typing = false },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this order?") },
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

/** One warehouse out of the worker's assigned ones - see [DropdownField]. */
@Composable
private fun WarehouseDropdown(
    options: List<com.d365fo.mobileops.data.repository.AssignedWarehouse>,
    selectedId: String,
    placeholder: String,
    onPick: (String) -> Unit,
) = com.d365fo.mobileops.presentation.common.components.DropdownField(
    options = options.map { com.d365fo.mobileops.presentation.common.components.DropdownOption(it.warehouseId, it.label) },
    selectedId = selectedId,
    placeholder = placeholder,
    onPick = onPick,
)
