package com.d365fo.mobileops.presentation.modules.stepflow

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhotoCamera
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.domain.model.PurchaseOrderLine
import com.d365fo.mobileops.domain.model.ReceivingStep
import com.d365fo.mobileops.domain.model.canSkipStep
import com.d365fo.mobileops.presentation.common.components.FlowAction
import com.d365fo.mobileops.presentation.common.components.FlowActionBar
import com.d365fo.mobileops.presentation.common.components.FlowHeader
import com.d365fo.mobileops.presentation.common.components.FlowKeyHandler
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
import com.d365fo.mobileops.util.Formatters
import java.math.BigDecimal

/**
 * PO receiving, rebuilt on the redesign's flow layout (docs/ui-redesign-brief.md, phase 4):
 * [FlowHeader] → [StatusBand] → [NextCard] → [SendTray] → [FlowActionBar], on the comfort page.
 *
 * Scan only: the PO opens the order, an item scan receives the full open quantity with the
 * batch and expiry its GS1 label carries, and the last line posts by itself five seconds later
 * (Undo or F4 meanwhile). The band says what happened; nothing asks for an OK. Everything the
 * old screen did by tap is still here, out of the way: today's orders under the ready prompt,
 * the line choice when one item is on several lines, typing, the camera, item search and Start
 * over in the ⋮ menu, and the banked lines (Lines, F2) with Post now.
 *
 * [ReceivingFlowViewModel] and its D365FO validation, staging and posting path are unchanged;
 * see that class for the rules this draws.
 */
@Composable
fun ReceivingFlowScreen(
    onBack: () -> Unit,
    onManualEntryRequested: () -> Unit,
    viewModel: ReceivingFlowViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    var showItemSearch by remember { mutableStateOf(false) }
    var showKeypad by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // Scans reach the flow only while it is on screen - see ReceivingFlowViewModel.scannerOnScreen.
    DisposableEffect(viewModel) {
        viewModel.scannerOnScreen(true)
        onDispose { viewModel.scannerOnScreen(false) }
    }

    // Back (arrow, system back, F1) leaves the flow; banked lines are kept for "Continue where
    // you left off". Exceptions: an open list or choice closes first, and during the last
    // line's countdown Back posts at once rather than leaving it behind. The scanner's phantom
    // back right after a scan is ignored - see HidScannerBridge.justScanned.
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
    val quantityKey = if (state.step == ReceivingStep.QUANTITY && !state.busy && state.lineChoices.isEmpty() && !state.posting) {
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
            orderId = state.capture.purchId,
            onKeep = { confirmDiscard = false },
            onDiscard = {
                confirmDiscard = false
                viewModel.reset()
            },
        )
    }

    com.d365fo.mobileops.presentation.modules.cyclecounting.opportunity.OpportunityCountPrompt()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WarehouseStatusColors.pageComfort),
    ) {
        FlowHeader(
            title = state.capture.purchId?.let { "Receive $it" } ?: "Receive",
            onBack = goBack,
            progress = progressFor(state),
            menuItems = if (state.posting) null else { close ->
                DropdownMenuItem(
                    text = { Text("Type it in") },
                    leadingIcon = { Icon(Icons.Default.Keyboard, contentDescription = null) },
                    onClick = {
                        close()
                        if (state.step == ReceivingStep.QUANTITY) showKeypad = true else onManualEntryRequested()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Scan with camera") },
                    leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                    onClick = { close(); showCamera = true },
                )
                if (state.step == ReceivingStep.ITEM && state.linesInline && state.openLines.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Search items") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        onClick = { close(); showItemSearch = true },
                    )
                }
                if (state.capture.purchId != null) {
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
            val band = receivingBand(state)
            StatusBand(
                kind = band.kind,
                title = band.title,
                line = band.line,
                onUndo = if (band.undoable && viewModel.canUndo) viewModel::undo else null,
                undoKey = band.key,
                actions = when {
                    state.notice?.retry == true -> {
                        { SquareOutlinedButton(onClick = viewModel::retryPost) { Text("Retry now") } }
                    }
                    state.notice == null && state.error == null && canSkipStep(state.step, state.capture) -> {
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
                        onPostNow = viewModel::postReceipt,
                    )
                    state.lineChoices.isNotEmpty() -> LineChoice(state.lineChoices, viewModel::sessionRemaining, viewModel::chooseLine)
                    state.step == ReceivingStep.PURCHASE_ORDER -> ReadyView(state, enabled = !state.busy, onPick = viewModel::pickTodaysOrder)
                    else -> nextCardFor(state, viewModel::sessionRemaining)?.let { card ->
                        NextCard(
                            label = card.label,
                            code = card.code,
                            description = card.description,
                            location = card.location,
                            locationLabel = "Location",
                            quantity = card.quantity,
                            quantityUnit = card.unit,
                            onQuantityClick = if (viewModel.keypadValue != null) ({ showKeypad = true }) else null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            SendTray(lastSent = state.lastPost?.let { p -> p.grnId?.let { "GRN $it · ${p.purchId}" } ?: p.purchId })
        }

        FlowActionBar(
            onAction = onAction,
            highlighted = when {
                state.step == ReceivingStep.QUANTITY -> FlowAction.Keypad
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
                subtitle = (line?.let { listOfNotNull(it.itemId, it.variant.label.takeIf { v -> v.isNotBlank() }, it.purchaseUnit) }
                    ?: last?.let { listOf(it.itemId, it.unitSymbol) })?.joinToString(" · "),
                hint = if (state.step == ReceivingStep.QUANTITY) hintFor(state) else "Change the quantity received",
                initial = if (value.signum() > 0) plain(value) else "",
                replaceInitial = true,
                confirmLabel = "Confirm",
                validate = viewModel::keypadError,
                extras = line?.takeIf { state.step == ReceivingStep.QUANTITY && it.units.size > 1 }?.let { l ->
                    {
                        com.d365fo.mobileops.presentation.common.components.UnitChips(
                            units = l.units,
                            lineUnit = l.purchaseUnit,
                            selected = state.entryUnit ?: l.purchaseUnit,
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

/** The status band's content for a state - pure, so it is unit-tested (ReceivingBandTest). */
internal data class BandModel(
    val kind: StatusKind,
    val title: String,
    val line: String? = null,
    val undoable: Boolean = false,
    /** Changes with each new result, so the band's Undo window restarts. */
    val key: Any? = null,
)

internal fun receivingBand(state: ReceivingFlowState): BandModel {
    val c = state.capture
    if (state.posting) {
        val n = state.captured.size
        return BandModel(StatusKind.Info, "Posting $n " + (if (n == 1) "line" else "lines") + "…", c.purchId)
    }
    state.error?.let { return BandModel(StatusKind.Bad, it, "Nothing was added") }
    state.notice?.let { n ->
        val kind = when (n.tone) {
            ReceivingNotice.Tone.INFO -> StatusKind.Info
            ReceivingNotice.Tone.OK -> StatusKind.Ok
            ReceivingNotice.Tone.WARN -> StatusKind.Warn
            ReceivingNotice.Tone.BAD -> StatusKind.Bad
        }
        // The last line in: say it is about to post, and how to stop it.
        val line = if (state.autoPostAt != null && n.undoable) {
            listOfNotNull(n.line, "Posts in 5 s - Undo or F4 to change").joinToString("\n")
        } else {
            n.line
        }
        return BandModel(kind, n.title, line, undoable = n.undoable, key = n.id)
    }
    if (state.lineChoices.isNotEmpty()) {
        return BandModel(StatusKind.Warn, "On ${state.lineChoices.size} lines", "Pick the line below")
    }
    return when (state.step) {
        ReceivingStep.PURCHASE_ORDER -> BandModel(StatusKind.Idle, "Ready", "Scan a purchase order")
        ReceivingStep.ITEM -> when {
            state.order != null && state.captured.isEmpty() -> BandModel(
                StatusKind.Info, c.purchId.orEmpty(),
                listOfNotNull(
                    c.vendorAccount?.let { "Vendor $it" },
                    state.outstandingLineCount.takeIf { it > 0 }?.let { "$it " + (if (it == 1) "line" else "lines") + " to receive" },
                ).joinToString(" · ").ifBlank { null },
            )
            else -> BandModel(StatusKind.Info, "Scan the next item", "${state.captured.size} scanned on ${c.purchId}")
        }
        ReceivingStep.QUANTITY -> BandModel(StatusKind.Warn, "Enter the quantity", hintFor(state))
        ReceivingStep.LOCATION -> BandModel(StatusKind.Warn, "Scan the location", state.suggestedLocation?.let { "Order suggests $it" })
        ReceivingStep.BATCH -> BandModel(StatusKind.Warn, "Scan the batch", "Not on the item's label")
        ReceivingStep.BATCH_EXPIRY_DATE -> BandModel(StatusKind.Warn, "New batch - enter the expiry", "DD-MM-YYYY, from the pack (⋮ Type it in)")
        ReceivingStep.SERIAL -> BandModel(StatusKind.Info, "Scan the serial", hintFor(state))
        ReceivingStep.PACKING_SLIP, ReceivingStep.CONFIRM -> BandModel(StatusKind.Info, promptFor(state))
    }
}

/** The next-card's content - the line in hand, else the next line to scan; null when there is none. */
internal data class CardModel(
    val label: String,
    val code: String,
    val description: String?,
    val location: String?,
    val quantity: String?,
    val unit: String?,
)

internal fun nextCardFor(state: ReceivingFlowState, remainingFor: (PurchaseOrderLine) -> BigDecimal): CardModel? {
    val c = state.capture
    c.line?.let { line ->
        val qty = c.quantity ?: remainingFor(line)
        return CardModel(
            label = "Now · line ${plain(BigDecimal.valueOf(line.lineNumber))}",
            code = line.itemId,
            description = describe(line),
            location = c.locationId ?: state.suggestedLocation,
            quantity = plain(qty),
            unit = c.unitSymbol ?: line.purchaseUnit,
        )
    }
    if (state.step != ReceivingStep.ITEM) return null
    if (!state.linesInline) {
        return CardModel(
            label = "Next",
            code = "Scan an item",
            description = "${state.outstandingLineCount} " + (if (state.outstandingLineCount == 1) "line" else "lines") + " to receive on ${c.purchId}",
            location = null, quantity = null, unit = null,
        )
    }
    val open = state.openLines
    val nextIndex = open.indexOfFirst { remainingFor(it).signum() > 0 }
    if (nextIndex < 0) {
        return CardModel(
            label = "All lines scanned",
            code = c.purchId.orEmpty(),
            description = if (state.autoPostAt != null) "Posting by itself in a moment" else "Nothing left to receive",
            location = null, quantity = null, unit = null,
        )
    }
    val line = open[nextIndex]
    return CardModel(
        label = "Next · line ${nextIndex + 1} of ${open.size}",
        code = line.itemId,
        description = describe(line),
        location = line.locationId?.takeIf { it.isNotBlank() },
        quantity = plain(remainingFor(line)),
        unit = line.purchaseUnit,
    )
}

private fun describe(line: PurchaseOrderLine): String? =
    listOfNotNull(
        line.itemName.takeIf { it.isNotBlank() && !it.equals(line.itemId, ignoreCase = true) },
        line.variant.label.takeIf { it.isNotBlank() },
    ).joinToString(" · ").ifBlank { null }

/** "1 of 2" - lines done of the order's lines, when they are in hand. */
private fun progressFor(state: ReceivingFlowState): String? {
    if (state.capture.purchId == null) return null
    val total = if (state.linesInline) state.openLines.size else state.outstandingLineCount
    if (total <= 0) return null
    val done = state.captured.map { it.lineNumber }.distinct().size.coerceAtMost(total)
    return "$done of $total"
}

// -- parts ----------------------------------------------------------------------------------

/** The ready prompt: today's orders as one-tap rows, under the band's "Scan a purchase order". */
@Composable
private fun ReadyView(state: ReceivingFlowState, enabled: Boolean, onPick: (String) -> Unit) {
    val orders = state.todaysOrders
    if (orders.isEmpty()) {
        NextCard(
            label = "Next",
            code = "Purchase order",
            description = "Scan the PO label, or ⋮ Type it in - the last digits are enough for today's orders.",
            quantity = null,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(WarehouseStatusColors.surface)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Due today - scan or tap one",
            style = WarehouseType.label,
            color = WarehouseStatusColors.inkMuted,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        )
        orders.take(MAX_TODAYS_ORDERS).forEachIndexed { i, order ->
            if (i > 0) HorizontalDivider(color = WarehouseStatusColors.divider)
            ListRow(
                title = order.purchId,
                codeTitle = true,
                subtitles = listOf("Vendor ${order.vendorAccount}"),
                status = StatusKind.Idle,
                onClick = if (enabled) ({ onPick(order.purchId) }) else null,
            )
        }
        if (orders.size > MAX_TODAYS_ORDERS) {
            Text(
                "+${orders.size - MAX_TODAYS_ORDERS} more",
                style = WarehouseType.label,
                color = WarehouseStatusColors.inkMuted,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** One item on several lines - the one real choice receiving still asks for. */
@Composable
private fun LineChoice(
    lines: List<PurchaseOrderLine>,
    remainingFor: (PurchaseOrderLine) -> BigDecimal,
    onPick: (PurchaseOrderLine) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(WarehouseStatusColors.surface),
    ) {
        items(lines) { line ->
            ListRow(
                title = "Line ${plain(BigDecimal.valueOf(line.lineNumber))} · ${line.itemId}",
                subtitles = listOfNotNull(describe(line), line.locationId?.takeIf { it.isNotBlank() }?.let { "Location $it" }),
                status = StatusKind.Info,
                trailing = { ListRowValue(Formatters.qtyUnit(remainingFor(line), line.purchaseUnit)) },
                onClick = { onPick(line) },
            )
            HorizontalDivider(color = WarehouseStatusColors.divider)
        }
    }
}

/**
 * Lines (F2): what is banked on this order, newest first, each removable, then what is still
 * open; Post now posts early (a short delivery, a break) - otherwise the last line posts by itself.
 */
@Composable
private fun LinesView(
    state: ReceivingFlowState,
    remainingFor: (PurchaseOrderLine) -> BigDecimal,
    onRemove: (Int) -> Unit,
    onPostNow: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(WarehouseStatusColors.surface),
        ) {
            item { SectionLabel("Scanned (${state.captured.size})") }
            if (state.captured.isEmpty()) {
                item { SectionNote("Nothing scanned on this order yet") }
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
                            entry.batchExpiryDate?.let { "expires ${displayDate(it)}" },
                            entry.serialIds.takeIf { it.isNotEmpty() }?.let { "${it.size} " + (if (it.size == 1) "serial" else "serials") },
                        ).joinToString(" · ").ifBlank { null },
                    ),
                    status = StatusKind.Ok,
                    trailing = {
                        ListRowValue(Formatters.qtyUnit(entry.quantity, entry.unitSymbol))
                        IconButton(onClick = { onRemove(index) }, enabled = !state.posting) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove ${entry.itemId}", tint = WarehouseStatusColors.inkMuted)
                        }
                    },
                )
                HorizontalDivider(color = WarehouseStatusColors.divider)
            }
            val open = if (state.linesInline) state.openLines.filter { remainingFor(it).signum() > 0 } else emptyList()
            if (open.isNotEmpty()) {
                item { SectionLabel("Still to receive (${open.size})") }
                items(open) { line ->
                    ListRow(
                        title = line.itemId,
                        codeTitle = true,
                        subtitles = listOfNotNull(describe(line), line.locationId?.takeIf { it.isNotBlank() }?.let { "Location $it" }),
                        status = StatusKind.Idle,
                        trailing = { ListRowValue("${plain(remainingFor(line))} / ${plain(line.orderedQuantity)}") },
                    )
                    HorizontalDivider(color = WarehouseStatusColors.divider)
                }
            }
        }
        if (state.captured.isNotEmpty()) {
            SquareButton(
                onClick = onPostNow,
                enabled = !state.posting && !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.StepperButton),
            ) {
                Text("Post now - ${state.captured.size} " + (if (state.captured.size == 1) "line" else "lines"))
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
 * Find a line by typing part of its item name or ID instead of scanning - see the identical
 * sheet on TransferFlowScreen.
 */
@Composable
private fun ItemSearchSheet(
    lines: List<PurchaseOrderLine>,
    remainingFor: (PurchaseOrderLine) -> BigDecimal,
    onPick: (PurchaseOrderLine) -> Unit,
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
                            subtitles = listOfNotNull(describe(line)),
                            trailing = { ListRowValue("${plain(remainingFor(line))} / ${plain(line.orderedQuantity)}") },
                            onClick = { onPick(line) },
                        )
                        HorizontalDivider(color = WarehouseStatusColors.divider)
                    }
                }
            }
        }
    }
}

// -- shared with the typing screen and Transfer ----------------------------------------------

internal fun promptFor(state: ReceivingFlowState): String = when (state.step) {
    ReceivingStep.PURCHASE_ORDER -> "Scan purchase order"
    ReceivingStep.PACKING_SLIP -> "Scan delivery note"
    ReceivingStep.ITEM -> "Scan item"
    ReceivingStep.QUANTITY -> "Enter quantity"
    ReceivingStep.LOCATION -> "Scan location"
    ReceivingStep.BATCH -> "Scan batch"
    ReceivingStep.BATCH_EXPIRY_DATE -> "Expiry date"
    ReceivingStep.SERIAL -> {
        val remaining = state.capture.serialsOutstanding
        if (remaining > 1) "Scan serial ($remaining to go)" else "Scan serial"
    }
    ReceivingStep.CONFIRM -> "Confirm"
}

internal fun hintFor(state: ReceivingFlowState): String? = when (state.step) {
    ReceivingStep.ITEM -> if (state.outstandingLineCount == 0 && state.captured.isEmpty() && state.order != null) {
        "Nothing left to receive on this order"
    } else {
        null
    }
    ReceivingStep.QUANTITY -> state.capture.line?.let { line ->
        // What is still left for this device, not D365FO's count, which still includes what was
        // just scanned or sent from here.
        val left = state.suggestedQuantity
        val spoken = left?.let { line.outstandingQuantity - it }?.takeIf { it.signum() > 0 }
        "${Formatters.qtyUnit(left ?: line.outstandingQuantity, line.purchaseUnit)} " +
            "left of ${plain(line.orderedQuantity)}" +
            (spoken?.let { " · ${plain(it)} scanned" } ?: "") +
            (state.entryUnit?.let { unit -> " · entering in $unit" } ?: "")
    }
    ReceivingStep.LOCATION -> state.suggestedLocation?.let { "Order suggests $it" }
    // From the line's own rule, not the quantity: without serial number control in D365FO one
    // serial covers the whole line.
    ReceivingStep.SERIAL -> state.capture.quantity?.let {
        serialPrompt(state.capture.line?.rules?.serialPerUnit != false, state.capture.serialsOutstanding)
    }
    ReceivingStep.BATCH_EXPIRY_DATE -> "New batch - expiry DD-MM-YYYY"
    else -> null
}

private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

internal fun serialPrompt(perUnit: Boolean, outstanding: Int): String = when {
    !perUnit -> "One serial for this line"
    outstanding == 1 -> "One serial per unit — 1 more to scan"
    else -> "One serial per unit — $outstanding more to scan"
}

/**
 * The typing screen for the current step (⋮ Type it in) - the **same** [ReceivingFlowViewModel]
 * as the flow underneath (see AppNavGraph's RECEIVE_FLOW_ENTRY route), so an entry here answers
 * the flow in progress and Back lands on it one step on.
 */
@Composable
fun ReceivingEntryScreen(
    viewModel: ReceivingFlowViewModel,
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
        hint = hintFor(state) ?: if (state.step == ReceivingStep.PURCHASE_ORDER) {
            "Last digits are enough for today's POs"
        } else {
            null
        },
        keyboardType = if (state.step == ReceivingStep.QUANTITY) KeyboardType.Decimal else KeyboardType.Text,
        showNumericKeypad = state.step == ReceivingStep.QUANTITY,
        quickFillLabel = state.suggestedQuantity?.takeIf { state.step == ReceivingStep.QUANTITY }
            ?.let { "Receive all outstanding (${plain(it)})" },
        quickFillValue = state.suggestedQuantity?.takeIf { state.step == ReceivingStep.QUANTITY }?.let { plain(it) },
        validate = viewModel::quantityError,
        keypadExtras = state.capture.line?.takeIf { state.step == ReceivingStep.QUANTITY && it.units.size > 1 }?.let { line ->
            {
                com.d365fo.mobileops.presentation.common.components.UnitChips(
                    units = line.units,
                    lineUnit = line.purchaseUnit,
                    selected = state.entryUnit ?: line.purchaseUnit,
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

private const val MAX_TODAYS_ORDERS = 6
