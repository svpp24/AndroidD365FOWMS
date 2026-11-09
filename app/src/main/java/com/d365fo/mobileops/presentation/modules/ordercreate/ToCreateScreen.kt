package com.d365fo.mobileops.presentation.modules.ordercreate

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.data.local.entity.CreatedOrderStatus
import com.d365fo.mobileops.data.repository.DraftLine
import com.d365fo.mobileops.domain.order.qtyText
import com.d365fo.mobileops.presentation.common.components.FlowAction
import com.d365fo.mobileops.presentation.common.components.FlowKeyHandler
import com.d365fo.mobileops.presentation.common.components.ListRow
import com.d365fo.mobileops.presentation.common.components.NextCard
import com.d365fo.mobileops.presentation.common.components.QuantityPicker
import com.d365fo.mobileops.presentation.common.components.SquareButton
import com.d365fo.mobileops.presentation.common.components.StatusKind
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * Create transfer on the redesign's flow layout (brief §4, phase 7) - see [ToCreateViewModel].
 * From is the current store; the To stores are [ListRow]s (the only one ticked already) above
 * **Start**. Items are then scanned one by one; Back creates the transfer and stays, and once
 * D365FO has numbered it **Ship it now** opens Transfer - Ship on it ([onShip]). Back again leaves.
 */
@Composable
fun ToCreateScreen(onBack: () -> Unit, onShip: () -> Unit, viewModel: ToCreateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val orders by viewModel.orders.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var keypadFor by remember { mutableStateOf<DraftLine?>(null) }
    val draft = state.draft

    DisposableEffect(viewModel) {
        viewModel.scannerOnScreen(true)
        onDispose { viewModel.scannerOnScreen(false) }
    }

    val goBack: () -> Unit = {
        if (!viewModel.justScanned() && !state.busy) {
            when {
                state.showingLines -> viewModel.showLines(false)
                state.choosingVariant != null || state.choosingStock != null -> viewModel.cancelChoice()
                viewModel.hasOrderToSend -> viewModel.submit()
                else -> onBack()
            }
        }
    }
    BackHandler(onBack = goBack)

    val onAction: (FlowAction) -> Unit = { action ->
        when (action) {
            FlowAction.Back -> goBack()
            FlowAction.Lines -> if (state.routeChosen) viewModel.showLines(!state.showingLines)
            FlowAction.Undo -> viewModel.undo()
            FlowAction.Keypad -> viewModel.keypadLine()?.let { keypadFor = it }
        }
    }
    FlowKeyHandler(onAction)

    val sent = state.submittedId?.let { id -> orders.firstOrNull { it.id == id } }
    val shipNumber = sent?.takeIf { it.status == CreatedOrderStatus.CREATED && draft.lines.isEmpty() }?.documentNumber
    val route = "${draft.fromWarehouseId} → ${draft.toWarehouseId.ifBlank { "?" }}"
    val others = state.warehouses.filter { it.warehouseId != draft.fromWarehouseId }
    val band = when {
        state.notice != null -> OrderBand.of(state.notice!!)
        state.choosingVariant != null -> OrderBand(StatusKind.Warn, "Which ${state.choosingVariant!!.itemId}?", "Tap the variant to send")
        state.choosingStock != null -> OrderBand(StatusKind.Warn, "Which batch or serial?", "${state.choosingStock!!.item.itemId} has several at ${draft.fromWarehouseId}")
        sent != null && draft.lines.isEmpty() && !state.routeChosen -> sentOrderBand(sent, "Transfer", online)
        !state.routeChosen && others.isEmpty() -> OrderBand(StatusKind.Warn, "No other store to send to", "You need two warehouses - ask a supervisor")
        !state.routeChosen && draft.toWarehouseId.isBlank() -> OrderBand(StatusKind.Idle, "Where does it go?", "Tap the To store, then Start")
        !state.routeChosen -> OrderBand(StatusKind.Idle, "Ready · $route", "Start - or just scan the first item")
        draft.lines.isEmpty() -> OrderBand(StatusKind.Info, route, "Scan the items to send")
        else -> OrderBand(StatusKind.Info, "Scan the next item", "${linesWord(draft.lines.size)} · Back creates the transfer")
    }

    // Created: Ship it now opens Transfer - Ship on it (the existing route; PendingFlowResume hands it over).
    val shipAction: (@Composable RowScope.() -> Unit)? = shipNumber?.takeIf { state.notice == null && !state.routeChosen }?.let { number ->
        { SquareButton(onClick = { viewModel.ship(number); onShip() }) { Text("Ship it now") } }
    }

    OrderFlowLayout(
        title = if (state.routeChosen) "Transfer · $route" else "Create transfer",
        progress = draft.lines.takeIf { it.isNotEmpty() }?.let { linesWord(it.size) },
        onBack = goBack,
        busy = state.busy,
        band = band,
        onUndo = viewModel::undo,
        lastSent = sent?.takeIf { it.status == CreatedOrderStatus.CREATED }?.documentNumber,
        onAction = onAction,
        highlighted = if (draft.lines.isNotEmpty() && state.notice?.undoable == true) FlowAction.Undo else null,
        enabled = buildSet {
            add(FlowAction.Back)
            if (state.routeChosen) add(FlowAction.Lines)
            if (viewModel.canUndo) add(FlowAction.Undo)
            if (viewModel.keypadLine() != null) add(FlowAction.Keypad)
        },
        menuItems = { close ->
            DropdownMenuItem(
                text = { Text("Type it in") },
                leadingIcon = { Icon(Icons.Default.Keyboard, contentDescription = null) },
                onClick = { close(); typing = true },
            )
            DropdownMenuItem(
                text = { Text("Scan with camera") },
                leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                onClick = { close(); showCamera = true },
            )
            if (state.routeChosen && draft.lines.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Change where it goes") },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = { close(); viewModel.changeRoute() },
                )
            }
        },
        bandActions = shipAction,
        footer = if (!state.routeChosen && others.isNotEmpty()) {
            {
                SquareButton(
                    onClick = { viewModel.start() },
                    enabled = draft.toWarehouseId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.StepperButton),
                ) { Text(if (draft.toWarehouseId.isBlank()) "Start" else "Start · $route") }
            }
        } else {
            null
        },
    ) {
        when {
            state.showingLines -> DraftLinesView(
                lines = draft.lines,
                showPrice = false,
                currency = "",
                onEdit = { keypadFor = it },
                onRemove = viewModel::removeLine,
            )
            state.choosingVariant != null -> VariantChoice(state.choosingVariant!!, viewModel::pickVariant)
            state.choosingStock != null -> StockChoice(state.choosingStock!!.item, state.choosingStock!!.stock.orEmpty(), viewModel::pickStock)
            !state.routeChosen -> ListCard {
                sectionLabel("From ${draft.fromWarehouseId} (this store) - to")
                items(others, key = { it.warehouseId }) { wh ->
                    val chosen = wh.warehouseId == draft.toWarehouseId
                    ListRow(
                        title = wh.label,
                        status = if (chosen) StatusKind.Ok else null,
                        subtitles = listOfNotNull("Chosen".takeIf { chosen }),
                        onClick = { viewModel.pickTo(wh.warehouseId) },
                    )
                    HorizontalDivider(color = WarehouseStatusColors.divider)
                }
            }
            else -> {
                val line = lineInHand(draft.lines, state.lastAdd)
                if (line == null) {
                    NextCard(
                        label = "Next",
                        code = "Scan an item",
                        description = "From ${draft.fromWarehouseId} to ${draft.toWarehouseId}. Each scan sends one; F4 changes the quantity.",
                        quantity = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LineCard(
                        label = "Last scanned · ${linesWord(draft.lines.size)} on the transfer",
                        line = line,
                        location = listOfNotNull(
                            line.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                            line.serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" },
                            line.available?.let { "${qtyText(it)} at ${draft.fromWarehouseId}" },
                        ).joinToString(" · ").ifBlank { null },
                        locationLabel = "Stock",
                        onQuantityClick = { keypadFor = line },
                    )
                }
            }
        }
    }

    keypadFor?.let { line ->
        QuantityPicker(
            subtitle = listOf(line.itemId, line.variant.label, line.batchId, line.unitId).filter { it.isNotBlank() }.joinToString(" · "),
            hint = viewModel.keypadRules(line).hint.ifBlank { "Quantity to send" },
            initial = qtyText(line.qty),
            replaceInitial = true,
            confirmLabel = "Save",
            validate = { viewModel.keypadError(line, it) },
            onConfirm = { value ->
                keypadFor = null
                viewModel.changeQuantity(line, value)
            },
            onDismiss = { if (!viewModel.justScanned()) keypadFor = null },
            removeLabel = "Remove line",
            onRemove = {
                keypadFor = null
                viewModel.removeLine(line)
            },
        )
    }
    if (typing) {
        CodeEntryDialog(
            title = "Item",
            label = "Item number or barcode",
            onSubmit = { typing = false; viewModel.onTyped(it) },
            onDismiss = { typing = false },
        )
    }
    if (showCamera) {
        CameraScannerSheet(onDecoded = { viewModel.onCameraDecoded(it); showCamera = false }, onDismiss = { showCamera = false })
    }
}
