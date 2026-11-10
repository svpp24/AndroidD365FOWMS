package com.d365fo.mobileops.presentation.modules.ordercreate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.presentation.common.focus.BandKind
import com.d365fo.mobileops.presentation.common.focus.BandState
import com.d365fo.mobileops.presentation.common.focus.FocusButton
import com.d365fo.mobileops.presentation.common.focus.FocusCenter
import com.d365fo.mobileops.presentation.common.focus.FocusListRow
import com.d365fo.mobileops.presentation.common.focus.FocusScreen
import com.d365fo.mobileops.presentation.common.focus.HoldToConfirmButton
import com.d365fo.mobileops.presentation.common.focus.ScanPillField
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusType
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.modules.stepflow.bandOf

/**
 * Purchase return - see [ReturnCreateViewModel] - in the Focus design, on the existing steps:
 * the vendor (picked, or taken from the original PO scanned), the return reason and action,
 * items (stock and quantity asked as before), then Hold to post return - posting can't be undone.
 */
@Composable
fun ReturnCreateScreen(onBack: () -> Unit, viewModel: ReturnCreateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val orders by viewModel.orders.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    var showLines by rememberSaveable { mutableStateOf(false) }
    var showReason by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val draft = state.draft
    val lastAdded = rememberLastAdded(draft.lines)

    ReturnDialogs(state, viewModel)
    if (confirmDiscard) {
        DiscardDialog(what = "return", lines = draft.lines.size, onKeep = { confirmDiscard = false }, onDiscard = { confirmDiscard = false; viewModel.discard() })
    }
    if (showCamera) {
        CameraScannerSheet(onDecoded = { viewModel.onCameraDecoded(it); showCamera = false }, onDismiss = { showCamera = false })
    }

    if (showLines && draft.vendorAccount.isNotBlank() && draft.lines.isNotEmpty()) {
        OrderLinesScreen(
            title = "Return lines",
            lines = draft.lines,
            showPrice = false,
            currency = "",
            footer = orderSize(draft.lines),
            onLine = { viewModel.editLine(it) },
            onClose = { showLines = false },
        )
        return
    }

    if (draft.vendorAccount.isBlank()) {
        // -- Step 1: the vendor - picked, or taken from the original PO scanned ---------------
        val submitted = state.submittedId?.let { id -> orders.firstOrNull { it.id == id } }
        FocusScreen(
            title = "Return to vendor",
            onBack = onBack,
            band = state.error?.let { bandOf(BandKind.Bad, it) }
                ?: submitted?.let { BandState(BandKind.Ok, "Return saved", it.documentNumber ?: it.title) }
                ?: BandState(BandKind.Info, "New return", "From ${draft.warehouseId}".takeIf { draft.warehouseId.isNotBlank() }),
            scanPill = { ScanPillField(enabled = !state.busy) },
            actions = if (submitted == null) null else { {
                FocusButton("Start another return", onClick = viewModel::dismissResult, modifier = Modifier.weight(1f))
            } },
        ) {
            BusyLine(state.busy)
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Keyed so the field keeps focus when the rows above go away as typing starts.
                item(key = "vendor-search") {
                    TapToTypeField(
                        state.vendorQuery, viewModel::onVendorQuery, "Type to search vendor",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                if (submitted != null) {
                    item(key = "submitted") { CreatedOrderCard(submitted) }
                } else if (orders.isNotEmpty() && state.vendorQuery.isBlank()) {
                    item(key = "orders-caption") { ListCaption("Your returns", "Newest") }
                    items(orders.take(3), key = { it.id }) { order -> CreatedOrderCard(order) }
                }
                item(key = "vendor-caption") {
                    ListCaption(
                        "Vendors" + (if (state.vendorCount > 0) " · ${state.vendorCount}" else ""),
                        trailing = if (state.vendorCount == 0) (if (online) "Getting vendors…" else "No vendors yet - connect once") else null,
                    )
                }
                items(state.vendors, key = { it.accountNum }) { vendor ->
                    FocusListRow(
                        title = vendor.name,
                        subtitle = vendor.accountNum,
                        icon = Icons.Default.Store,
                        chevron = true,
                        onClick = { viewModel.pickVendor(vendor) },
                    )
                }
            }
        }
        return
    }

    // -- Step 2: reason and action, then lines -------------------------------------------------
    val hasReasons = state.reasons.isNotEmpty() || state.actions.isNotEmpty()
    val reasonUnset = (state.reasons.isNotEmpty() && draft.reasonCode.isBlank()) || (state.actions.isNotEmpty() && draft.actionCode.isBlank())
    // The reason step: until a reason and action are chosen, or when changed from the menu.
    val choosingReason = hasReasons && (showReason || (draft.lines.isEmpty() && reasonUnset))
    val reasonLabel = state.reasons.firstOrNull { it.code == draft.reasonCode }?.label
    FocusScreen(
        title = "Return to vendor",
        onBack = onBack,
        counter = draft.lines.size.takeIf { it > 0 }?.let { linesText(it) },
        band = returnBand(state, lastAdded, reasonLabel, online),
        remaining = orderSize(draft.lines),
        // Scans work on the reason step too (any order): an item scanned there is added.
        scanPill = { ScanPillField(enabled = !state.busy) },
        topBarTrailing = {
            OrderToolsMenu(
                listOfNotNull(
                    OrderMenuItem("Camera scan", Icons.Default.PhotoCamera, { showCamera = true }, enabled = !state.busy),
                    OrderMenuItem("Change reason", Icons.Default.EditNote, { showReason = true }).takeIf { hasReasons && !choosingReason },
                    OrderMenuItem(if (draft.rmaNumber.isBlank()) "Add vendor RMA" else "Change RMA", Icons.Default.Description, { viewModel.editRma(true) }),
                    OrderMenuItem("Change vendor", Icons.Default.Store, viewModel::changeVendor).takeIf { draft.lines.isEmpty() },
                    OrderMenuItem("Discard return", Icons.Default.Delete, { confirmDiscard = true }),
                ),
            )
        },
        actions = {
            if (choosingReason) {
                FocusButton(
                    "Done",
                    onClick = { showReason = false },
                    primary = true,
                    enabled = !reasonUnset,
                    modifier = Modifier.weight(1f),
                )
            } else {
                if (draft.lines.isNotEmpty()) {
                    FocusButton("Lines", onClick = { showLines = true }, icon = Icons.AutoMirrored.Filled.ListAlt, modifier = Modifier.weight(1f))
                }
                HoldToConfirmButton(
                    "Hold to post return",
                    onConfirm = viewModel::create,
                    enabled = !state.busy && draft.lines.isNotEmpty() && state.missing == null,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) {
        BusyLine(state.busy)
        when {
            choosingReason -> ReasonChoice(state, viewModel)
            lastAdded != null -> LastAddedCenter(lastAdded, onTap = { viewModel.editLine(lastAdded) })
            else -> FocusCenter(
                caption = "New return",
                code = draft.vendorAccount,
                description = draft.vendorName.takeIf { it.isNotBlank() },
                unitLine = listOfNotNull(
                    "From ${draft.warehouseId}",
                    reasonLabel,
                    draft.rmaNumber.takeIf { it.isNotBlank() }?.let { "RMA $it" },
                ).joinToString(" · "),
            )
        }
    }
}

/**
 * What went wrong, else what still stops the post (said as what is missing, not as an
 * instruction), else what the last scan added, else the return itself.
 */
internal fun returnBand(
    state: ReturnCreateState,
    lastAdded: com.d365fo.mobileops.data.repository.DraftLine?,
    reasonLabel: String?,
    online: Boolean,
): BandState {
    val draft = state.draft
    state.error?.let { return bandOf(BandKind.Bad, it) }
    if (draft.lines.isNotEmpty()) {
        when {
            state.reasons.isNotEmpty() && draft.reasonCode.isBlank() -> return BandState(BandKind.Warn, "No return reason", "nothing posted")
            state.actions.isNotEmpty() && draft.actionCode.isBlank() -> return BandState(BandKind.Warn, "No return action", "nothing posted")
        }
    }
    lastAdded?.let {
        return BandState(
            BandKind.Ok,
            "Added",
            listOfNotNull(
                "${it.itemId} · ${com.d365fo.mobileops.domain.order.qtyText(it.qty)} ${it.unitId}".trim(),
                it.locationId.takeIf { l -> l.isNotBlank() }?.let { l -> "From $l" },
            ).joinToString(" · "),
        )
    }
    return BandState(
        BandKind.Info,
        "New return · ${draft.vendorName.ifBlank { draft.vendorAccount }}",
        reasonLabel?.let { "Reason: $it" } ?: "Reasons load when online".takeIf { state.reasons.isEmpty() && !online },
    )
}

/** The return reason and action: D365FO's own setup, any length - dropdowns, as before. */
@Composable
private fun ColumnScope.ReasonChoice(state: ReturnCreateState, viewModel: ReturnCreateViewModel) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        if (state.reasons.isNotEmpty()) {
            Text("RETURN REASON", style = FocusType.caption, color = FocusColors.caption)
            com.d365fo.mobileops.presentation.common.components.DropdownField(
                options = state.reasons.map { com.d365fo.mobileops.presentation.common.components.DropdownOption(it.code, it.label) },
                selectedId = state.draft.reasonCode,
                placeholder = "Reason",
                onPick = viewModel::pickReason,
            )
        }
        if (state.actions.isNotEmpty()) {
            Text("RETURN ACTION", style = FocusType.caption, color = FocusColors.caption, modifier = Modifier.padding(top = 12.dp))
            com.d365fo.mobileops.presentation.common.components.DropdownField(
                options = state.actions.map { com.d365fo.mobileops.presentation.common.components.DropdownOption(it.code, it.label) },
                selectedId = state.draft.actionCode,
                placeholder = "Action",
                onPick = viewModel::pickAction,
            )
        }
    }
}

/** The variant, stock, quantity and RMA steps - unchanged, with the same checks as before. */
@Composable
private fun ReturnDialogs(state: ReturnCreateState, viewModel: ReturnCreateViewModel) {
    val draft = state.draft
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
}
