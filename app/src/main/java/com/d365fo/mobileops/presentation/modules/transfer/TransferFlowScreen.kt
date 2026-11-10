package com.d365fo.mobileops.presentation.modules.transfer

import com.d365fo.mobileops.presentation.common.focus.BandKind
import com.d365fo.mobileops.presentation.common.focus.BandState
import com.d365fo.mobileops.presentation.common.focus.FocusCenter
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.Undo
import com.d365fo.mobileops.presentation.common.components.BarcodeFieldIcon
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.QrCode2
import com.d365fo.mobileops.presentation.common.components.bleed
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.d365fo.mobileops.presentation.common.components.SquareTextButton as TextButton
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.domain.model.TransferMode
import com.d365fo.mobileops.domain.model.TransferOrderLine
import com.d365fo.mobileops.domain.model.TransferStep
import com.d365fo.mobileops.domain.model.stepsForTransfer
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.components.HeroEnterBadge
import com.d365fo.mobileops.presentation.common.components.HeroFieldShape
import com.d365fo.mobileops.presentation.common.components.heroFieldColors
import com.d365fo.mobileops.presentation.common.components.TextEntryScreen
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import java.math.BigDecimal

/**
 * Transfer ship and receive, one prompt at a time - back to this flow's original shape after a
 * scan-first, card-and-list redesign didn't hold up in practice, the same reversion applied to
 * PO receiving. [mode] still decides wording, which steps appear and which quantity bounds the
 * entry. Every capture still goes through the exact same [TransferFlowViewModel] and the same
 * one-field-validated-against-D365FO-before-advancing rule this flow has always used - this file
 * is a visual layer, not a rules change.
 *
 * The one thing carried forward from the scan-first attempt: the numeric keypad on the quantity
 * step. See [NumericKeypad].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferFlowScreen(
    mode: TransferMode,
    onBack: () -> Unit,
    onManualEntryRequested: () -> Unit,
    viewModel: TransferFlowViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    var showItemSearch by remember { mutableStateOf(false) }

    LaunchedEffect(mode) { viewModel.setMode(mode) }

    // See the identical guard on ReceivingFlowScreen - applied here to both BackHandler and the
    // top bar's own IconButton onClick, since live tracing ruled out a real KEYCODE_BACK and
    // Compose's own predictive-back callback as the phantom's delivery mechanism, pointing at a
    // phantom touch on the icon's tap target instead.
    val stepBack = {
        if (!viewModel.justScanned()) {
            when {
                // Back from "Scanned so far" closes the list rather than stepping the flow back
                // from under it - see ReceivingFlowScreen's stepBack.
                state.showingCaptured -> viewModel.showCaptured(false)
                state.step == TransferStep.TRANSFER_ORDER -> onBack()
                else -> viewModel.back()
            }
        }
    }
    BackHandler(onBack = stepBack)

    var confirmDiscard by remember { mutableStateOf(false) }
    if (confirmDiscard) {
        com.d365fo.mobileops.presentation.common.components.DiscardScannedDialog(
            lines = state.captured.size,
            orderId = state.capture.transferId,
            onKeep = { confirmDiscard = false },
            onDiscard = {
                confirmDiscard = false
                viewModel.reset()
            },
        )
    }

    // Opens the number pad on reaching the quantity step, once per line - see the identical
    // note on ReceivingFlowScreen.
    var keypadOpenedFor by rememberSaveable { mutableStateOf<String?>(null) }
    // The shared quantity popup (QuantityPicker), shown over this screen - not the full typing screen.
    var showQuantity by remember { mutableStateOf(false) }
    val quantityKey = if (state.step == TransferStep.QUANTITY && !state.busy && state.lineChoices.isEmpty() &&
        !state.showingCaptured
    ) {
        "${state.capture.line?.lineNumber}:${state.captured.size}"
    } else {
        null
    }
    LaunchedEffect(quantityKey) {
        if (quantityKey != null && quantityKey != keypadOpenedFor) {
            while (viewModel.justScanned()) kotlinx.coroutines.delay(150)
            keypadOpenedFor = quantityKey
            showQuantity = true
        }
    }

    if (showQuantity && state.step == TransferStep.QUANTITY) {
        // Closes itself when a scan moves the flow on, or is refused or answered with a notice -
        // otherwise that answer lands behind it, unseen (see the same rule on the entry screen).
        val openedOn = remember { Triple(state.capture.line?.lineNumber, state.error, state.lineNotice?.id) }
        LaunchedEffect(state.step, state.error, state.lineNotice?.id, state.capture.line?.lineNumber) {
            if (state.capture.line?.lineNumber != openedOn.first ||
                (state.error != null && state.error != openedOn.second) ||
                (state.lineNotice != null && state.lineNotice?.id != openedOn.third)
            ) showQuantity = false
        }
        val line = state.capture.line
        com.d365fo.mobileops.presentation.common.components.QuantityPicker(
            subtitle = line?.let { l ->
                listOfNotNull(
                    l.itemId,
                    l.variant.label.takeIf { it.isNotBlank() },
                    l.unitId,
                ).joinToString(" · ")
            },
            hint = hintFor(state),
            confirmLabel = "Confirm",
            validate = viewModel::quantityError,
            // No "all outstanding" button here (feedback) - the hint above says what is left.
            extras = line?.takeIf { it.units.size > 1 }?.let { l ->
                {
                    com.d365fo.mobileops.presentation.common.components.UnitChips(
                        units = l.units,
                        lineUnit = l.unitId,
                        selected = state.entryUnit ?: l.unitId,
                        onSelect = viewModel::selectEntryUnit,
                    )
                }
            },
            onConfirm = { value ->
                showQuantity = false
                viewModel.onManualEntry(value)
            },
            // The scanner's phantom back must not close it - see stepBack.
            onDismiss = { if (!viewModel.justScanned()) showQuantity = false },
        )
    }

    com.d365fo.mobileops.presentation.modules.cyclecounting.opportunity.OpportunityCountPrompt()
    val discrepancy = androidx.hilt.navigation.compose.hiltViewModel<
        com.d365fo.mobileops.presentation.modules.cyclecounting.opportunity.DiscrepancyViewModel,
    >()

    // The discrepancy dialog lives with its action; the trigger is in the tools menu now.
    if (mode == TransferMode.SHIP) {
        com.d365fo.mobileops.presentation.modules.cyclecounting.opportunity.ReportDiscrepancyAction(
            locationId = state.capture.locationId ?: state.capture.line?.locationId,
            itemId = state.capture.line?.itemId,
            showTrigger = false,
            viewModel = discrepancy,
        )
    }
    // Focus design on the existing steps - see ReceivingFlowScreen.
    var menuOpen by remember { mutableStateOf(false) }
    val total = state.order?.outstandingLineCount ?: 0
    val left = state.outstandingLineCount
    val done = if (left != null) (total - left).coerceAtLeast(0) else 0
    val toolsMenu: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = com.d365fo.mobileops.presentation.common.theme.FocusColors.ink)
            }
            androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Camera scan") },
                    leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                    enabled = !state.busy,
                    onClick = { menuOpen = false; showCamera = true },
                    modifier = Modifier.heightIn(min = 56.dp),
                )
                if (state.step == TransferStep.ITEM && state.openLines.isNotEmpty()) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Search items") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        onClick = { menuOpen = false; showItemSearch = true },
                        modifier = Modifier.heightIn(min = 56.dp),
                    )
                }
                if (mode == TransferMode.SHIP && discrepancy.enabled) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Report discrepancy") },
                        leadingIcon = { Icon(Icons.Default.ReportProblem, contentDescription = null) },
                        onClick = { menuOpen = false; discrepancy.open(state.capture.locationId ?: state.capture.line?.locationId, state.capture.line?.itemId) },
                        modifier = Modifier.heightIn(min = 56.dp),
                    )
                }
                if (state.capture.transferId != null) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Start over") },
                        leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            // Asks first when anything is scanned, and ignores the scanner's phantom tap.
                            if (!viewModel.justScanned()) {
                                if (state.captured.isEmpty()) viewModel.reset() else confirmDiscard = true
                            }
                        },
                        modifier = Modifier.heightIn(min = 56.dp),
                    )
                }
            }
        }
    }
    com.d365fo.mobileops.presentation.common.focus.FocusScreen(
        title = when {
            state.showingCaptured -> "Transfer lines"
            mode == TransferMode.SHIP -> "Ship transfer"
            else -> "Receive transfer"
        },
        onBack = stepBack,
        counter = if (state.order != null && total > 0) "$done / $total" else null,
        progress = if (state.order != null && total > 0) done.toFloat() / total else null,
        band = if (state.showingCaptured) null else transferBand(state, mode),
        remaining = if (!state.showingCaptured && state.step == TransferStep.ITEM && state.captured.isNotEmpty() && left != null) {
            "$left ${if (left == 1) "line" else "lines"} left"
        } else {
            null
        },
        scanPill = if (state.showingCaptured || state.lineChoices.isNotEmpty()) null else {
            { com.d365fo.mobileops.presentation.common.focus.ScanPillField(enabled = !state.busy) }
        },
        topBarTrailing = if (state.showingCaptured) null else toolsMenu,
        actions = transferActions(state, mode, onLines = { viewModel.showCaptured(true) }, onPost = viewModel::postTransfer, onUndo = stepBack, onSkip = viewModel::skipStep),
    ) {
        if (state.busy) {
            androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
        }
        when {
            state.showingCaptured -> Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                CapturedLines(
                    captured = state.captured,
                    busy = state.busy,
                    onRemove = viewModel::removeCaptured,
                    onAdjustQuantity = viewModel::adjustCapturedQuantity,
                    onClose = { viewModel.showCaptured(false) },
                    onPost = viewModel::postTransfer,
                    shipping = mode == TransferMode.SHIP,
                )
            }
            state.lineChoices.isNotEmpty() -> Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                LineChoice(state.lineChoices, viewModel::chooseLine)
            }
            else -> TransferCenter(state, mode, onQuantityTap = { showQuantity = true })
        }
    }
    if (showCamera) {
        CameraScannerSheet(
            onDecoded = {
                viewModel.onCameraDecoded(it)
                showCamera = false
            },
            onDismiss = { showCamera = false },
        )
    }

    if (showItemSearch) {
        ItemSearchSheet(
            lines = state.openLines,
            onPick = { line ->
                showItemSearch = false
                viewModel.pickSearchedLine(line.itemId, line.lineNumber)
            },
            onDismiss = { showItemSearch = false },
        )
    }
}

/**
 * Lets the operator find a line by typing part of its item name or ID instead of scanning -
 * useful when a barcode is damaged, or the operator is staging picks from a printed list rather
 * than working barcode-first. Filters [lines] client-side since they are already in memory (see
 * the call site's [TransferFlowState.openLines] check) rather than round-tripping to D365FO for
 * what is, on any order small enough to have its lines inline, a handful of rows.
 */
@Composable
private fun ItemSearchSheet(
    lines: List<TransferOrderLine>,
    onPick: (TransferOrderLine) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(query, lines) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            lines
        } else {
            // The variant too: "Red" or "C2" finds the one line of several for the same item.
            lines.filter {
                it.itemId.contains(needle, ignoreCase = true) ||
                    it.itemName.contains(needle, ignoreCase = true) ||
                    it.variant.matches(needle)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
            .clickable(onClick = onDismiss),
    ) {
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = 520.dp)
                // Swallow taps so they don't fall through to the scrim's dismiss-on-click.
                .clickable(enabled = false) {},
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Find an item",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Item name or ID") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (results.isEmpty()) {
                    Text(
                        text = "No outstanding line matches \"$query\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(results) { line ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(line) }
                                    .padding(vertical = 10.dp),
                            ) {
                                Column {
                                    Text(text = line.itemId, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = line.itemName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    // Which variant: lines for the same item otherwise read identically here.
                                    line.variant.label.takeIf { it.isNotBlank() }?.let { variant ->
                                        Text(
                                            text = variant,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LineChoice(lines: List<TransferOrderLine>, onPick: (TransferOrderLine) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "That item is on ${lines.size} lines. Which one?",
            style = MaterialTheme.typography.titleMedium,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(lines) { line ->
                Card(
                    onClick = { onPick(line) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Line ${plain(BigDecimal.valueOf(line.lineNumber))} · " +
                                "${com.d365fo.mobileops.util.Formatters.qtyUnit(line.remainingQuantity, line.unitId)} outstanding",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = line.itemName.ifBlank { line.itemId } +
                                line.variant.label.takeIf { it.isNotBlank() }?.let { " \u00B7 $it" }.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The "scanned so far" menu - view every line captured this visit, and remove one if it is
 * wrong, before posting the whole transfer. See the identical one on PO receiving.
 */
@Composable
private fun CapturedLines(
    captured: List<CapturedTransferLine>,
    busy: Boolean,
    onRemove: (Int) -> Unit,
    onAdjustQuantity: (Int, Int) -> Unit,
    onClose: () -> Unit,
    onPost: () -> Unit,
    /**
     * Transfer - Ship is picking, then confirming it: each scan is picked in D365FO, and
     * "Confirm picking" posts the shipment of what was picked (user, 8 Oct 2026).
     */
    shipping: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        com.d365fo.mobileops.presentation.common.components.ListHeader(
            icon = Icons.AutoMirrored.Filled.ListAlt,
            title = if (shipping) "Picked so far" else "Scanned so far",
            count = captured.size,
        )
        val online = com.d365fo.mobileops.presentation.common.components.LocalSyncStatus.current?.fullyOnline != false
        Text(
            text = if (shipping && online) {
                "Picked as you scan. Confirm to ship - can't be undone."
            } else if (shipping) {
                // Offline nothing is picked when scanned - the shipment posting picks it.
                "Offline - confirm to ship; sent when back."
            } else {
                "Check the lines - posting can't be undone."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Most recent first: the line worth checking or undoing is the one just scanned.
            itemsIndexed(captured.reversed()) { reversedIndex, entry ->
                val index = captured.lastIndex - reversedIndex
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = entry.itemId, style = MaterialTheme.typography.titleMedium)
                            if (entry.itemName.isNotBlank()) {
                                Text(
                                    text = entry.itemName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // A serial-tracked line moves one serial per unit, so the count here
                            // is fixed by the serial list, not something a +/- can safely nudge -
                            // wrong on the fly and the serial list itself would need re-scanning
                            // to match. Delete and rescan is the correction path for those.
                            val serialTracked = entry.serialIds.isNotEmpty()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier.padding(top = 2.dp),
                            ) {
                                IconButton(
                                    onClick = { onAdjustQuantity(index, -1) },
                                    enabled = !busy && !serialTracked && entry.quantity > BigDecimal.ONE,
                                ) {
                                    Icon(Icons.Default.Remove, contentDescription = "Decrease quantity")
                                }
                                Text(
                                    text = "${com.d365fo.mobileops.util.Formatters.qtyUnit(entry.quantity, entry.unitSymbol)}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                IconButton(
                                    onClick = { onAdjustQuantity(index, 1) },
                                    enabled = !busy && !serialTracked &&
                                        (entry.maxQuantity == null || entry.quantity < entry.maxQuantity),
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Increase quantity")
                                }
                            }
                            val detail = listOfNotNull(
                                // The variant first: on a product master it is what tells two
                                // lines of one item apart (Dm001 on TO-14 showed only "AAC").
                                entry.variantLabel.takeIf { it.isNotBlank() },
                                entry.locationId?.takeIf { it.isNotBlank() },
                                entry.batchId?.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                                // The serial itself, like the batch - "1 serial" did not say which.
                                // Many (one per unit) are counted, the first named.
                                when (entry.serialIds.size) {
                                    0 -> null
                                    1 -> "Serial ${entry.serialIds[0]}"
                                    else -> "${entry.serialIds.size} serials (${entry.serialIds[0]}, …)"
                                },
                            ).joinToString(" · ")
                            if (detail.isNotBlank()) {
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (shipping) {
                                Text(
                                    text = when {
                                        entry.pickedEarlier -> "Picked on another device"
                                        entry.picked -> "Picked"
                                        else -> "Picked on confirm"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (entry.picked && !entry.pickedEarlier) WarehouseStatusColors.green else WarehouseStatusColors.amber,
                                )
                            }
                        }
                        IconButton(onClick = { onRemove(index) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove")
                        }
                    }
                }
            }
        }

        // Post is the primary action here, for the same reason as the receiving screen: this is
        // the last thing an operator sees before the transfer leaves the device.
        // Side by side, the same order as every dialog: the action on the left, going back on
        // the right (feedback, 10 Oct).
        com.d365fo.mobileops.presentation.common.components.FlowDock(modifier = Modifier.bleed(16.dp)) {
            com.d365fo.mobileops.presentation.common.components.DockOutlinedButton(
                onClick = onClose,
                enabled = !busy,
                icon = com.d365fo.mobileops.presentation.common.components.BarcodeGlyph,
                text = "Scan more",
                modifier = Modifier.weight(0.4f),
            )
            com.d365fo.mobileops.presentation.common.components.HoldButton(
                label = when {
                    busy && shipping -> "Shipping…"
                    busy -> "Posting…"
                    shipping -> "Hold to ship"
                    else -> "Hold to receive"
                },
                onConfirm = onPost,
                enabled = !busy && captured.isNotEmpty(),
                modifier = Modifier.weight(0.6f),
            )
        }
    }
}

/**
 * The trail so far, growing one field per step rather than stopping at quantity - see the
 * identical note on PO receiving's own CaptureSummary. Item name alongside the code for the
 * same reason: a code alone confirms nothing to someone who does not have the order sheet
 * memorised. Labelled rows, not one dot-separated line - "Item" and "Batch" read at a glance.
 */
@Composable
private fun CaptureSummary(state: TransferFlowState) {
    val capture = state.capture
    val shipping = capture.mode == TransferMode.SHIP
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Which way the stock is going, as this card's own heading rather than folded into
            // the transfer order value - the TopAppBar already says "Ship"/"Receive" once, this
            // said it a second time in brackets on every field read.
            Text(
                text = if (shipping) "Shipping" else "Receiving",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Field("Transfer order", capture.transferId)
            Field("Item", capture.line?.let { "${it.itemId} — ${it.itemName}" })
            // The variant on its own row: the item name alone ("HS test variant item 1") does
            // not say which colour or size the worker should be holding.
            Field("Variant", capture.line?.variant?.label)
            Field(
                "Quantity",
                capture.quantity?.let { com.d365fo.mobileops.util.Formatters.qtyUnit(it, capture.unitSymbol) },
            )
            Field("Location", capture.locationId)
            Field("Batch", capture.batchId)
            if (capture.serialIds.isNotEmpty()) {
                val count = capture.serialIds.size
                Field("Serials", "$count ${if (count == 1) "serial" else "serials"}")
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.6f),
        )
    }
}

/** The result band - see ReceivingFlowScreen's receivingBand. */
private fun transferBand(state: TransferFlowState, mode: TransferMode): BandState? {
    state.error?.let { return com.d365fo.mobileops.presentation.modules.stepflow.bandOf(BandKind.Bad, it) }
    state.postNotice?.let { n ->
        val kind = when (n.kind) {
            PostNoticeKind.SENT -> BandKind.Ok
            PostNoticeKind.QUEUED -> BandKind.Warn
            PostNoticeKind.REFUSED -> BandKind.Bad
        }
        return com.d365fo.mobileops.presentation.modules.stepflow.bandOf(kind, n.message)
    }
    state.lineNotice?.let { return com.d365fo.mobileops.presentation.modules.stepflow.bandOf(if (it.hint) BandKind.Warn else BandKind.Ok, it.text) }
    val order = state.order
    return when {
        state.step == TransferStep.TRANSFER_ORDER -> BandState(BandKind.Info, "Ready", "No transfer open")
        state.step == TransferStep.ITEM && state.captured.isEmpty() && order != null -> BandState(
            BandKind.Info,
            "Transfer found",
            listOfNotNull(
                order.transferId,
                listOfNotNull(order.fromWarehouseId, order.toWarehouseId).takeIf { it.size == 2 }?.joinToString(" → "),
            ).joinToString(" · "),
        )
        else -> null
    }
}

/** Lines + Hold to ship/receive once something is scanned; Undo (+ Skip) on a line's own steps. */
private fun transferActions(
    state: TransferFlowState,
    mode: TransferMode,
    onLines: () -> Unit,
    onPost: () -> Unit,
    onUndo: () -> Unit,
    onSkip: () -> Unit,
): (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? {
    if (state.showingCaptured || state.lineChoices.isNotEmpty()) return null
    return when (state.step) {
        TransferStep.TRANSFER_ORDER -> null
        TransferStep.ITEM -> if (state.captured.isEmpty()) null else {
            {
                com.d365fo.mobileops.presentation.common.focus.FocusButton(
                    "Lines", onClick = onLines, icon = Icons.AutoMirrored.Filled.ListAlt,
                    enabled = !state.busy, modifier = Modifier.weight(1f),
                )
                com.d365fo.mobileops.presentation.common.focus.HoldToConfirmButton(
                    if (mode == TransferMode.SHIP) "Hold to ship" else "Hold to receive",
                    onConfirm = onPost, enabled = !state.busy, modifier = Modifier.weight(1f),
                )
            }
        }
        else -> {
            {
                com.d365fo.mobileops.presentation.common.focus.FocusButton(
                    "Undo", onClick = onUndo, icon = Icons.AutoMirrored.Filled.Undo,
                    enabled = !state.busy, modifier = Modifier.weight(1f),
                )
                if (com.d365fo.mobileops.domain.model.canSkipTransferStep(state.step, state.capture)) {
                    com.d365fo.mobileops.presentation.common.focus.FocusButton(
                        "Skip", onClick = onSkip, primary = true, enabled = !state.busy, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** The centre - see ReceivingFlowScreen's ReceivingCenter. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TransferCenter(state: TransferFlowState, mode: TransferMode, onQuantityTap: () -> Unit) {
    val c = state.capture
    val ship = mode == TransferMode.SHIP
    when (state.step) {
        TransferStep.TRANSFER_ORDER -> FocusCenter(caption = "Transfer", code = "Not open")
        TransferStep.ITEM -> {
            val last = state.captured.lastOrNull()
            if (last == null) {
                val lines = state.outstandingLineCount ?: state.order?.outstandingLineCount ?: 0
                val units = state.openLines.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO) { sum, l -> sum + l.remainingQuantity }
                FocusCenter(
                    caption = if (ship) "To pick" else "To receive",
                    code = when (lines) {
                        0 -> "Nothing left"
                        1 -> "1 line"
                        else -> "$lines lines"
                    },
                    description = units?.takeIf { lines > 0 }?.let { "${plain(it)} units" },
                )
            } else {
                FocusCenter(
                    caption = if (ship) "Last picked" else "Last scanned",
                    code = last.itemId,
                    description = listOf(last.itemName, last.variantLabel).filter { it.isNotBlank() }.joinToString(" · "),
                    quantity = plain(last.quantity),
                    unitLine = listOfNotNull(
                        last.unitSymbol.takeIf { it.isNotBlank() },
                        last.batchId?.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                        last.locationId?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                )
            }
        }
        else -> {
            val line = c.line
            FocusCenter(
                caption = when (state.step) {
                    TransferStep.QUANTITY -> "Quantity"
                    TransferStep.LOCATION -> "Location"
                    TransferStep.BATCH -> "Batch"
                    TransferStep.SERIAL -> "Serial"
                    else -> titleFor(state.step, mode)
                },
                code = line?.itemId.orEmpty(),
                description = listOfNotNull(
                    line?.itemName?.takeIf { it.isNotBlank() && !it.equals(line.itemId, ignoreCase = true) },
                    line?.variant?.label?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").takeIf { it.isNotBlank() },
                quantity = c.quantity?.let { plain(it) } ?: "–".takeIf { state.step == TransferStep.QUANTITY },
                unitLine = when (state.step) {
                    TransferStep.QUANTITY -> line?.let { l ->
                        "${l.unitId} · ${plain(state.suggestedQuantity ?: l.remainingQuantity)} left of ${plain(l.transferQuantity)}"
                    }
                    TransferStep.SERIAL -> listOfNotNull(c.unitSymbol, "${c.serialsOutstanding} serial${if (c.serialsOutstanding == 1) "" else "s"} left").joinToString(" · ")
                    else -> listOfNotNull(c.unitSymbol, c.batchId?.let { "Batch $it" }, c.locationId).joinToString(" · ")
                },
                onQuantityTap = if (state.step == TransferStep.QUANTITY && !state.busy) onQuantityTap else null,
            )
        }
    }
}
private fun titleFor(step: TransferStep, mode: TransferMode): String = when (step) {
    TransferStep.TRANSFER_ORDER -> if (mode == TransferMode.SHIP) "Ship" else "Receive"
    TransferStep.ITEM -> "Item"
    TransferStep.QUANTITY -> "Quantity"
    TransferStep.LOCATION -> "Location"
    TransferStep.BATCH -> "Batch"
    TransferStep.SERIAL -> "Serial"
    TransferStep.CONFIRM -> "Confirm"
}

private fun promptFor(state: TransferFlowState): String = when (state.step) {
    TransferStep.TRANSFER_ORDER -> "Scan transfer order"
    TransferStep.ITEM -> "Scan item"
    TransferStep.QUANTITY -> "Enter quantity"
    TransferStep.LOCATION -> "Scan location"
    TransferStep.BATCH -> "Scan batch"
    TransferStep.SERIAL -> state.capture.serialsOutstanding
        .takeIf { it > 1 }
        ?.let { "Scan serial ($it to go)" }
        ?: "Scan serial"
    TransferStep.CONFIRM -> "Confirm"
}

private fun hintFor(state: TransferFlowState): String? = when (state.step) {
    // No information lines under the scan card (feedback: "these kind of info not required").
    TransferStep.TRANSFER_ORDER -> null

    // Nothing under "Scan item" (feedback f77ebd0d): no "n lines outstanding · n scanned" or
    // "All lines scanned" - the list badge in the top bar already counts what is scanned.
    TransferStep.ITEM -> null

    // Receiving shows nothing under "Enter quantity" (feedback 65173f62): the "Receive all
    // outstanding (n)" button already gives the number, and the entry unit shows on its chip.
    TransferStep.QUANTITY -> state.capture.line?.takeIf { state.capture.mode == TransferMode.SHIP }?.let { line ->
        "${com.d365fo.mobileops.util.Formatters.qtyUnit(line.remainingQuantity, line.unitId)} still to ship" +
            (state.entryUnit?.let { unit -> " · entering in $unit" } ?: "")
    }

    TransferStep.LOCATION -> null

    // Receiving: which batch came on the truck is already known from the shipment - it was
    // only said after a wrong scan ("… Shipped: E0927 (1)"), so it is said up front.
    // Shipping: the batch the order was created for (TO creation's "which stock?") - asked for
    // again here with nothing to say which it was (TO-34, 10 Oct).
    TransferStep.BATCH -> state.capture.line?.let { line ->
        when {
            state.capture.mode == TransferMode.RECEIVE && line.shippedBatches.isNotEmpty() ->
                "Shipped: " + line.shippedBatches.joinToString { "${it.batchId} (${plain(it.quantity)})" }
            state.capture.mode == TransferMode.SHIP && line.lineBatchId != null ->
                "Ordered from batch ${line.lineBatchId}"
            else -> null
        }
    }

    TransferStep.SERIAL -> state.capture.quantity?.let {
        com.d365fo.mobileops.presentation.modules.stepflow.serialPrompt(
            state.capture.line?.rules?.serialPerUnit != false, state.capture.serialsOutstanding,
        )
    }

    else -> null
}

private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

/**
 * The dedicated typing screen for whichever step is current - see the identical
 * [com.d365fo.mobileops.presentation.modules.stepflow.ReceivingEntryScreen] and its doc comment
 * on why this fetches the same [TransferFlowViewModel] instance as the flow screen underneath it
 * rather than creating a new one.
 */
@Composable
fun TransferEntryScreen(
    viewModel: TransferFlowViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The hardware scanner keeps delivering into the flow's ViewModel while this pad is open, so
    // a scan's refusal or its new step lands on the screen underneath, unseen: a PO scanned at
    // the quantity pad said '"BSP-PO-000161706" is not a number' behind it (10 Oct). Close the
    // pad when that happens - once, as onSubmit already pops it too.
    var closed by remember { mutableStateOf(false) }
    val close = { if (!closed) { closed = true; onBack() } }
    // Quick notices too ("… is the batch - enter the quantity first"): they last a moment and
    // were shown only behind the pad.
    val openedOn = remember { Triple(state.step, state.error, state.lineNotice?.id) }
    LaunchedEffect(state.step, state.error, state.lineNotice?.id) {
        if (state.step != openedOn.first ||
            (state.error != null && state.error != openedOn.second) ||
            (state.lineNotice != null && state.lineNotice?.id != openedOn.third)
        ) close()
    }
    TextEntryScreen(
        title = promptFor(state),
        hint = hintFor(state),
        keyboardType = if (state.step == TransferStep.QUANTITY) KeyboardType.Decimal else KeyboardType.Text,
        showNumericKeypad = state.step == TransferStep.QUANTITY,
        quickFillLabel = state.suggestedQuantity?.takeIf { state.step == TransferStep.QUANTITY }?.let {
            (if (state.capture.mode == TransferMode.SHIP) "Ship" else "Receive") + " all outstanding (${plain(it)})"
        },
        quickFillValue = state.suggestedQuantity?.takeIf { state.step == TransferStep.QUANTITY }?.let { plain(it) },
        validate = viewModel::quantityError,
        keypadExtras = state.capture.line?.takeIf { state.step == TransferStep.QUANTITY && it.units.size > 1 }?.let { line ->
            {
                com.d365fo.mobileops.presentation.common.components.UnitChips(
                    units = line.units,
                    lineUnit = line.unitId,
                    selected = state.entryUnit ?: line.unitId,
                    onSelect = viewModel::selectEntryUnit,
                )
            }
        },
        // The scanner's phantom back must not close the pad - see TransferFlowScreen.stepBack.
        onBack = { if (!viewModel.justScanned()) close() },
        onSubmit = { value ->
            viewModel.onManualEntry(value)
            close()
        },
    )
}

