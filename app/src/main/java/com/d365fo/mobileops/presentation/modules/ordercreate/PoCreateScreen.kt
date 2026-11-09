package com.d365fo.mobileops.presentation.modules.ordercreate

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
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
import com.d365fo.mobileops.domain.order.amountText
import com.d365fo.mobileops.domain.order.qtyText
import com.d365fo.mobileops.presentation.common.components.FlowAction
import com.d365fo.mobileops.presentation.common.components.FlowKeyHandler
import com.d365fo.mobileops.presentation.common.components.ListRow
import com.d365fo.mobileops.presentation.common.components.NextCard
import com.d365fo.mobileops.presentation.common.components.QuantityPicker
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton
import com.d365fo.mobileops.presentation.common.components.StatusKind
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.presentation.common.theme.WarehouseType
import com.d365fo.mobileops.util.Formatters

/**
 * Create PO on the redesign's flow layout (brief §4, phase 7) - see [PoCreateViewModel] for the
 * rules. Before a vendor: the vendor list, last used first, as [ListRow]s under a search field.
 * Then the next-card shows the line the last scan added, its quantity a tap (or F4) from the
 * keypad. Back sends the order (created and confirmed) and stays, the band following it; Back
 * again leaves.
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
    var showCamera by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var keypadFor by remember { mutableStateOf<DraftLine?>(null) }
    val draft = state.draft

    DisposableEffect(viewModel) {
        viewModel.scannerOnScreen(true)
        onDispose { viewModel.scannerOnScreen(false) }
    }

    // Back (arrow, system back, F1): an open list or choice closes first; an order in hand is
    // created and confirmed (brief: "when the worker leaves") and the screen stays to show it go;
    // otherwise it leaves. The scanner's phantom back right after a scan is ignored.
    val goBack: () -> Unit = {
        if (!viewModel.justScanned() && !state.busy) {
            when {
                state.showingLines -> viewModel.showLines(false)
                state.choosingVariant != null -> viewModel.cancelVariant()
                viewModel.hasOrderToSend -> viewModel.submit()
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

    val sent = state.submittedId?.let { id -> orders.firstOrNull { it.id == id } }
    val band = when {
        state.notice != null -> OrderBand.of(state.notice!!)
        state.choosingVariant != null -> OrderBand(StatusKind.Warn, "Which ${state.choosingVariant!!.itemId}?", "Tap the variant to order")
        sent != null && draft.lines.isEmpty() -> sentOrderBand(sent, "PO", online)
        draft.vendorAccount.isBlank() -> OrderBand(StatusKind.Idle, "Choose the vendor", "Tap one - or scan its account")
        draft.lines.isEmpty() -> OrderBand(StatusKind.Info, "${draft.vendorAccount} ${draft.vendorName}".trim(), "Scan the items to order")
        else -> OrderBand(StatusKind.Info, "Scan the next item", "${linesWord(draft.lines.size)} · Back creates the PO")
    }

    // Created but not confirmed (it needs approval first): a worker allowed to confirm can go on.
    val confirmNumber = sent?.takeIf {
        draft.lines.isEmpty() && state.notice == null && mayConfirm && it.status == CreatedOrderStatus.CREATED &&
            it.message?.startsWith("Created; not confirmed") == true
    }?.documentNumber
    val confirmAction: (@Composable RowScope.() -> Unit)? = confirmNumber?.let { number ->
        { SquareOutlinedButton(onClick = { onConfirm(number) }) { Text("Confirm it now") } }
    }

    OrderFlowLayout(
        title = if (draft.vendorAccount.isBlank()) "Create PO" else "PO · ${draft.vendorAccount}",
        progress = draft.lines.takeIf { it.isNotEmpty() }?.let { linesWord(it.size) },
        onBack = goBack,
        busy = state.busy || state.pricing || state.refreshingVendors,
        band = band,
        onUndo = viewModel::undo,
        lastSent = sent?.takeIf { it.status == CreatedOrderStatus.CREATED }?.documentNumber,
        onAction = onAction,
        highlighted = when {
            state.choosingVariant != null -> null
            draft.lines.isNotEmpty() && state.notice?.undoable == true -> FlowAction.Undo
            else -> null
        },
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
            if (draft.vendorAccount.isBlank() && online) {
                DropdownMenuItem(
                    text = { Text("Refresh vendors") },
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                    onClick = { close(); viewModel.refreshVendors(true) },
                )
            }
            if (draft.vendorAccount.isNotBlank() && draft.lines.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Change vendor") },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = { close(); viewModel.changeVendor() },
                )
            }
            if (draft.lines.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Discard this order") },
                    leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                    onClick = { close(); viewModel.discard() },
                )
            }
        },
        bandActions = confirmAction,
    ) {
        when {
            state.showingLines -> DraftLinesView(
                lines = draft.lines,
                showPrice = true,
                currency = draft.currencyCode,
                onEdit = { keypadFor = it },
                onRemove = viewModel::removeLine,
                footer = {
                    if (draft.lines.isNotEmpty()) {
                        val priced = draft.lines.all { it.lineAmount != null }
                        sectionLabel(
                            if (priced) "Total ${amountText(draft.lines.sumOf { it.lineAmount ?: 0.0 })} ${draft.currencyCode}".trim()
                            else "Priced by D365FO when created",
                        )
                    }
                },
            )
            state.choosingVariant != null -> VariantChoice(state.choosingVariant!!, viewModel::pickVariant)
            draft.vendorAccount.isBlank() -> VendorList(state, onQuery = viewModel::onVendorQuery, onPick = viewModel::pickVendor)
            else -> {
                val line = lineInHand(draft.lines, state.lastAdd)
                if (line == null) {
                    NextCard(
                        label = "Next",
                        code = "Scan an item",
                        description = "Ordering from ${draft.vendorName.ifBlank { draft.vendorAccount }} into ${draft.warehouseId} · delivery ${draft.deliveryDate}. " +
                            "Each scan adds the item's order minimum.",
                        quantity = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LineCard(
                        label = "Last scanned · ${linesWord(draft.lines.size)} on the order",
                        line = line,
                        location = priceText(line, draft.currencyCode),
                        locationLabel = "Line amount",
                        onQuantityClick = { keypadFor = line },
                    )
                }
            }
        }
    }

    keypadFor?.let { line ->
        QuantityPicker(
            subtitle = listOf(line.itemId, line.variant.label, line.unitId).filter { it.isNotBlank() }.joinToString(" · "),
            hint = "Order quantity",
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
            // Supervisors only, and only online - offline there is no D365FO price to replace.
            extras = if (viewModel.mayOverridePrice && online) {
                {
                    SquareOutlinedButton(onClick = { keypadFor = null; viewModel.editPrice(line) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Set the price instead")
                    }
                }
            } else {
                null
            },
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
    if (typing) {
        CodeEntryDialog(
            title = if (draft.vendorAccount.isBlank()) "Vendor or item" else "Item",
            label = if (draft.vendorAccount.isBlank()) "Vendor account" else "Item number or barcode",
            onSubmit = { typing = false; viewModel.onTyped(it) },
            onDismiss = { typing = false },
        )
    }
    if (showCamera) {
        CameraScannerSheet(onDecoded = { viewModel.onCameraDecoded(it); showCamera = false }, onDismiss = { showCamera = false })
    }
}

/** The vendor list: search, then the last-used vendor first - one tap starts the order. */
@Composable
private fun VendorList(state: PoCreateState, onQuery: (String) -> Unit, onPick: (com.d365fo.mobileops.data.local.entity.VendorEntity) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TapToTypeField(state.vendorQuery, onQuery, "Search by name or account", modifier = Modifier.fillMaxWidth())
        val vendors = vendorsLastUsedFirst(state.vendors, state.lastVendor, state.vendorQuery)
        ListCard(Modifier.weight(1f)) {
            sectionLabel(
                when {
                    state.vendorCount == 0 -> "No vendors on this device yet - connect once"
                    state.vendorQuery.isNotBlank() -> "${vendors.size} found"
                    else -> "${state.vendorCount} vendors" + (state.vendorsSavedAt?.let { " · updated ${Formatters.clockTime(it)}" } ?: "")
                },
            )
            items(vendors, key = { it.accountNum }) { vendor ->
                val last = state.vendorQuery.isBlank() && vendor.accountNum == state.lastVendor?.accountNum
                ListRow(
                    title = vendor.name.ifBlank { vendor.accountNum },
                    subtitles = listOfNotNull(
                        listOf(vendor.accountNum, vendor.currencyCode, vendor.paymentTerms).filter { it.isNotBlank() }.joinToString(" · "),
                        "Last used".takeIf { last },
                    ),
                    status = if (last) StatusKind.Info else null,
                    onClick = { onPick(vendor) },
                )
                HorizontalDivider(color = WarehouseStatusColors.divider)
            }
            if (vendors.isEmpty() && state.vendorQuery.isNotBlank()) {
                item {
                    Text(
                        "No vendor matches \"${state.vendorQuery}\"",
                        style = WarehouseType.body,
                        color = WarehouseStatusColors.inkMuted,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}
