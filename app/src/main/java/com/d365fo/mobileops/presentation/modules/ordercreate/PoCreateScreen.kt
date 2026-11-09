package com.d365fo.mobileops.presentation.modules.ordercreate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.automirrored.filled.ListAlt
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.domain.order.amountText
import com.d365fo.mobileops.domain.order.qtyText
import com.d365fo.mobileops.presentation.common.components.AppCard
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.components.PromptHeroCard
import com.d365fo.mobileops.presentation.common.components.SquareButton
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.util.Formatters
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar

/** PO creation - see [PoCreateViewModel]. */
@OptIn(ExperimentalMaterial3Api::class)
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
    var typing by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val draft = state.draft

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create PO") },
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = WarehouseDimens.ScreenPadding, vertical = 0.dp),
            verticalArrangement = Arrangement.spacedBy(WarehouseDimens.ItemSpacing),
        ) {
            item(key = "tabs") {
                Column {
                    com.d365fo.mobileops.presentation.common.components.StepTrail(
                        names = listOf("Vendor", "Items", "Create"),
                        current = if (draft.vendorAccount.isBlank()) 0 else 1,
                    )
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(4.dp)) {
                        if (state.busy || state.pricing || state.refreshingVendors) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            // Below the scan card on a step that has one - see the item after it in step 2.
            if (draft.vendorAccount.isBlank()) state.error?.let { message -> item { ErrorBanner(message = message, onDismiss = viewModel::dismissError) } }

            state.submittedId?.let { id ->
                val order = orders.firstOrNull { it.id == id }
                if (order != null) {
                    item { com.d365fo.mobileops.presentation.common.components.ListHeader(Icons.Default.Check, "Order saved") }
                    item { CreatedOrderCard(order, actionLabel = confirmLabel(order), onAction = { order.documentNumber?.let(onConfirm) }) }
                    item { SquareOutlinedButton(onClick = viewModel::dismissResult, modifier = Modifier.fillMaxWidth()) { Text("Start another order") } }
                }
            }

            if (draft.vendorAccount.isBlank()) {
                // -- Step 1: vendor ------------------------------------------------------------
                // Keyed: the cards above disappear with the first letter typed, and an unkeyed
                // field would be recomposed at a new index - losing focus and the next letters.
                item(key = "vendor-search") {
                    com.d365fo.mobileops.presentation.common.components.PromptHeroCard(prompt = "Choose vendor") {
                        TapToTypeField(state.vendorQuery, viewModel::onVendorQuery, "Vendor name or account")
                    }
                }
                // The latest orders made here, after the search field (they hide while searching).
                if (orders.isNotEmpty() && state.submittedId == null && state.vendorQuery.isBlank()) {
                    item { com.d365fo.mobileops.presentation.common.components.ListHeader(Icons.Default.Description, "Your orders", orders.size.coerceAtMost(3), trailing = "Newest") }
                    items(orders.take(3), key = { it.id }) { order ->
                        CreatedOrderCard(order, actionLabel = confirmLabel(order), onAction = { order.documentNumber?.let(onConfirm) })
                    }
                }
                item(key = "vendor-count") {
                    com.d365fo.mobileops.presentation.common.components.ListHeader(
                        icon = Icons.Default.Store,
                        title = "Vendors",
                        count = state.vendorCount.takeIf { it > 0 },
                        trailing = if (state.vendorCount == 0) {
                            if (online) "Getting vendors..." else "No vendors yet - connect once."
                        } else if (online) {
                            "Refresh" + (state.vendorsSavedAt?.let { " · ${Formatters.clockTime(it)}" } ?: "")
                        } else {
                            state.vendorsSavedAt?.let { "Updated ${Formatters.clockTime(it)}" }
                        },
                        onTrailing = if (online && state.vendorCount > 0) ({ viewModel.refreshVendors(true) }) else null,
                    )
                }
                items(state.vendors, key = { it.accountNum }) { vendor ->
                    com.d365fo.mobileops.presentation.common.components.ListCard {
                        com.d365fo.mobileops.presentation.common.components.LineRow(
                            title = vendor.name,
                            lines = listOf(listOf(vendor.accountNum, vendor.currencyCode, vendor.paymentTerms).filter { it.isNotBlank() }.joinToString(" · ")),
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
                // -- Step 2: lines -------------------------------------------------------------
                // Scan card first: it stays in one place however the cards below change (feedback, 10 Oct).
                item {
                    PromptHeroCard(
                        prompt = "Scan item",
                        pills = listOf(
                            Icons.Default.Store to draft.vendorName.ifBlank { draft.vendorAccount },
                            Icons.Default.Warehouse to "To ${draft.warehouseId}",
                            Icons.Default.CalendarMonth to "Delivery ${draft.deliveryDate}",
                        ),
                    ) {
                        OrderScanField(onType = { typing = true }, onCamera = { showCamera = true }, enabled = !state.busy)
                        if (draft.lines.isEmpty()) {
                            TextButton(onClick = viewModel::changeVendor) { Text("Change vendor") }
                        }
                    }
                }
                state.error?.let { message -> item { ErrorBanner(message = message, onDismiss = viewModel::dismissError) } }
                if (draft.lines.isNotEmpty()) {
                    item {
                        val priced = draft.lines.all { it.lineAmount != null }
                        com.d365fo.mobileops.presentation.common.components.ListHeader(
                            icon = Icons.AutoMirrored.Filled.ListAlt,
                            title = "Order lines",
                            count = draft.lines.size,
                            trailing = if (priced) {
                                "Total ${amountText(draft.lines.sumOf { it.lineAmount ?: 0.0 })} ${draft.currencyCode}".trim()
                            } else {
                                "Priced by D365FO"
                            },
                        )
                    }
                }
                items(draft.lines, key = { it.id }) { line ->
                    OrderLineCard(line, showPrice = true, currency = draft.currencyCode) { viewModel.editLine(line) }
                }
                item { androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp)) }
            }
        }
        // The dock (Harbour): Discard beside a hold to create and confirm.
        if (draft.vendorAccount.isNotBlank()) {
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
                    enabled = !state.busy && draft.lines.isNotEmpty(),
                    modifier = Modifier.weight(0.62f),
                )
            }
        }
        }
    }

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
