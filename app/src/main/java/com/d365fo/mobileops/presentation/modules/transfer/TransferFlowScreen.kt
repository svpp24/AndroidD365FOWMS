package com.d365fo.mobileops.presentation.modules.transfer

import com.d365fo.mobileops.presentation.common.focus.FocusTask
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.CalendarMonth
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
import androidx.compose.material.icons.filled.CalendarMonth
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
    var keypadOpenedFor by rememberSaveable { mutableStateOf<String?>(null) }
    // Undo from a later step (batch, bin, serial) back to Quantity opens the pad again with the
    // quantity entered - see the identical note on ReceivingFlowScreen (user, 17 Nov 2026).
    var undoQuantity by remember { mutableStateOf<Pair<String, String>?>(null) }
    val stepBack = {
        if (!viewModel.justScanned() && !state.showingCaptured && state.step != TransferStep.QUANTITY) {
            state.capture.quantity?.let { q ->
                undoQuantity = "${state.capture.line?.lineNumber}:${state.captured.size}" to plain(q)
                keypadOpenedFor = null
            }
        }
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
    // The shared quantity popup (QuantityPicker), shown over this screen - not the full typing screen.
    var showQuantity by remember { mutableStateOf(false) }
    val quantityKey = if (state.step == TransferStep.QUANTITY && !state.busy && state.lineChoices.isEmpty() &&
        !state.showingCaptured
    ) {
        "${state.capture.line?.lineNumber}:${state.captured.size}"
    } else {
        null
    }
    val reopenWith = undoQuantity?.takeIf { it.first == quantityKey }?.second ?: ""
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
                    l.itemName.takeIf { it.isNotBlank() && !it.equals(l.itemId, true) },
                    l.variant.label.takeIf { it.isNotBlank() },
                    l.unitId,
                ).joinToString(" · ")
            },
            hint = hintFor(state),
            confirmLabel = "Confirm",
            initial = reopenWith,
            replaceInitial = reopenWith.isNotEmpty(),
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
                undoQuantity = null
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
    var showTodays by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
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
            { com.d365fo.mobileops.presentation.common.focus.ScanPillField(label = transferScanLabel(state.step), enabled = !state.busy) }
        },
        topBarTrailing = if (state.showingCaptured) null else toolsMenu,
        sidePane = if (state.showingCaptured) null else { {
            com.d365fo.mobileops.presentation.common.focus.SideLines(
                caption = "Scanned so far · ${state.captured.size}",
                lines = state.captured.asReversed().map {
                    com.d365fo.mobileops.presentation.common.focus.SideLine(it.itemId, it.itemName, "${it.quantity.stripTrailingZeros().toPlainString()} ${it.unitSymbol}".trim())
                },
                empty = "Nothing scanned yet.",
            )
        } },
        actions = transferActions(
            state, mode, onLines = { viewModel.showCaptured(true) }, onPost = viewModel::postTransfer, onUndo = stepBack, onSkip = viewModel::skipStep,
            showTodays = showTodays, onToggleTodays = { showTodays = !showTodays },
            onItems = { showItemSearch = true },
        ),
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
                    onSetQuantity = viewModel::setCapturedQuantity,
                    onClose = { viewModel.showCaptured(false) },
                    onPost = viewModel::postTransfer,
                    shipping = mode == TransferMode.SHIP,
                )
            }
            state.lineChoices.isNotEmpty() -> Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                LineChoice(state.lineChoices, viewModel::chooseLine)
            }
            // Shipping asks for a batch: the ones in stock, earliest expiry first (16 Nov 2026).
            state.step == TransferStep.BATCH && mode == TransferMode.SHIP && state.batchChoices.isNotEmpty() ->
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    com.d365fo.mobileops.presentation.common.focus.ListTaskBox("Scan or choose the batch", androidx.compose.material.icons.Icons.Outlined.QrCodeScanner)
                    com.d365fo.mobileops.presentation.common.focus.FocusCaption(
                        "At ${state.capture.fromWarehouseId.orEmpty()} · earliest expiry first",
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp),
                    )
                    val today = java.time.LocalDate.now()
                    val ordered = state.capture.line?.lineBatchId
                    state.batchChoices.forEach { b ->
                        val expiry = b.expiry?.let { d ->
                            (if (d.isBefore(today)) "Expired " else "Expires ") +
                                d.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH))
                        } ?: "No expiry date"
                        com.d365fo.mobileops.presentation.common.focus.FocusListRow(
                            title = b.batchId,
                            titleIsCode = true,
                            subtitle = listOfNotNull(
                                expiry,
                                java.math.BigDecimal.valueOf(b.available).stripTrailingZeros().toPlainString() + " available",
                                if (b.batchId == ordered) "ordered" else null,
                            ).joinToString(" · "),
                            chevron = true,
                            onClick = { if (!state.busy) viewModel.chooseBatch(b.batchId) },
                        )
                    }
                }
            state.step == TransferStep.TRANSFER_ORDER && showTodays && state.todays.isNotEmpty() ->
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    state.todays.forEach { t ->
                        com.d365fo.mobileops.presentation.common.focus.FocusListRow(
                            title = t.transferId,
                            titleIsCode = true,
                            subtitle = (if (mode == TransferMode.SHIP) "To ${t.toWarehouseId}" else "From ${t.fromWarehouseId}") +
                                " · " + (if (t.lineCount == 1) "1 line" else "${t.lineCount} lines"),
                            chevron = true,
                            onClick = { if (!state.busy) { showTodays = false; viewModel.pickTodays(t.transferId) } },
                        )
                    }
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
            // What is left after what this session already picked or scanned (18 Nov 2026).
            scanned = state.captured.groupBy { it.lineNumber }.mapValues { (_, l) -> l.fold(BigDecimal.ZERO) { s, c -> s + c.quantity } },
            onPick = { line ->
                showItemSearch = false
                viewModel.pickSearchedLine(line.itemId, line.lineNumber)
            },
            onDismiss = { showItemSearch = false },
        )
    }
}

/** The transfer's open lines as a page (user, 17 Nov 2026) - see PickItemPage. */
@Composable
private fun ItemSearchSheet(
    lines: List<TransferOrderLine>,
    scanned: Map<Double, BigDecimal> = emptyMap(),
    onPick: (TransferOrderLine) -> Unit,
    onDismiss: () -> Unit,
) {
    val byKey = lines.associateBy { "${it.lineNumber}" }
    com.d365fo.mobileops.presentation.common.focus.PickItemPage(
        title = "Transfer items",
        rows = lines.map { l ->
            com.d365fo.mobileops.presentation.common.focus.PickRow(
                key = "${l.lineNumber}",
                itemId = l.itemId,
                name = l.itemName,
                variant = l.variant.label,
                figure = "${plain((l.remainingQuantity - (scanned[l.lineNumber] ?: BigDecimal.ZERO)).max(BigDecimal.ZERO))} ${l.unitId}".trim(),
                extraMatch = { q -> l.variant.matches(q) },
            )
        },
        onPick = { row -> byKey[row.key]?.let(onPick) },
        onClose = onDismiss,
    )
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
    onSetQuantity: (Int, BigDecimal) -> Unit = { _, _ -> },
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
        // "Check the lines" as the task box; the bin and the - / + under each line (17 Nov 2026).
        com.d365fo.mobileops.presentation.common.focus.FocusTaskBox(
            com.d365fo.mobileops.presentation.common.focus.FocusTask("Check the lines", icon = Icons.AutoMirrored.Filled.ListAlt),
        )
        // Offline nothing is picked when scanned - the shipment posting picks it.
        val online = com.d365fo.mobileops.presentation.common.components.LocalSyncStatus.current?.fullyOnline != false
        if (shipping && !online) {
            Text("Offline - confirm to ship; sent when back.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        var typing by remember { mutableStateOf<Int?>(null) }
        typing?.let { index ->
            captured.getOrNull(index)?.let { entry ->
                com.d365fo.mobileops.presentation.common.components.QuantityPicker(
                    subtitle = listOf(entry.itemId, entry.itemName, entry.unitSymbol).filter { it.isNotBlank() }.joinToString(" · "),
                    hint = entry.maxQuantity?.let { "Up to ${plain(it)} ${entry.unitSymbol}".trim() },
                    initial = plain(entry.quantity),
                    replaceInitial = true,
                    allowDecimal = true,
                    validate = { text ->
                        val v = text.toBigDecimalOrNull()
                        when {
                            v == null -> "\"$text\" is not a number"
                            v <= BigDecimal.ZERO -> "Enter an amount above zero - or remove the line"
                            entry.maxQuantity != null && v > entry.maxQuantity -> "Up to ${plain(entry.maxQuantity)} on this line"
                            else -> null
                        }
                    },
                    confirmLabel = "Save",
                    onConfirm = { text -> typing = null; text.toBigDecimalOrNull()?.let { onSetQuantity(index, it) } },
                    onDismiss = { typing = null },
                )
            }
        }
        LazyColumn(modifier = Modifier.weight(1f).bleed(16.dp)) {
            // Most recent first: the line worth checking or undoing is the one just scanned.
            itemsIndexed(captured.reversed()) { reversedIndex, entry ->
                val index = captured.lastIndex - reversedIndex
                // A serial-tracked line moves one serial per unit: delete and rescan instead.
                val serialTracked = entry.serialIds.isNotEmpty()
                com.d365fo.mobileops.presentation.common.focus.FocusLineCard(
                    code = entry.itemId,
                    name = entry.itemName,
                    details = listOfNotNull(
                        entry.variantLabel.takeIf { it.isNotBlank() },
                        listOfNotNull(
                            entry.locationId?.takeIf { it.isNotBlank() },
                            entry.batchId?.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                        ).joinToString(" · ").takeIf { it.isNotBlank() },
                        when (entry.serialIds.size) {
                            0 -> null
                            1 -> "Serial ${entry.serialIds[0]}"
                            else -> "${entry.serialIds.size} serials (${entry.serialIds[0]}, …)"
                        },
                    ),
                    quantity = plain(entry.quantity),
                    unit = entry.unitSymbol,
                    canMinus = !busy && !serialTracked && entry.quantity > BigDecimal.ONE,
                    canPlus = !busy && !serialTracked && (entry.maxQuantity == null || entry.quantity < entry.maxQuantity),
                    onMinus = { onAdjustQuantity(index, -1) },
                    onPlus = { onAdjustQuantity(index, 1) },
                    onQuantityTap = if (busy || serialTracked) null else ({ typing = index }),
                    onRemove = { onRemove(index) },
                    removeEnabled = !busy,
                    status = if (!shipping) null else when {
                        entry.pickedEarlier -> "Picked on another device"
                        entry.picked -> "Picked"
                        else -> "Picked on confirm"
                    },
                )
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
            // Sent: the "Last shipped" card says it (user, 17 Nov 2026).
            PostNoticeKind.SENT -> null
            PostNoticeKind.QUEUED -> BandKind.Warn
            PostNoticeKind.REFUSED -> BandKind.Bad
        }
        if (kind != null) return com.d365fo.mobileops.presentation.modules.stepflow.bandOf(kind, n.message)
    }
    // Only what needs attention: "Transfer found" and "Picked 10 x ..." only repeated the card.
    state.lineNotice?.takeIf { it.hint }?.let { return com.d365fo.mobileops.presentation.modules.stepflow.bandOf(BandKind.Warn, it.text) }
    return null
}

/** Lines + Hold to ship/receive once something is scanned; Undo (+ Skip) on a line's own steps. */
private fun transferActions(
    state: TransferFlowState,
    mode: TransferMode,
    onLines: () -> Unit,
    onPost: () -> Unit,
    onUndo: () -> Unit,
    onSkip: () -> Unit,
    showTodays: Boolean = false,
    onToggleTodays: () -> Unit = {},
    onItems: () -> Unit = {},
): (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? {
    if (state.showingCaptured || state.lineChoices.isNotEmpty()) return null
    return when (state.step) {
        // Today's transfers, as PO receiving's Today's POs (user, 16 Nov 2026).
        TransferStep.TRANSFER_ORDER -> if (state.todays.isEmpty()) null else {
            {
                com.d365fo.mobileops.presentation.common.focus.FocusButton(
                    if (showTodays) "Close list" else "Today's transfers",
                    onClick = onToggleTodays,
                    icon = Icons.Default.CalendarMonth,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // The transfer's items under the scan bar once it is open, as PO items (user, 16 Nov 2026).
        TransferStep.ITEM -> if (state.captured.isEmpty()) {
            if (state.openLines.isEmpty()) null else {
                {
                    com.d365fo.mobileops.presentation.common.focus.FocusButton(
                        "Transfer items", onClick = onItems, icon = Icons.Default.Search,
                        enabled = !state.busy, modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            {
                val three = state.openLines.isNotEmpty()
                if (three) {
                    com.d365fo.mobileops.presentation.common.focus.FocusButton(
                        "Items", onClick = onItems, icon = Icons.Default.Search,
                        enabled = !state.busy, compact = true, modifier = Modifier.weight(0.8f),
                    )
                }
                com.d365fo.mobileops.presentation.common.focus.FocusButton(
                    "Lines", onClick = onLines, icon = Icons.AutoMirrored.Filled.ListAlt,
                    enabled = !state.busy, compact = three, modifier = Modifier.weight(if (three) 0.8f else 1f),
                )
                com.d365fo.mobileops.presentation.common.focus.HoldToConfirmButton(
                    if (mode == TransferMode.SHIP) "Hold to ship" else "Hold to receive",
                    onConfirm = onPost, enabled = !state.busy, compact = three, modifier = Modifier.weight(if (three) 1.4f else 1f),
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
        TransferStep.TRANSFER_ORDER -> {
            val sent = state.postNotice?.takeIf { it.kind == PostNoticeKind.SENT }
            if (sent != null) {
                // The last post as a card, not a green bar (user, 17 Nov 2026).
                val parts = sent.message.split(" · ", limit = 2)
                FocusCenter(
                    task = transferTask(state, mode),
                    caption = if (ship) "Last shipped" else "Last received",
                    code = parts[0],
                    description = parts.getOrNull(1),
                    unitLine = "${state.todays.size} ${if (ship) "to ship" else "to receive"} today",
                )
            } else {
                FocusCenter(
                    task = transferTask(state, mode),
                    caption = if (ship) "Transfers to ship today" else "Transfers to receive today",
                    code = state.todays.size.toString(),
                )
            }
        }
        TransferStep.ITEM -> {
            val last = state.captured.lastOrNull()
            if (last == null) {
                val lines = state.outstandingLineCount ?: state.order?.outstandingLineCount ?: 0
                val units = state.openLines.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO) { sum, l -> sum + l.remainingQuantity }
                // The transfer and its route are in the card - no "Transfer found" bar (17 Nov 2026).
                val o = state.order
                FocusCenter(
                    task = transferTask(state, mode),
                    caption = o?.transferId ?: if (ship) "To pick" else "To receive",
                    code = when (lines) {
                        0 -> "Nothing left"
                        1 -> "1 line"
                        else -> "$lines lines"
                    },
                    description = units?.takeIf { lines > 0 }?.let { "${plain(it)} units ${if (ship) "to pick" else "to receive"}" },
                    unitLine = o?.let { listOfNotNull(it.fromWarehouseId, it.toWarehouseId).takeIf { w -> w.size == 2 }?.joinToString(" → ") },
                )
            } else {
                FocusCenter(
            task = transferTask(state, mode),
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
            task = transferTask(state, mode),
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
/** What to do now, for the task box - see ReceivingFlowScreen's receivingTask. */
private fun transferTask(state: TransferFlowState, mode: TransferMode): com.d365fo.mobileops.presentation.common.focus.FocusTask {
    val i = androidx.compose.material.icons.Icons.Outlined
    val c = state.capture
    val ship = mode == TransferMode.SHIP
    val item = c.line?.itemId.orEmpty()
    val T = { t: String, d: String?, ic: androidx.compose.ui.graphics.vector.ImageVector, done: Boolean -> FocusTask(t, d, ic, done) }
    return when (state.step) {
        // "Scan order" / "Scan an item" (user, 17 Nov 2026), for ship and receive alike.
        TransferStep.TRANSFER_ORDER -> T("Scan order", null, i.ReceiptLong, false)
        TransferStep.ITEM -> {
            val last = state.captured.lastOrNull()
            val lines = state.outstandingLineCount ?: state.order?.outstandingLineCount
            when {
                lines == 0 && state.captured.isEmpty() -> T(if (ship) "Nothing left to ship" else "Nothing left to receive", "on this transfer", i.CheckCircle, true)
                last != null -> T("Scan an item", null, i.QrCodeScanner, true)
                else -> T("Scan an item", null, i.QrCodeScanner, false)
            }
        }
        TransferStep.QUANTITY -> T("Enter quantity", c.line?.let { l -> "${plain(state.suggestedQuantity ?: l.remainingQuantity)} ${l.unitId} left" }, i.Dialpad, false)
        TransferStep.LOCATION -> T("Scan the bin", if (ship) "you are picking from" else "where you put it away", i.Place, false)
        TransferStep.BATCH -> T(if (ship) "Scan or choose the batch" else "Scan the batch", item.takeIf { it.isNotBlank() }?.let { "on the box of $it" }, i.QrCodeScanner, false)
        TransferStep.SERIAL -> T("Scan the serial", c.serialsOutstanding.takeIf { it > 1 }?.let { "$it to go" }, i.QrCodeScanner, false)
        TransferStep.CONFIRM -> T("Scan an item", null, i.CheckCircle, true)
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

/** What the scan box asks for at each transfer step (#30, user 16 Nov 2026). */
internal fun transferScanLabel(step: TransferStep): String = when (step) {
    TransferStep.TRANSFER_ORDER -> "Scan the transfer number"
    TransferStep.ITEM -> "Scan an item on this transfer"
    TransferStep.QUANTITY -> "Type the quantity"
    TransferStep.LOCATION -> "Scan the location"
    TransferStep.BATCH -> "Scan the batch number"
    TransferStep.SERIAL -> "Scan the serial number"
    TransferStep.CONFIRM -> "Scan the next item"
}

