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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
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
                title = { Text("Create transfer") },
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
                        names = listOf("Route", "Items", "Create"),
                        current = if (!state.routeChosen) 0 else 1,
                    )
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(4.dp)) { if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                }
            }
            // Below the scan card on a step that has one - see the item after it in step 2.
            if (!state.routeChosen) state.error?.let { message -> item { ErrorBanner(message = message, onDismiss = viewModel::dismissError) } }

            state.submittedId?.let { id ->
                val order = orders.firstOrNull { it.id == id }
                if (order != null) {
                    item { com.d365fo.mobileops.presentation.common.components.ListHeader(Icons.Default.Check, "Order saved") }
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
                            "You need two warehouses - ask a supervisor.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else {
                    // Dropdowns, not a chip per warehouse: a worker assigned many warehouses got
                    // two walls of chips before the first item (feedback, 10 Oct). From and To in
                    // one white card with an arrow between (Harbour).
                    item {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(androidx.compose.ui.graphics.Color.White, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .padding(12.dp),
                        ) {
                            Text("From", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = WarehouseStatusColors.text3)
                            WarehouseDropdown(
                                options = state.warehouses,
                                selectedId = draft.fromWarehouseId,
                                placeholder = "Choose the warehouse it ships from",
                                onPick = viewModel::pickFrom,
                            )
                            Icon(
                                Icons.Default.ArrowDownward,
                                contentDescription = null,
                                tint = WarehouseStatusColors.text3,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            )
                            Text("To", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = WarehouseStatusColors.text3)
                            WarehouseDropdown(
                                options = state.warehouses.filter { it.warehouseId != draft.fromWarehouseId },
                                selectedId = draft.toWarehouseId,
                                placeholder = "Choose the warehouse it goes to",
                                onPick = viewModel::pickTo,
                            )
                        }
                    }
                }
                // Below the route - what the worker acts on comes first; then the latest three,
                // the same as PO creation and Purchase return.
                if (orders.isNotEmpty() && state.submittedId == null) {
                    item { com.d365fo.mobileops.presentation.common.components.ListHeader(Icons.Default.SwapHoriz, "Your transfers", orders.size.coerceAtMost(3), trailing = "Newest") }
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
                        pills = listOf(
                            Icons.Default.Route to "${draft.fromWarehouseId} to ${draft.toWarehouseId}",
                            Icons.Default.CalendarMonth to "Ships ${draft.shipDate}",
                        ),
                    ) {
                        OrderScanField(onType = { typing = true }, onCamera = { showCamera = true }, enabled = !state.busy)
                        if (draft.lines.isEmpty()) TextButton(onClick = viewModel::changeRoute) { Text("Change warehouses") }
                    }
                }
                state.error?.let { message -> item { ErrorBanner(message = message, onDismiss = viewModel::dismissError) } }
                if (draft.lines.isNotEmpty()) {
                    item {
                        com.d365fo.mobileops.presentation.common.components.ListHeader(
                            icon = Icons.AutoMirrored.Filled.ListAlt,
                            title = "Order lines",
                            count = draft.lines.size,
                        )
                    }
                }
                items(draft.lines, key = { it.id }) { line ->
                    OrderLineCard(line, showPrice = false, currency = "") { viewModel.editLine(line) }
                }
                item { androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp)) }
            }
        }
        // The dock (Harbour): Next on the route step; Discard and a hold to create on the items.
        if (!state.routeChosen) {
            if (state.warehouses.size >= 2) {
                com.d365fo.mobileops.presentation.common.components.FlowDock {
                    com.d365fo.mobileops.presentation.common.components.GradientButton(
                        onClick = viewModel::confirmRoute,
                        enabled = draft.fromWarehouseId.isNotBlank() && draft.toWarehouseId.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Next")
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                }
            }
        } else if (draft.lines.isNotEmpty()) {
            com.d365fo.mobileops.presentation.common.components.FlowDock {
                com.d365fo.mobileops.presentation.common.components.DockOutlinedButton(
                    onClick = { confirmDiscard = true },
                    text = "Discard",
                    color = WarehouseStatusColors.red,
                    modifier = Modifier.weight(0.38f),
                )
                com.d365fo.mobileops.presentation.common.components.HoldButton(
                    label = "Hold to create",
                    onConfirm = viewModel::create,
                    enabled = !state.busy,
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
