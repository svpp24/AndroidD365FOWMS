package com.d365fo.mobileops.presentation.modules.transfer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.domain.model.TransferMode
import com.d365fo.mobileops.domain.model.TransferOrderLine
import com.d365fo.mobileops.domain.model.TransferStep
import com.d365fo.mobileops.domain.model.canSkipTransferStep
import com.d365fo.mobileops.presentation.common.components.FlowAction
import com.d365fo.mobileops.presentation.common.components.FlowActionBar
import com.d365fo.mobileops.presentation.common.components.FlowHeader
import com.d365fo.mobileops.presentation.common.components.FlowKeyHandler
import com.d365fo.mobileops.presentation.common.components.HoldToConfirmButton
import com.d365fo.mobileops.presentation.common.components.ListRow
import com.d365fo.mobileops.presentation.common.components.ListRowValue
import com.d365fo.mobileops.presentation.common.components.NextCard
import com.d365fo.mobileops.presentation.common.components.SendTray
import com.d365fo.mobileops.presentation.common.components.SquareButton
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton
import com.d365fo.mobileops.presentation.common.components.StatusBand
import com.d365fo.mobileops.presentation.common.components.StatusKind
import com.d365fo.mobileops.presentation.common.components.TextEntryScreen
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.presentation.common.theme.WarehouseType
import com.d365fo.mobileops.presentation.modules.stepflow.BandModel
import com.d365fo.mobileops.presentation.modules.stepflow.CardModel
import com.d365fo.mobileops.util.Formatters
import java.math.BigDecimal

/**
 * Transfer ship and receive, rebuilt on the redesign's flow layout (docs/ui-redesign-brief.md,
 * phase 5): [FlowHeader] → [StatusBand] → [NextCard] → [SendTray] → [FlowActionBar], on the
 * comfort page - the same shape as PO receiving (phase 4).
 *
 * Scan only: the transfer opens the order, an item scan takes the line in full - shipping from the
 * location that has the stock, receiving with the batch from the shipment into the receipt
 * location - and the last line ships or posts by itself five seconds later (Undo or F4
 * meanwhile). No stock is a Warn band with **Skip line** / **Ship without it**. Ending a
 * transfer early (Lines, F2) is a hold, not a tap. [mode] decides the wording.
 *
 * [TransferFlowViewModel] and its D365FO validation, live picking and posting path are
 * unchanged; see that class for the rules this draws.
 */
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
    var showKeypad by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val shipping = mode == TransferMode.SHIP

    LaunchedEffect(mode) { viewModel.setMode(mode) }

    // Scans reach the flow only while it is on screen - see TransferFlowViewModel.scannerOnScreen.
    DisposableEffect(viewModel) {
        viewModel.scannerOnScreen(true)
        onDispose { viewModel.scannerOnScreen(false) }
    }

    // Back (arrow, system back, F1) leaves the flow; banked lines are kept for "Continue where
    // you left off". An open list closes first, and during the last line's countdown Back
    // ships or posts at once rather than leaving it behind. The scanner's phantom back right
    // after a scan is ignored - see HidScannerBridge.justScanned.
    val goBack = {
        if (!viewModel.justScanned() && !state.posting) {
            when {
                state.showingCaptured -> viewModel.showCaptured(false)
                viewModel.autoPostPending -> viewModel.postPendingNow()
                else -> onBack()
            }
        }
    }
    BackHandler(onBack = goBack)

    val onAction: (FlowAction) -> Unit = { action ->
        when (action) {
            FlowAction.Back -> goBack()
            FlowAction.Lines -> if (!state.posting) viewModel.showCaptured(!state.showingCaptured)
            FlowAction.Undo -> viewModel.undo()
            FlowAction.Keypad -> if (viewModel.keypadValue != null) showKeypad = true
        }
    }
    FlowKeyHandler(onAction)

    // The quantity step is rare now (the quantity is filled in): when it comes, the keypad opens
    // by itself, once per line, after the scanner's phantom back has passed.
    var keypadOpenedFor by rememberSaveable { mutableStateOf<String?>(null) }
    val quantityKey = if (state.step == TransferStep.QUANTITY && !state.busy && state.lineChoices.isEmpty() && !state.posting) {
        "${state.capture.line?.lineNumber}:${state.captured.size}"
    } else {
        null
    }
    LaunchedEffect(quantityKey) {
        if (quantityKey != null && quantityKey != keypadOpenedFor) {
            while (viewModel.justScanned()) kotlinx.coroutines.delay(150)
            keypadOpenedFor = quantityKey
            showKeypad = true
        }
    }

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

    com.d365fo.mobileops.presentation.modules.cyclecounting.opportunity.OpportunityCountPrompt()
    val discrepancy = androidx.hilt.navigation.compose.hiltViewModel<
        com.d365fo.mobileops.presentation.modules.cyclecounting.opportunity.DiscrepancyViewModel,
    >()
    if (shipping) {
        // The report sheet itself; its trigger is the ⋮ menu's "Report discrepancy".
        com.d365fo.mobileops.presentation.modules.cyclecounting.opportunity.ReportDiscrepancyAction(
            locationId = state.capture.locationId ?: state.capture.line?.locationId,
            itemId = state.capture.line?.itemId,
            showTrigger = false,
            viewModel = discrepancy,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WarehouseStatusColors.pageComfort),
    ) {
        FlowHeader(
            title = (if (shipping) "Ship" else "Receive") + (state.capture.transferId?.let { " $it" } ?: " transfer"),
            onBack = goBack,
            progress = progressFor(state, viewModel::sessionRemaining),
            menuItems = if (state.posting) null else { close ->
                DropdownMenuItem(
                    text = { Text("Type it in") },
                    leadingIcon = { Icon(Icons.Default.Keyboard, contentDescription = null) },
                    onClick = {
                        close()
                        if (state.step == TransferStep.QUANTITY) showKeypad = true else onManualEntryRequested()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Scan with camera") },
                    leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                    onClick = { close(); showCamera = true },
                )
                if (state.step == TransferStep.ITEM && state.openLines.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Search items") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        onClick = { close(); showItemSearch = true },
                    )
                }
                if (shipping && discrepancy.enabled) {
                    DropdownMenuItem(
                        text = { Text("Report discrepancy") },
                        leadingIcon = { Icon(Icons.Default.ReportProblem, contentDescription = null) },
                        onClick = {
                            close()
                            discrepancy.open(state.capture.locationId ?: state.capture.line?.locationId, state.capture.line?.itemId)
                        },
                    )
                }
                if (state.capture.transferId != null) {
                    DropdownMenuItem(
                        text = { Text("Start over") },
                        leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                        onClick = {
                            close()
                            if (!viewModel.justScanned()) {
                                if (state.captured.isEmpty()) viewModel.reset() else confirmDiscard = true
                            }
                        },
                    )
                }
            },
        )
        // A lookup in progress: a thin line under the header, steady height so nothing jumps.
        Box(Modifier.fillMaxWidth().heightIn(min = 3.dp)) {
            if (state.busy || state.posting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = WarehouseDimens.ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val band = transferBand(state)
            StatusBand(
                kind = band.kind,
                title = band.title,
                line = band.line,
                onUndo = if (band.undoable && viewModel.canUndo) viewModel::undo else null,
                undoKey = band.key,
                actions = when {
                    // No stock: the one real choice shipping asks (brief §4).
                    state.outOfStock != null && state.error == null -> {
                        {
                            SquareOutlinedButton(onClick = viewModel::skipOutOfStock, enabled = !state.busy) { Text("Skip line") }
                            if (state.captured.isNotEmpty()) {
                                SquareButton(onClick = viewModel::shipWithoutIt, enabled = !state.busy) { Text("Ship without it") }
                            }
                        }
                    }
                    state.notice?.retry == true -> {
                        { SquareOutlinedButton(onClick = viewModel::retryPost) { Text("Retry now") } }
                    }
                    state.notice == null && state.error == null && canSkipTransferStep(state.step, state.capture) -> {
                        { SquareOutlinedButton(onClick = viewModel::skipStep, enabled = !state.busy) { Text("Skip - not required") } }
                    }
                    else -> null
                },
            )

            Box(Modifier.weight(1f)) {
                when {
                    state.showingCaptured -> LinesView(
                        state = state,
                        remainingFor = viewModel::sessionRemaining,
                        onRemove = viewModel::removeCaptured,
                        onPostNow = viewModel::postTransfer,
                    )
                    state.lineChoices.isNotEmpty() -> LineChoice(state.lineChoices, viewModel::sessionRemaining, viewModel::chooseLine)
                    else -> transferCardFor(state, viewModel::sessionRemaining)?.let { card ->
                        NextCard(
                            label = card.label,
                            code = card.code,
                            description = card.description,
                            location = card.location,
                            locationLabel = if (shipping) "Pick from" else "Put away to",
                            quantity = card.quantity,
                            quantityUnit = card.unit,
                            onQuantityClick = if (viewModel.keypadValue != null) ({ showKeypad = true }) else null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            SendTray(
                lastSent = state.lastPost?.let { p ->
                    listOfNotNull(p.documentId, p.transferId).joinToString(" · ")
                },
            )
        }

        FlowActionBar(
            onAction = onAction,
            highlighted = when {
                state.step == TransferStep.QUANTITY -> FlowAction.Keypad
                state.autoPostAt != null -> FlowAction.Undo
                else -> null
            },
            enabled = buildSet {
                add(FlowAction.Back)
                if (!state.posting) add(FlowAction.Lines)
                if (viewModel.canUndo) add(FlowAction.Undo)
                if (viewModel.keypadValue != null) add(FlowAction.Keypad)
            },
        )
    }

    if (showKeypad) {
        val value = viewModel.keypadValue
        if (value == null) {
            showKeypad = false
        } else {
            val line = state.capture.line
            val last = state.captured.lastOrNull()
            com.d365fo.mobileops.presentation.common.components.QuantityPicker(
                subtitle = (line?.let { listOfNotNull(it.itemId, it.variant.label.takeIf { v -> v.isNotBlank() }, it.unitId) }
                    ?: last?.let { listOf(it.itemId, it.unitSymbol) })?.joinToString(" · "),
                hint = if (state.step == TransferStep.QUANTITY) hintFor(state) else if (shipping) "Change the quantity picked" else "Change the quantity received",
                initial = if (value.signum() > 0) plain(value) else "",
                replaceInitial = true,
                confirmLabel = "Confirm",
                validate = viewModel::keypadError,
                extras = line?.takeIf { state.step == TransferStep.QUANTITY && it.units.size > 1 }?.let { l ->
                    {
                        com.d365fo.mobileops.presentation.common.components.UnitChips(
                            units = l.units,
                            lineUnit = l.unitId,
                            selected = state.entryUnit ?: l.unitId,
                            onSelect = viewModel::selectEntryUnit,
                        )
                    }
                },
                onConfirm = { entered ->
                    showKeypad = false
                    viewModel.applyKeypad(entered)
                },
                onDismiss = { if (!viewModel.justScanned()) showKeypad = false },
            )
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
            remainingFor = viewModel::sessionRemaining,
            onPick = { line ->
                showItemSearch = false
                viewModel.pickSearchedLine(line.itemId, line.lineNumber)
            },
            onDismiss = { showItemSearch = false },
        )
    }
}

// -- what the band and the card say ---------------------------------------------------------

/** The status band's content for a state - pure, so it is unit-tested (TransferRedesignTest). */
internal fun transferBand(state: TransferFlowState): BandModel {
    val c = state.capture
    val shipping = c.mode == TransferMode.SHIP
    if (state.posting) {
        val n = state.captured.size
        return BandModel(StatusKind.Info, (if (shipping) "Shipping " else "Posting ") + "$n " + (if (n == 1) "line" else "lines") + "…", c.transferId)
    }
    state.error?.let { return BandModel(StatusKind.Bad, it, "Nothing was added") }
    state.outOfStock?.let { o ->
        return BandModel(
            StatusKind.Warn,
            "Out of stock",
            "${o.itemId}: none to pick" + (o.at?.let { " at $it" } ?: "") +
                if (state.captured.isEmpty()) " - skip it, or Undo" else " - skip it, or ship the rest now",
            key = o,
        )
    }
    state.notice?.let { n ->
        val kind = when (n.tone) {
            TransferNotice.Tone.INFO -> StatusKind.Info
            TransferNotice.Tone.OK -> StatusKind.Ok
            TransferNotice.Tone.WARN -> StatusKind.Warn
            TransferNotice.Tone.BAD -> StatusKind.Bad
        }
        // The last line in: say it is about to go, and how to stop it.
        val line = if (state.autoPostAt != null && n.undoable) {
            listOfNotNull(n.line, (if (shipping) "Ships" else "Posts") + " in 5 s - Undo or F4 to change").joinToString("\n")
        } else {
            n.line
        }
        return BandModel(kind, n.title, line, undoable = n.undoable, key = n.id)
    }
    if (state.lineChoices.isNotEmpty()) {
        return BandModel(StatusKind.Warn, "On ${state.lineChoices.size} lines", "Pick the line below")
    }
    return when (state.step) {
        TransferStep.TRANSFER_ORDER -> BandModel(
            StatusKind.Idle, "Ready",
            if (shipping) "Scan a transfer order to ship" else "Scan a transfer order to receive",
        )
        TransferStep.ITEM -> when {
            state.order != null && state.captured.isEmpty() -> BandModel(
                StatusKind.Info, c.transferId.orEmpty(),
                listOfNotNull(
                    listOfNotNull(c.fromWarehouseId, c.toWarehouseId).takeIf { it.size == 2 }?.joinToString(" → "),
                    state.outstandingLineCount?.takeIf { it > 0 }?.let {
                        "$it " + (if (it == 1) "line" else "lines") + (if (shipping) " to ship" else " to receive")
                    },
                ).joinToString(" · ").ifBlank { null },
            )
            else -> BandModel(StatusKind.Info, "Scan the next item", "${state.captured.size} scanned on ${c.transferId}")
        }
        TransferStep.QUANTITY -> BandModel(StatusKind.Warn, "Enter the quantity", hintFor(state))
        TransferStep.LOCATION -> if (shipping) {
            BandModel(StatusKind.Warn, "Scan the location", state.stockHint?.let { "In stock at $it" } ?: "Where you pick it from")
        } else {
            BandModel(StatusKind.Warn, "Scan the location", "Where you put it away")
        }
        TransferStep.BATCH -> BandModel(StatusKind.Warn, "Scan the batch", hintFor(state))
        TransferStep.SERIAL -> BandModel(StatusKind.Info, "Scan the serial", hintFor(state))
        TransferStep.CONFIRM -> BandModel(StatusKind.Info, "Confirm")
    }
}

/** The next-card's content - the line in hand, else the next line to scan; null when there is none. */
internal fun transferCardFor(state: TransferFlowState, remainingFor: (TransferOrderLine) -> BigDecimal): CardModel? {
    val c = state.capture
    val shipping = c.mode == TransferMode.SHIP
    c.line?.let { line ->
        val qty = c.quantity ?: remainingFor(line)
        return CardModel(
            label = "Now · line ${plain(BigDecimal.valueOf(line.lineNumber))}",
            code = line.itemId,
            description = describe(line, c.batchId),
            location = c.locationId,
            quantity = plain(qty),
            unit = c.unitSymbol ?: line.unitId,
        )
    }
    if (state.step == TransferStep.TRANSFER_ORDER) {
        return CardModel(
            label = "Next",
            code = "Transfer order",
            description = if (shipping) {
                "Scan the transfer label, or ⋮ Type it in. Each item then ships in full from the location with stock."
            } else {
                "Scan the transfer label, or ⋮ Type it in. Each item is then received in full, batch from the shipment."
            },
            location = null, quantity = null, unit = null,
        )
    }
    if (state.step != TransferStep.ITEM) return null
    if (state.order?.linesInline != true || state.openLines.isEmpty()) {
        val n = state.outstandingLineCount ?: 0
        return CardModel(
            label = "Next",
            code = "Scan an item",
            description = "$n " + (if (n == 1) "line" else "lines") + (if (shipping) " to ship" else " to receive") + " on ${c.transferId}",
            location = null, quantity = null, unit = null,
        )
    }
    val open = state.openLines
    val nextIndex = open.indexOfFirst { it.lineNumber !in state.setAside && remainingFor(it).signum() > 0 }
    if (nextIndex < 0) {
        return CardModel(
            label = "All lines scanned",
            code = c.transferId.orEmpty(),
            description = when {
                state.autoPostAt != null -> if (shipping) "Shipping by itself in a moment" else "Posting by itself in a moment"
                state.captured.isEmpty() -> "Nothing to " + (if (shipping) "ship" else "receive")
                else -> "Lines (F2) to " + (if (shipping) "ship" else "post") + " now"
            },
            location = null, quantity = null, unit = null,
        )
    }
    val line = open[nextIndex]
    return CardModel(
        label = "Next · line ${nextIndex + 1} of ${open.size}",
        code = line.itemId,
        description = describe(line, null),
        // Shipping: the line's own source location, a hint until the stock picks one.
        // Receiving: where it goes - the warehouse's receipt location.
        location = if (shipping) line.locationId?.takeIf { it.isNotBlank() } else c.defaultLocationId,
        quantity = plain(remainingFor(line)),
        unit = line.unitId,
    )
}

private fun describe(line: TransferOrderLine, batchId: String?): String? =
    listOfNotNull(
        line.itemName.takeIf { it.isNotBlank() && !it.equals(line.itemId, ignoreCase = true) },
        line.variant.label.takeIf { it.isNotBlank() },
        batchId?.let { "Batch $it" },
    ).joinToString(" · ").ifBlank { null }

/** "1 of 2" - lines done of the order's lines, when they are in hand. */
private fun progressFor(state: TransferFlowState, remainingFor: (TransferOrderLine) -> BigDecimal): String? {
    if (state.capture.transferId == null) return null
    if (state.order?.linesInline == true && state.openLines.isNotEmpty()) {
        val done = state.openLines.count { it.lineNumber in state.setAside || remainingFor(it).signum() <= 0 }
        return "$done of ${state.openLines.size}"
    }
    val total = state.order?.outstandingLineCount?.takeIf { it > 0 } ?: return null
    val done = state.captured.map { it.lineNumber }.distinct().size.coerceAtMost(total)
    return "$done of $total"
}

// -- parts ----------------------------------------------------------------------------------

/** One item on several lines - the one line choice the flow still asks for. */
@Composable
private fun LineChoice(
    lines: List<TransferOrderLine>,
    remainingFor: (TransferOrderLine) -> BigDecimal,
    onPick: (TransferOrderLine) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(WarehouseStatusColors.surface),
    ) {
        items(lines) { line ->
            ListRow(
                title = "Line ${plain(BigDecimal.valueOf(line.lineNumber))} · ${line.itemId}",
                subtitles = listOfNotNull(describe(line, null), line.lineBatchId?.let { "Batch $it" }),
                status = StatusKind.Info,
                trailing = { ListRowValue(Formatters.qtyUnit(remainingFor(line), line.unitId)) },
                onClick = { onPick(line) },
            )
            HorizontalDivider(color = WarehouseStatusColors.divider)
        }
    }
}

/**
 * Lines (F2): what is banked on this transfer, newest first, each removable, then what is still
 * open. Ship now / Receive now posts early - with lines still open that ends the transfer early,
 * so it is a hold (brief §3), not a tap. Otherwise the last line posts by itself.
 */
@Composable
private fun LinesView(
    state: TransferFlowState,
    remainingFor: (TransferOrderLine) -> BigDecimal,
    onRemove: (Int) -> Unit,
    onPostNow: () -> Unit,
) {
    val shipping = state.capture.mode == TransferMode.SHIP
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val open = if (state.order?.linesInline == true) {
            state.openLines.filter { remainingFor(it).signum() > 0 }
        } else {
            emptyList()
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(WarehouseStatusColors.surface),
        ) {
            item { SectionLabel((if (shipping) "Picked" else "Scanned") + " (${state.captured.size})") }
            if (state.captured.isEmpty()) {
                item { SectionNote("Nothing scanned on this transfer yet") }
            }
            items(state.captured.indices.reversed().toList()) { index ->
                val entry = state.captured[index]
                ListRow(
                    title = entry.itemId,
                    codeTitle = true,
                    subtitles = listOfNotNull(
                        entry.itemName.takeIf { it.isNotBlank() && !it.equals(entry.itemId, ignoreCase = true) },
                        listOfNotNull(
                            entry.variantLabel.takeIf { it.isNotBlank() },
                            entry.locationId?.takeIf { it.isNotBlank() },
                            entry.batchId?.let { "Batch $it" },
                            when (entry.serialIds.size) {
                                0 -> null
                                1 -> "Serial ${entry.serialIds[0]}"
                                else -> "${entry.serialIds.size} serials (${entry.serialIds[0]}, …)"
                            },
                        ).joinToString(" · ").ifBlank { null },
                        if (shipping) {
                            when {
                                entry.pickedEarlier -> "Picked on another device"
                                entry.picked -> "Picked in D365FO"
                                else -> "Picked when it ships"
                            }
                        } else {
                            null
                        },
                    ),
                    status = if (shipping && entry.pickedEarlier) StatusKind.Warn else StatusKind.Ok,
                    trailing = {
                        ListRowValue(Formatters.qtyUnit(entry.quantity, entry.unitSymbol))
                        IconButton(onClick = { onRemove(index) }, enabled = !state.posting && !state.busy) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove ${entry.itemId}", tint = WarehouseStatusColors.inkMuted)
                        }
                    },
                )
                HorizontalDivider(color = WarehouseStatusColors.divider)
            }
            if (open.isNotEmpty()) {
                item { SectionLabel("Still to " + (if (shipping) "ship" else "receive") + " (${open.size})") }
                items(open) { line ->
                    val skipped = line.lineNumber in state.setAside
                    ListRow(
                        title = line.itemId,
                        codeTitle = true,
                        subtitles = listOfNotNull(
                            describe(line, null),
                            if (skipped) "Left on the order" else null,
                        ),
                        status = if (skipped) StatusKind.Warn else StatusKind.Idle,
                        trailing = { ListRowValue("${plain(remainingFor(line))} / ${plain(line.remainingQuantity)}") },
                    )
                    HorizontalDivider(color = WarehouseStatusColors.divider)
                }
            }
        }
        if (state.captured.isNotEmpty()) {
            val n = state.captured.size
            val what = "$n " + (if (n == 1) "line" else "lines")
            val early = open.any { it.lineNumber !in state.setAside }
            if (early) {
                // Lines still open: ending the transfer here is the irreversible case the hold is for.
                HoldToConfirmButton(
                    label = (if (shipping) "Hold to ship " else "Hold to receive ") + what,
                    onConfirm = onPostNow,
                    enabled = !state.posting && !state.busy,
                    hint = "Lines still open stay on the order - keep holding to end it here",
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                SquareButton(
                    onClick = onPostNow,
                    enabled = !state.posting && !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.StepperButton),
                ) {
                    Text((if (shipping) "Ship now - " else "Receive now - ") + what)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = WarehouseType.label,
        color = WarehouseStatusColors.inkMuted,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun SectionNote(text: String) {
    Text(text, style = WarehouseType.body, color = WarehouseStatusColors.inkMuted, modifier = Modifier.padding(16.dp))
}

/**
 * Lets the operator find a line by typing part of its item name or ID instead of scanning -
 * a damaged barcode, or picks staged from a printed list. Filters [lines] in memory.
 */
@Composable
private fun ItemSearchSheet(
    lines: List<TransferOrderLine>,
    remainingFor: (TransferOrderLine) -> BigDecimal,
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
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(WarehouseStatusColors.surface)
                .clickable(enabled = false) {}
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Find an item",
                style = WarehouseType.title,
                color = WarehouseStatusColors.ink,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Item name or ID") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            if (results.isEmpty()) {
                SectionNote("No outstanding line matches \"$query\"")
            } else {
                LazyColumn {
                    items(results) { line ->
                        ListRow(
                            title = line.itemId,
                            codeTitle = true,
                            subtitles = listOfNotNull(describe(line, null)),
                            trailing = { ListRowValue("${plain(remainingFor(line))} / ${plain(line.remainingQuantity)}") },
                            onClick = { onPick(line) },
                        )
                        HorizontalDivider(color = WarehouseStatusColors.divider)
                    }
                }
            }
        }
    }
}

// -- shared with the typing screen ----------------------------------------------------------

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
    TransferStep.QUANTITY -> state.capture.line?.let { line ->
        "${Formatters.qtyUnit(state.suggestedQuantity ?: line.remainingQuantity, line.unitId)} still to " +
            (if (state.capture.mode == TransferMode.SHIP) "ship" else "receive") +
            (state.entryUnit?.let { unit -> " · entering in $unit" } ?: "")
    }

    // Receiving: which batch came on the truck is known from the shipment. Shipping: the batch
    // the order was created for (TO creation's "which stock?").
    TransferStep.BATCH -> state.capture.line?.let { line ->
        when {
            state.capture.mode == TransferMode.RECEIVE && line.shippedBatches.isNotEmpty() ->
                "Shipped: " + line.shippedBatches.joinToString { "${it.batchId} (${plain(it.quantity)})" }
            state.capture.mode == TransferMode.SHIP && line.lineBatchId != null ->
                "Ordered from batch ${line.lineBatchId}"
            else -> "Several batches in stock here"
        }
    }

    TransferStep.SERIAL -> state.capture.quantity?.let {
        com.d365fo.mobileops.presentation.modules.stepflow.serialPrompt(
            state.capture.line?.rules?.serialPerUnit != false, state.capture.serialsOutstanding,
        )
    }

    TransferStep.TRANSFER_ORDER -> "Last digits are enough"

    else -> null
}

private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

/**
 * The dedicated typing screen for whichever step is current (⋮ Type it in) - see the identical
 * [com.d365fo.mobileops.presentation.modules.stepflow.ReceivingEntryScreen]: the **same**
 * [TransferFlowViewModel] as the flow underneath, so an entry here answers the flow in progress.
 */
@Composable
fun TransferEntryScreen(
    viewModel: TransferFlowViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Scans still reach the flow while this screen is open - see scannerOnScreen.
    DisposableEffect(viewModel) {
        viewModel.scannerOnScreen(true)
        onDispose { viewModel.scannerOnScreen(false) }
    }
    // A scan made meanwhile moves the flow, or is refused, on the screen underneath: close, so its
    // answer is seen - once, as onSubmit pops too.
    var closed by remember { mutableStateOf(false) }
    val close = { if (!closed) { closed = true; onBack() } }
    val openedOn = remember { Triple(state.step, state.error, state.notice?.id) }
    LaunchedEffect(state.step, state.error, state.notice?.id) {
        if (state.step != openedOn.first ||
            (state.error != null && state.error != openedOn.second) ||
            (state.notice != null && state.notice?.id != openedOn.third)
        ) close()
    }
    TextEntryScreen(
        title = promptFor(state).replaceFirst("Scan ", "Enter "),
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
        // The scanner's phantom back must not close the pad - see HidScannerBridge.justScanned.
        onBack = { if (!viewModel.justScanned()) close() },
        onSubmit = { value ->
            viewModel.onManualEntry(value)
            close()
        },
    )
}
