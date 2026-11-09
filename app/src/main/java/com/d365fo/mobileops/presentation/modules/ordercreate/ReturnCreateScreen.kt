package com.d365fo.mobileops.presentation.modules.ordercreate

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Tag
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.data.local.entity.CreatedOrderStatus
import com.d365fo.mobileops.data.repository.DraftLine
import com.d365fo.mobileops.domain.order.qtyText
import com.d365fo.mobileops.presentation.common.components.FlowAction
import com.d365fo.mobileops.presentation.common.components.FlowKeyHandler
import com.d365fo.mobileops.presentation.common.components.HoldToConfirmButton
import com.d365fo.mobileops.presentation.common.components.ListRow
import com.d365fo.mobileops.presentation.common.components.NextCard
import com.d365fo.mobileops.presentation.common.components.QuantityPicker
import com.d365fo.mobileops.presentation.common.components.StatusKind
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * Return to vendor on the redesign's flow layout (brief §4, phase 7) - see
 * [ReturnCreateViewModel]. Scan the original PO, then the items; **Hold to post return** is the
 * one deliberate act (brief §3: hold only for irreversible posts). Reason and action default to
 * the last used and are a ⋮ away; the vendor list is there for a return without its PO.
 */
@Composable
fun ReturnCreateScreen(onBack: () -> Unit, viewModel: ReturnCreateViewModel = hiltViewModel()) {
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

    // Back: an open list or choice closes first; otherwise leave - the return is kept as a draft
    // until it is held to post.
    val goBack: () -> Unit = {
        if (!viewModel.justScanned() && !state.busy) {
            when {
                state.showingLines -> viewModel.showLines(false)
                state.choosingVariant != null || state.choosingStock != null -> viewModel.cancelChoice()
                state.pickingCodes && state.missing == null -> viewModel.showCodes(false)
                state.pickingVendor -> viewModel.showVendorList(false)
                else -> onBack()
            }
        }
    }
    BackHandler(onBack = goBack)

    val onAction: (FlowAction) -> Unit = { action ->
        when (action) {
            FlowAction.Back -> goBack()
            FlowAction.Lines -> if (draft.vendorAccount.isNotBlank()) viewModel.showLines(!state.showingLines)
            FlowAction.Undo -> viewModel.undo()
            FlowAction.Keypad -> viewModel.keypadLine()?.let { keypadFor = it }
        }
    }
    FlowKeyHandler(onAction)

    // Reason or action with no default (no last used, several to choose from): ask, once lines are in.
    val needsCodes = draft.vendorAccount.isNotBlank() && (state.missing?.startsWith("Pick a return") == true)
    val showCodes = state.pickingCodes || (needsCodes && draft.lines.isNotEmpty())

    val sent = state.submittedId?.let { id -> orders.firstOrNull { it.id == id } }
    val band = when {
        state.notice != null -> OrderBand.of(state.notice!!)
        state.choosingVariant != null -> OrderBand(StatusKind.Warn, "Which ${state.choosingVariant!!.itemId}?", "Tap the variant going back")
        state.choosingStock != null -> OrderBand(StatusKind.Warn, "Take it from where?", "${state.choosingStock!!.item.itemId} is in several places here")
        showCodes -> OrderBand(StatusKind.Warn, state.missing ?: "Reason and action", "Remembered for the next return · Back when done")
        sent != null && draft.lines.isEmpty() && draft.vendorAccount.isBlank() -> sentOrderBand(sent, "Return", online)
        draft.vendorAccount.isBlank() -> OrderBand(StatusKind.Idle, "Ready", "Scan the original purchase order")
        draft.lines.isEmpty() -> OrderBand(StatusKind.Info, "${state.sourcePo ?: draft.vendorAccount} · ${draft.vendorName}".trimEnd(' ', '·'), "Scan the items that go back")
        else -> OrderBand(StatusKind.Info, "Scan the next item", "${linesWord(draft.lines.size)} · hold to post when done")
    }

    OrderFlowLayout(
        title = if (draft.vendorAccount.isBlank()) "Return to vendor" else "Return · ${draft.vendorAccount}",
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
            if (draft.vendorAccount.isNotBlank()) add(FlowAction.Lines)
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
            if (draft.vendorAccount.isBlank()) {
                DropdownMenuItem(
                    text = { Text("Pick the vendor instead") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    onClick = { close(); viewModel.showVendorList(true) },
                )
            } else {
                if (state.reasons.isNotEmpty() || state.actions.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Reason and action") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { close(); viewModel.showCodes(true) },
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (draft.rmaNumber.isBlank()) "Vendor RMA number" else "RMA ${draft.rmaNumber}") },
                    leadingIcon = { Icon(Icons.Default.Tag, contentDescription = null) },
                    onClick = { close(); viewModel.editRma(true) },
                )
                if (draft.lines.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Change vendor") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { close(); viewModel.changeVendor() },
                    )
                }
            }
        },
        footer = if (draft.lines.isNotEmpty() && !state.showingLines && !showCodes && state.choosingStock == null && state.choosingVariant == null) {
            {
                HoldToConfirmButton(
                    label = "Hold to post return",
                    onConfirm = viewModel::post,
                    enabled = state.missing == null && !state.busy,
                    hint = "Keep holding - the return is posted to ${draft.vendorAccount}",
                )
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
            showCodes -> CodesChoice(state, onReason = viewModel::pickReason, onAction = viewModel::pickAction)
            draft.vendorAccount.isBlank() && state.pickingVendor -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TapToTypeField(state.vendorQuery, viewModel::onVendorQuery, "Search by name or account", modifier = Modifier.fillMaxWidth())
                ListCard(Modifier.weight(1f)) {
                    sectionLabel("${state.vendorCount} vendors")
                    items(state.vendors, key = { it.accountNum }) { vendor ->
                        ListRow(
                            title = vendor.name.ifBlank { vendor.accountNum },
                            subtitles = listOf(vendor.accountNum),
                            onClick = { viewModel.pickVendor(vendor) },
                        )
                        HorizontalDivider(color = WarehouseStatusColors.divider)
                    }
                }
            }
            draft.vendorAccount.isBlank() -> NextCard(
                label = "Next",
                code = "Purchase order",
                description = "Scan the PO the stock came in on - its vendor is the return's. No PO? ⋮ Pick the vendor instead.",
                quantity = null,
                modifier = Modifier.fillMaxSize(),
            )
            else -> {
                val line = lineInHand(draft.lines, state.lastAdd)
                val codes = listOfNotNull(state.reasonLabel?.let { "Reason $it" }, state.actionLabel?.let { "action $it" }).joinToString(" · ")
                if (line == null) {
                    NextCard(
                        label = "Next",
                        code = "Scan an item",
                        description = listOf(
                            "Each scan returns one, from the location that holds it.",
                            codes.ifBlank { "Reason and action: ⋮" },
                        ).joinToString(" "),
                        quantity = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LineCard(
                        label = "Last scanned · ${linesWord(draft.lines.size)} on the return",
                        line = line,
                        location = line.locationId.ifBlank { null } ?: if (online) null else "Checked by D365FO",
                        locationLabel = "From location",
                        onQuantityClick = { keypadFor = line },
                    )
                }
            }
        }
    }

    keypadFor?.let { line ->
        QuantityPicker(
            subtitle = listOf(line.itemId, line.variant.label, line.locationId, line.unitId).filter { it.isNotBlank() }.joinToString(" · "),
            hint = viewModel.keypadRules(line).hint.ifBlank { "Quantity going back" },
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
    if (state.editingRma) {
        CodeEntryDialog(
            title = "Vendor RMA number",
            label = "Up to 10 characters",
            onSubmit = viewModel::setRma,
            onDismiss = { viewModel.editRma(false) },
        )
    }
    if (typing) {
        CodeEntryDialog(
            title = if (draft.vendorAccount.isBlank()) "Purchase order" else "Item",
            label = if (draft.vendorAccount.isBlank()) "Purchase order number" else "Item number or barcode",
            onSubmit = { typing = false; viewModel.onTyped(it) },
            onDismiss = { typing = false },
        )
    }
    if (showCamera) {
        CameraScannerSheet(onDecoded = { viewModel.onCameraDecoded(it); showCamera = false }, onDismiss = { showCamera = false })
    }
}

/** Reason and action as [ListRow]s - the chosen ones ticked; a tap picks. */
@Composable
private fun CodesChoice(state: ReturnCreateState, onReason: (String) -> Unit, onAction: (String) -> Unit) {
    ListCard {
        if (state.reasons.isNotEmpty()) {
            sectionLabel("Return reason")
            items(state.reasons, key = { "r:${it.code}" }) { reason ->
                val chosen = reason.code == state.draft.reasonCode
                ListRow(
                    title = reason.label,
                    status = if (chosen) StatusKind.Ok else null,
                    subtitles = listOfNotNull("Chosen".takeIf { chosen }),
                    onClick = { onReason(reason.code) },
                )
                HorizontalDivider(color = WarehouseStatusColors.divider)
            }
        }
        if (state.actions.isNotEmpty()) {
            sectionLabel("Return action")
            items(state.actions, key = { "a:${it.code}" }) { action ->
                val chosen = action.code == state.draft.actionCode
                ListRow(
                    title = action.label,
                    status = if (chosen) StatusKind.Ok else null,
                    subtitles = listOfNotNull("Chosen".takeIf { chosen }),
                    onClick = { onAction(action.code) },
                )
                HorizontalDivider(color = WarehouseStatusColors.divider)
            }
        }
    }
}
