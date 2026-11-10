package com.d365fo.mobileops.presentation.modules.ordercreate

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.AlertDialog
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
import com.d365fo.mobileops.domain.order.amountText
import com.d365fo.mobileops.presentation.common.focus.BandKind
import com.d365fo.mobileops.presentation.common.focus.BandState
import com.d365fo.mobileops.presentation.common.focus.FocusButton
import com.d365fo.mobileops.presentation.common.focus.FocusCenter
import com.d365fo.mobileops.presentation.common.focus.FocusListRow
import com.d365fo.mobileops.presentation.common.focus.FocusScreen
import com.d365fo.mobileops.presentation.common.focus.HoldToConfirmButton
import com.d365fo.mobileops.presentation.common.focus.ScanPillField
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.modules.stepflow.bandOf
import com.d365fo.mobileops.util.Formatters

/**
 * PO creation - see [PoCreateViewModel] - in the Focus design (brief sections 6 and 9), on the
 * existing steps: choose the vendor, then scan items (each asks its quantity, with the item's
 * order rules), then hold to create. The band says what the last scan did, the centre shows the
 * last line added, and the order's lines open on demand.
 */
@Composable
fun PoCreateScreen(
    onBack: () -> Unit,
    /** Opens PO confirmation on a created order. */
    onConfirm: (String) -> Unit = {},
    viewModel: PoCreateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val orders by viewModel.orders.collectAsStateWithLifecycle()
    val mayConfirm by viewModel.mayConfirm.collectAsStateWithLifecycle()
    val confirmedPurchIds by viewModel.confirmedPurchIds.collectAsStateWithLifecycle()
    /** "Confirm it now" on a created PO - for workers allowed to confirm, until it is confirmed. */
    // Creating a PO confirms it too (HSMobileOpsOrderCreateService::confirmCreated); the link
    // stays only for one that was created but could not be confirmed (needs approval first).
    fun confirmLabel(order: com.d365fo.mobileops.data.local.entity.CreatedOrderEntity) =
        if (mayConfirm && order.documentNumber !in confirmedPurchIds &&
            order.message?.startsWith("Created; not confirmed") == true
        ) "Confirm it now" else null
    var showCamera by remember { mutableStateOf(false) }
    var showLines by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val draft = state.draft
    val lastAdded = rememberLastAdded(draft.lines)
    val busy = state.busy || state.pricing || state.refreshingVendors

    PoDialogs(state, viewModel, online)
    if (confirmDiscard) {
        DiscardDialog(what = "order", lines = draft.lines.size, onKeep = { confirmDiscard = false }, onDiscard = { confirmDiscard = false; viewModel.discard() })
    }
    if (showCamera) {
        CameraScannerSheet(onDecoded = { viewModel.onCameraDecoded(it); showCamera = false }, onDismiss = { showCamera = false })
    }
    val priced = draft.lines.isNotEmpty() && draft.lines.all { it.lineAmount != null }
    val total = if (priced) "Total ${amountText(draft.lines.sumOf { it.lineAmount ?: 0.0 })} ${draft.currencyCode}".trim() else null

    if (showLines && draft.vendorAccount.isNotBlank() && draft.lines.isNotEmpty()) {
        OrderLinesScreen(
            title = "PO lines",
            lines = draft.lines,
            showPrice = true,
            currency = draft.currencyCode,
            footer = total ?: "Priced by D365FO",
            onLine = { viewModel.editLine(it) },
            onClose = { showLines = false },
        )
        return
    }

    if (draft.vendorAccount.isBlank()) {
        // -- Step 1: vendor, with the order just saved and the latest made here --------------
        val submitted = state.submittedId?.let { id -> orders.firstOrNull { it.id == id } }
        FocusScreen(
            title = "Create PO",
            onBack = onBack,
            band = state.error?.let { bandOf(BandKind.Bad, it) }
                ?: submitted?.let { BandState(BandKind.Ok, "Order saved", it.documentNumber ?: it.title) },
            actions = if (submitted == null) null else { {
                FocusButton("Start another order", onClick = viewModel::dismissResult, modifier = Modifier.weight(1f))
            } },
        ) {
            BusyLine(busy)
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Keyed: the rows above disappear with the first letter typed, and an unkeyed
                // field would be recomposed at a new index - losing focus and the next letters.
                item(key = "vendor-search") {
                    TapToTypeField(
                        state.vendorQuery, viewModel::onVendorQuery, "Type to search vendor",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                if (submitted != null) {
                    item(key = "submitted") {
                        CreatedOrderCard(submitted, actionLabel = confirmLabel(submitted), onAction = { submitted.documentNumber?.let(onConfirm) })
                    }
                } else if (orders.isNotEmpty() && state.vendorQuery.isBlank()) {
                    // The latest orders made here (they hide while searching).
                    item(key = "orders-caption") { ListCaption("Your orders", "Newest") }
                    items(orders.take(3), key = { it.id }) { order ->
                        CreatedOrderCard(order, actionLabel = confirmLabel(order), onAction = { order.documentNumber?.let(onConfirm) })
                    }
                }
                item(key = "vendor-caption") {
                    ListCaption(
                        "Vendors" + (if (state.vendorCount > 0) " · ${state.vendorCount}" else ""),
                        trailing = when {
                            state.vendorCount == 0 -> if (online) "Getting vendors…" else "No vendors yet - connect once"
                            online -> "Refresh" + (state.vendorsSavedAt?.let { " · ${Formatters.clockTime(it)}" } ?: "")
                            else -> state.vendorsSavedAt?.let { "Updated ${Formatters.clockTime(it)}" }
                        },
                        onTrailing = if (online && state.vendorCount > 0) ({ viewModel.refreshVendors(true) }) else null,
                    )
                }
                items(state.vendors, key = { it.accountNum }) { vendor ->
                    FocusListRow(
                        title = vendor.name,
                        subtitle = listOf(vendor.accountNum, vendor.currencyCode, vendor.paymentTerms).filter { it.isNotBlank() }.joinToString(" · "),
                        icon = Icons.Default.Store,
                        chevron = true,
                        onClick = { viewModel.pickVendor(vendor) },
                    )
                }
            }
        }
        return
    }

    // -- Step 2: lines ---------------------------------------------------------------------
    FocusScreen(
        title = "Create PO",
        onBack = onBack,
        counter = draft.lines.size.takeIf { it > 0 }?.let { linesText(it) },
        band = poBand(state, lastAdded),
        remaining = orderSize(draft.lines, total),
        scanPill = { ScanPillField(enabled = !state.busy) },
        topBarTrailing = {
            OrderToolsMenu(
                listOfNotNull(
                    OrderMenuItem("Camera scan", Icons.Default.PhotoCamera, { showCamera = true }, enabled = !state.busy),
                    OrderMenuItem("Change vendor", Icons.Default.Store, viewModel::changeVendor).takeIf { draft.lines.isEmpty() },
                    OrderMenuItem("Discard order", Icons.Default.Delete, { confirmDiscard = true }),
                ),
            )
        },
        actions = {
            if (draft.lines.isNotEmpty()) {
                FocusButton("Lines", onClick = { showLines = true }, icon = Icons.AutoMirrored.Filled.ListAlt, modifier = Modifier.weight(1f))
            }
            HoldToConfirmButton(
                "Hold to create",
                onConfirm = viewModel::create,
                enabled = !state.busy && draft.lines.isNotEmpty(),
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        BusyLine(busy)
        if (lastAdded == null) {
            FocusCenter(
                caption = "New order",
                code = draft.vendorAccount,
                description = draft.vendorName.takeIf { it.isNotBlank() },
                unitLine = "To ${draft.warehouseId} · Delivery ${draft.deliveryDate}",
            )
        } else {
            LastAddedCenter(lastAdded, onTap = { viewModel.editLine(lastAdded) })
        }
    }
}

/** The band on the lines step: what went wrong, else what the last scan added, else the order. */
internal fun poBand(state: PoCreateState, lastAdded: com.d365fo.mobileops.data.repository.DraftLine?): BandState {
    val draft = state.draft
    return state.error?.let { bandOf(BandKind.Bad, it) }
        ?: lastAdded?.let {
            BandState(BandKind.Ok, "Added", "${it.itemId} · ${com.d365fo.mobileops.domain.order.qtyText(it.qty)} ${it.unitId}".trim())
        }
        ?: BandState(BandKind.Info, "New order · ${draft.vendorName.ifBlank { draft.vendorAccount }}", "To ${draft.warehouseId}")
}

/** The quantity, price and variant steps - unchanged: the item's order rules are checked as before. */
@Composable
private fun PoDialogs(state: PoCreateState, viewModel: PoCreateViewModel, online: Boolean) {
    state.choosingVariant?.let { item ->
        VariantPickerDialog(item = item, onPick = viewModel::pickVariant, onDismiss = viewModel::cancelPending)
    }
    state.enteringQty?.let { pending ->
        NumberDialog(
            title = "Quantity",
            subtitle = listOf(pending.item.itemId, pending.variant?.label.orEmpty(), pending.item.purchUnit).filter { it.isNotBlank() }.joinToString(" · "),
            initial = pending.item.standardQty.takeIf { it > 0 },
            rules = viewModel.quantityRules(pending.item),
            confirmLabel = "Add line",
            onConfirm = viewModel::addLine,
            onDismiss = viewModel::cancelPending,
        )
    }
    state.editing?.let { line ->
        NumberDialog(
            title = "Quantity",
            subtitle = listOf(line.itemId, line.variant.label).filter { it.isNotBlank() }.joinToString(" · "),
            initial = line.qty,
            rules = null,
            confirmLabel = "Save",
            onConfirm = { viewModel.changeQuantity(line, it) },
            onDismiss = { viewModel.editLine(null) },
            onRemove = { viewModel.removeLine(line) },
            // Supervisors only, and only online - offline there is no D365FO price to replace.
            extraLabel = if (viewModel.mayOverridePrice && online) "Set the price instead" else null,
            onExtra = { viewModel.editPrice(line) },
        )
    }
    state.editingPrice?.let { line ->
        NumberDialog(
            title = "${line.itemId} - price",
            subtitle = "Replaces D365FO's price on this line" + (line.price?.let { " (now ${amountText(it)})" } ?: ""),
            initial = line.price,
            rules = null,
            confirmLabel = "Use this price",
            onConfirm = { viewModel.overridePrice(line, it) },
            onDismiss = { viewModel.editPrice(null) },
        )
    }
}

/** "Discard this order?" - kept from before: it drops lines that were never sent. */
@Composable
internal fun DiscardDialog(what: String, lines: Int, onKeep: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("Discard this $what?") },
        text = { Text("${linesText(lines)} removed. Nothing was sent.") },
        confirmButton = {
            com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = onKeep, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Keep") } },
                confirm = { com.d365fo.mobileops.presentation.common.components.CancelButton(onClick = onDiscard, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Discard") } },
            )
        },
    )
}
