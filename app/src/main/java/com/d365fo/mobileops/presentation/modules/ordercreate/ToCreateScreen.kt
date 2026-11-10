package com.d365fo.mobileops.presentation.modules.ordercreate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.data.local.entity.CreatedOrderStatus
import com.d365fo.mobileops.presentation.common.focus.BandKind
import com.d365fo.mobileops.presentation.common.focus.BandState
import com.d365fo.mobileops.presentation.common.focus.FocusButton
import com.d365fo.mobileops.presentation.common.focus.FocusCenter
import com.d365fo.mobileops.presentation.common.focus.FocusScreen
import com.d365fo.mobileops.presentation.common.focus.HoldToConfirmButton
import com.d365fo.mobileops.presentation.common.focus.ScanPillField
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusType
import com.d365fo.mobileops.presentation.modules.stepflow.bandOf

/**
 * TO creation - see [ToCreateViewModel] - in the Focus design, on the existing steps: the route
 * (From and To, pre-filled where there is only one other warehouse), then items (stock and
 * quantity asked as before), then hold to create. [onShip] opens Transfer - Ship for a created order.
 */
@Composable
fun ToCreateScreen(onBack: () -> Unit, onShip: () -> Unit, viewModel: ToCreateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val orders by viewModel.orders.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    var showLines by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val draft = state.draft
    val lastAdded = rememberLastAdded(draft.lines)
    val route = "${draft.fromWarehouseId} → ${draft.toWarehouseId}"

    ToDialogs(state, viewModel)
    if (confirmDiscard) {
        DiscardDialog(what = "order", lines = draft.lines.size, onKeep = { confirmDiscard = false }, onDiscard = { confirmDiscard = false; viewModel.discard() })
    }
    if (showCamera) {
        CameraScannerSheet(onDecoded = { viewModel.onCameraDecoded(it); showCamera = false }, onDismiss = { showCamera = false })
    }

    if (showLines && state.routeChosen && draft.lines.isNotEmpty()) {
        OrderLinesScreen(
            title = "Transfer lines",
            lines = draft.lines,
            showPrice = false,
            currency = "",
            footer = orderSize(draft.lines),
            onLine = { viewModel.editLine(it) },
            onClose = { showLines = false },
        )
        return
    }

    if (!state.routeChosen) {
        // -- Step 1: From and To, the order just saved and the latest made here --------------
        val submitted = state.submittedId?.let { id -> orders.firstOrNull { it.id == id } }
        val tooFew = state.warehouses.size < 2
        FocusScreen(
            title = "Create transfer",
            onBack = onBack,
            band = state.error?.let { bandOf(BandKind.Bad, it) }
                ?: submitted?.let { BandState(BandKind.Ok, "Transfer saved", it.documentNumber ?: it.title) }
                ?: BandState(BandKind.Warn, "You need two warehouses", "ask a supervisor").takeIf { tooFew },
            actions = when {
                submitted != null -> { {
                    FocusButton("Start another", onClick = viewModel::dismissResult, modifier = Modifier.weight(1f))
                    if (submitted.status == CreatedOrderStatus.CREATED) {
                        FocusButton(
                            "Ship it now",
                            onClick = { viewModel.ship(submitted.documentNumber.orEmpty()); onShip() },
                            primary = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } }
                tooFew -> null
                else -> { {
                    FocusButton(
                        "Use this route",
                        onClick = viewModel::confirmRoute,
                        primary = true,
                        enabled = draft.fromWarehouseId.isNotBlank() && draft.toWarehouseId.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    )
                } }
            },
        ) {
            BusyLine(state.busy)
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (submitted != null) {
                    item(key = "submitted") { CreatedOrderCard(submitted) }
                }
                if (!tooFew) {
                    // Dropdowns, not a chip per warehouse: a worker assigned many warehouses got
                    // two walls of chips before the first item (feedback, 10 Oct).
                    item(key = "route") {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        ) {
                            Text("FROM", style = FocusType.caption, color = FocusColors.caption)
                            WarehouseDropdown(
                                options = state.warehouses,
                                selectedId = draft.fromWarehouseId,
                                placeholder = "Warehouse it ships from",
                                onPick = viewModel::pickFrom,
                            )
                            Icon(
                                Icons.Default.ArrowDownward,
                                contentDescription = null,
                                tint = FocusColors.iconMuted,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            )
                            Text("TO", style = FocusType.caption, color = FocusColors.caption)
                            WarehouseDropdown(
                                options = state.warehouses.filter { it.warehouseId != draft.fromWarehouseId },
                                selectedId = draft.toWarehouseId,
                                placeholder = "Warehouse it goes to",
                                onPick = viewModel::pickTo,
                            )
                        }
                    }
                }
                if (orders.isNotEmpty() && submitted == null) {
                    item(key = "orders-caption") { ListCaption("Your transfers", "Newest") }
                    items(orders.take(3), key = { it.id }) { order ->
                        val shippable = state.shippableIds?.let { ids -> order.documentNumber?.uppercase() in ids } ?: true
                        // No longer to ship from here: it shipped - the row still said "Created".
                        val shipped = state.shippableIds != null && !shippable && order.status == CreatedOrderStatus.CREATED
                        CreatedOrderCard(order, actionLabel = "Ship it now".takeIf { shippable }, laterStatus = "Shipped".takeIf { shipped }, onAction = {
                            viewModel.ship(order.documentNumber.orEmpty()); onShip()
                        })
                    }
                }
            }
        }
        return
    }

    // -- Step 2: lines -------------------------------------------------------------------------
    FocusScreen(
        title = "Create transfer",
        onBack = onBack,
        counter = draft.lines.size.takeIf { it > 0 }?.let { linesText(it) },
        band = state.error?.let { bandOf(BandKind.Bad, it) }
            ?: lastAdded?.let { BandState(BandKind.Ok, "Added", "${it.itemId} · ${com.d365fo.mobileops.domain.order.qtyText(it.qty)} ${it.unitId}".trim()) }
            ?: BandState(BandKind.Info, "New transfer · $route", "Ship date ${draft.shipDate}"),
        remaining = orderSize(draft.lines),
        scanPill = { ScanPillField(enabled = !state.busy) },
        topBarTrailing = {
            OrderToolsMenu(
                listOfNotNull(
                    OrderMenuItem("Camera scan", Icons.Default.PhotoCamera, { showCamera = true }, enabled = !state.busy),
                    OrderMenuItem("Change warehouses", Icons.Default.Route, viewModel::changeRoute).takeIf { draft.lines.isEmpty() },
                    OrderMenuItem("Discard order", Icons.Default.Delete, { confirmDiscard = true }).takeIf { draft.lines.isNotEmpty() },
                ),
            )
        },
        actions = if (draft.lines.isEmpty()) null else { {
            FocusButton("Lines", onClick = { showLines = true }, icon = Icons.AutoMirrored.Filled.ListAlt, modifier = Modifier.weight(1f))
            HoldToConfirmButton("Hold to create", onConfirm = viewModel::create, enabled = !state.busy, modifier = Modifier.weight(1f))
        } },
    ) {
        BusyLine(state.busy)
        if (lastAdded == null) {
            FocusCenter(caption = "New transfer", code = route, unitLine = "Ship date ${draft.shipDate}")
        } else {
            LastAddedCenter(lastAdded, onTap = { viewModel.editLine(lastAdded) })
        }
    }
}

/** The variant, stock and quantity steps - unchanged, with the same stock checks as before. */
@Composable
private fun ToDialogs(state: ToCreateState, viewModel: ToCreateViewModel) {
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
