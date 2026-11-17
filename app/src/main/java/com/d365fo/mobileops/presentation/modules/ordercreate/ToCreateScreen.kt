package com.d365fo.mobileops.presentation.modules.ordercreate

import androidx.compose.material.icons.outlined.QrCodeScanner
import com.d365fo.mobileops.presentation.common.focus.FocusTask
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
            onRemove = { viewModel.removeLine(it) },
            onChangeQty = { line, qty -> viewModel.changeQuantity(line, qty) },
        )
        return
    }

    // "Choose the stock" as a page (user, 17 Nov 2026).
    state.pending?.takeIf { it.choosingStock && it.stock != null }?.let { pending ->
        StockPickerPage(
            title = "Create transfer",
            item = pending.item,
            rows = pending.stock.orEmpty(),
            onPick = viewModel::pickStock,
            onDismiss = viewModel::cancelPending,
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
                if (submitted != null) {
                    // The saved transfer as a task box, not a green bar (user, 17 Nov 2026).
                    item(key = "saved-task") {
                        com.d365fo.mobileops.presentation.common.focus.FocusTaskBox(
                            savedTask(submitted, "Transfer"),
                            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    item(key = "orders-caption") { ListCaption("Your transfers", "Newest") }
                    item(key = "submitted") { CreatedOrderCard(submitted) }
                } else if (orders.isNotEmpty()) {
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
        sidePane = {
            com.d365fo.mobileops.presentation.common.focus.FocusCaption(
                "Lines · ${draft.lines.size}",
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
            )
            androidx.compose.material3.HorizontalDivider(color = com.d365fo.mobileops.presentation.common.theme.FocusColors.divider)
            var confirming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.d365fo.mobileops.data.repository.DraftLine?>(null) }
            confirming?.let { line -> RemoveLineDialog(line, onRemove = { confirming = null; viewModel.removeLine(line) }, onKeep = { confirming = null }) }
            if (draft.lines.isEmpty()) {
                Text("Scanned items show here.", style = com.d365fo.mobileops.presentation.common.theme.FocusType.rowDetail, color = com.d365fo.mobileops.presentation.common.theme.FocusColors.muted, modifier = Modifier.padding(20.dp))
            }
            androidx.compose.foundation.lazy.LazyColumn {
                items(draft.lines.size) { i ->
                    val line = draft.lines[draft.lines.size - 1 - i]
                    OrderLineCard(line, showPrice = false, currency = "", onRemove = { confirming = line }) { viewModel.editLine(line) }
                }
            }
        },
        band = state.error?.let { bandOf(BandKind.Bad, it) },
        remaining = orderSize(draft.lines),
        // Says what to do first while the transfer is empty (user, 15 Nov); same look as always.
        scanPill = { ScanPillField(label = "Scan an item to send", enabled = !state.busy) },
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
            // A tablet shows the lines beside or under the scan, so no Lines button there.
            if (com.d365fo.mobileops.presentation.common.focus.tabletMode() == com.d365fo.mobileops.presentation.common.focus.TabletMode.Phone) FocusButton("Lines", onClick = { showLines = true }, icon = Icons.AutoMirrored.Filled.ListAlt, modifier = Modifier.weight(1f))
            HoldToConfirmButton("Hold to create", onConfirm = viewModel::create, enabled = !state.busy, modifier = Modifier.weight(1f))
        } },
    ) {
        BusyLine(state.busy)
        if (lastAdded == null) {
            FocusCenter(task = FocusTask("Scan an item", "to add it to the transfer", androidx.compose.material.icons.Icons.Outlined.QrCodeScanner), caption = "New transfer", code = route, unitLine = "Ship date ${draft.shipDate}")
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
            // Shown as StockPickerPage by the screen.
        } else if (state.enteringQty) {
            NumberDialog(
                title = "Quantity",
                subtitle = listOf(
                    pending.item.itemId,
                    pending.item.itemName,
                    pending.variant?.label.orEmpty(),
                    pending.pick?.batchId?.takeIf { it.isNotBlank() }?.let { "Batch $it" }.orEmpty(),
                    pending.pick?.serialId?.takeIf { it.isNotBlank() }?.let { "Serial $it" }.orEmpty(),
                    pending.unit.ifBlank { pending.item.inventUnit },
                ).filter { it.isNotBlank() }.joinToString(" · "),
                initial = if (pending.pick?.serialId?.isNotBlank() == true) 1.0 else null,
                rules = viewModel.quantityRules(pending),
                confirmLabel = "Add line",
                onConfirm = viewModel::addLine,
                onDismiss = viewModel::cancelPending,
                // The unit to type the quantity in (user, 17 Nov 2026) - converted to the
                // inventory unit for the line, as Ship and Receive convert. Not for a serial.
                top = if (pending.units.size > 1 && pending.pick?.serialId.isNullOrBlank()) ({
                    com.d365fo.mobileops.presentation.common.components.UnitChips(
                        units = pending.units,
                        lineUnit = pending.item.inventUnit,
                        selected = pending.unit.ifBlank { pending.item.inventUnit },
                        onSelect = viewModel::setUnit,
                    )
                }) else null,
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
