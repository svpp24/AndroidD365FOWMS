package com.d365fo.mobileops.presentation.modules.transfer

import com.d365fo.mobileops.presentation.common.components.StepTrail
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
import com.d365fo.mobileops.presentation.common.components.PromptHeroCard
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (mode == TransferMode.SHIP) "Ship transfer" else "Receive transfer") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = stepBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Once an order is open, Report and Start over go in the overflow menu: with
                    // the list, connection and feedback icons the bar was crowded (feedback
                    // 994bf988). Before that, Report keeps its labelled button.
                    val orderOpen = state.capture.transferId != null
                    if (mode == TransferMode.SHIP) {
                        com.d365fo.mobileops.presentation.modules.cyclecounting.opportunity.ReportDiscrepancyAction(
                            locationId = state.capture.locationId ?: state.capture.line?.locationId,
                            itemId = state.capture.line?.itemId,
                            showTrigger = !orderOpen,
                            viewModel = discrepancy,
                        )
                    }
                    // The scanned lines are reviewed from the dock (Harbour).
                },
                // Report and Start over in the screen's one ⋮ menu, above Send feedback - a
                // second ⋮ of its own sat beside it ("controls merged", feedback 10 Oct).
                menuItems = if (state.capture.transferId == null) null else { close ->
                    if (mode == TransferMode.SHIP && discrepancy.enabled) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Report discrepancy") },
                            leadingIcon = { Icon(Icons.Default.ReportProblem, contentDescription = null) },
                            onClick = { close(); discrepancy.open(state.capture.locationId ?: state.capture.line?.locationId, state.capture.line?.itemId) },
                        )
                    }
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Start over") },
                        leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                        onClick = {
                            close()
                            // Asks first when anything is scanned - see DiscardScannedDialog - and
                            // ignores the scanner's phantom tap (see stepBack).
                            if (!viewModel.justScanned()) {
                                if (state.captured.isEmpty()) viewModel.reset() else confirmDiscard = true
                            }
                        },
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StepProgress(state)

            // The scan card stays in one place on every step (feedback, 10 Oct): it used to sit
            // below this summary, which grows from one row to five as the steps fill it in, so
            // the card moved down the screen each step. With the card showing, the summary is
            // drawn below it (StepPrompt's context); elsewhere it stays on top.
            val prompting = !state.showingCaptured && state.lineChoices.isEmpty()
            if (!prompting) state.capture.transferId?.let { CaptureSummary(state) }

            // weight(1f) so StepPrompt has the screen's actual remaining height to work with -
            // see the identical note on PO receiving's own copy of this Box.
            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.showingCaptured -> CapturedLines(
                        captured = state.captured,
                        busy = state.busy,
                        onRemove = viewModel::removeCaptured,
                        onAdjustQuantity = viewModel::adjustCapturedQuantity,
                        onClose = { viewModel.showCaptured(false) },
                        onPost = viewModel::postTransfer,
                        shipping = mode == TransferMode.SHIP,
                    )

                    state.lineChoices.isNotEmpty() ->
                        LineChoice(state.lineChoices, viewModel::chooseLine)

                    else -> StepPrompt(
                        state = state,
                        onSubmit = viewModel::onManualEntry,
                        onTypeManually = { if (state.step == TransferStep.QUANTITY) showQuantity = true else onManualEntryRequested() },
                        onOpenCamera = { showCamera = true },
                            onSkip = viewModel::skipStep,
                        onSearchItem = { showItemSearch = true },
                        onSelectUnit = viewModel::selectEntryUnit,
                        onReviewScanned = { viewModel.showCaptured(true) },
                        onPost = viewModel::postTransfer,
                    )
                }
            }

            state.postNotice?.let { notice ->
                when (notice.kind) {
                    PostNoticeKind.SENT ->
                        com.d365fo.mobileops.presentation.common.components.SuccessBanner(notice.message, viewModel::dismissPostNotice)
                    PostNoticeKind.QUEUED ->
                        com.d365fo.mobileops.presentation.common.components.WarningBanner(notice.message, viewModel::dismissPostNotice)
                    PostNoticeKind.REFUSED -> ErrorPanel(notice.message, viewModel::dismissPostNotice)
                }
            }
            state.error?.let { message ->
                ErrorPanel(message, viewModel::dismissError)
            }
        }
    }
    com.d365fo.mobileops.presentation.common.components.QuickNotice(state.lineNotice, viewModel::clearLineNotice)

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

@Composable
private fun StepProgress(state: TransferFlowState) {
    val sequence = stepsForTransfer(state.capture)
    val position = (sequence.indexOf(state.step) + 1).coerceAtLeast(1)

    // The tabs first, flush under the bar; the lookup bar under them.
    Column {
        StepTrail(names = sequence.map { it.shortName() }, current = position - 1)
        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 4.dp))
        }
    }
}

@Composable
private fun StepPrompt(
    state: TransferFlowState,
    onSubmit: (String) -> Unit,
    onTypeManually: () -> Unit,
    onOpenCamera: () -> Unit,
    onSkip: () -> Unit = {},
    onSearchItem: () -> Unit,
    onSelectUnit: (String) -> Unit = {},
    onReviewScanned: () -> Unit = {},
    onPost: () -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }

    // Auto-focused on every step - see the identical note on PO receiving's own StepPrompt: the
    // "goes back to Home" reports had nothing to do with focus. This device's trigger button
    // fires a genuine KEY_HOME at the OS input-dispatch level on every press, independent of
    // anything on screen - so there is no reason not to keep the cursor sitting here for whoever
    // wants to type instead of scan.
    //
    // No keyboardController.hide() any more - see the identical note on PO receiving's own
    // StepPrompt: the field is readOnly now, which structurally stops the OS from ever showing
    // the IME for it, rather than this screen racing the device's own IME auto-show.
    LaunchedEffect(state.step, state.capture.serialIds.size) {
        focusRequester.requestFocus()
    }

    // Top group weighted + scrollable, bottom group fixed - see the identical note (and the
    // live cut-off it fixes) on PO receiving's own StepPrompt. A plain SpaceBetween Column does
    // not scroll, and the quantity step's keypad alone is tall enough to push the button row
    // off the bottom edge on some screens.
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // No hint line under the field (user, 9 Nov) - the chips carry what to know.
            PromptHeroCard(prompt = promptFor(state), pills = capturePills(state)) {
            // Read-only and empty, always - see the identical note on PO receiving's own
            // StepPrompt: a scan never lands here, and typing now happens on a dedicated
            // screen. What remains is a tap target styled like the field it replaces.
            //
            // Overlaid Box, not clickable() on the field itself - see the identical note on PO
            // receiving's own StepPrompt: a read-only field's own pointer input swallows the tap.
            Box {
                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    enabled = !state.busy,
                    textStyle = if (state.step == TransferStep.QUANTITY) {
                        MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center)
                    } else {
                        MaterialTheme.typography.headlineSmall
                    },
                    shape = HeroFieldShape,
                    colors = heroFieldColors(),
                    // Every step but quantity takes a scan.
                    leadingIcon = if (state.step != TransferStep.QUANTITY) ({ BarcodeFieldIcon() }) else null,
                    trailingIcon = { HeroEnterBadge() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .focusRequester(focusRequester),
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(enabled = !state.busy, onClick = onTypeManually),
                )
            }
                        // Inside the card, as on every other scan screen.
            com.d365fo.mobileops.presentation.common.components.HeroCameraButton(
                onClick = onOpenCamera,
                enabled = !state.busy,
                // Search is only meaningful with a set of lines in hand to filter.
                extraLabel = "Search items".takeIf { state.step == TransferStep.ITEM && state.openLines.isNotEmpty() },
                onExtra = onSearchItem,
            )
            }
            if (state.step == TransferStep.ITEM && state.captured.isNotEmpty()) {
                ScannedSoFar(state, onReviewScanned)
            }
            // In the scrolling group with the card, not the fixed bottom one: with up to six unit
            // chips there, the bottom group squeezed the card - its rounded bottom was cut off
            // and this button sat against the cut edge, reading as one control (feedback
            // f0eda53c "controls merged", 10 Oct).
            // Offered as a button, never pre-filled: pre-filling has an operator confirm the
            // full quantity by reflex, which is exactly how a short shipment or receipt gets
            // booked in full.
            if (state.step == TransferStep.QUANTITY) {
                state.suggestedQuantity?.let { outstanding ->
                    com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton(
                        onClick = { onSubmit(plain(outstanding)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) {
                        Text(
                            if (state.capture.mode == TransferMode.SHIP) {
                                "Ship all outstanding (${plain(outstanding)})"
                            } else {
                                "Receive all outstanding (${plain(outstanding)})"
                            },
                        )
                    }
                }
                state.capture.line?.takeIf { it.units.size > 1 }?.let { line ->
                    com.d365fo.mobileops.presentation.common.components.UnitChips(
                        units = line.units,
                        lineUnit = line.unitId,
                        selected = state.entryUnit ?: line.unitId,
                        onSelect = onSelectUnit,
                    )
                }
            }
        }

        if (com.d365fo.mobileops.domain.model.canSkipTransferStep(state.step, state.capture)) {
            OutlinedButton(
                onClick = onSkip,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = WarehouseDimens.MinTouchTarget),
            ) { Text("Skip - not required for this item") }
        }

        // The dock: review what is scanned, and ship or receive it with a hold.
        if (state.captured.isNotEmpty()) {
            com.d365fo.mobileops.presentation.common.components.FlowDock(
                modifier = Modifier.bleed(16.dp).padding(top = 8.dp),
            ) {
                com.d365fo.mobileops.presentation.common.components.DockOutlinedButton(
                    onClick = onReviewScanned,
                    enabled = !state.busy,
                    icon = Icons.AutoMirrored.Filled.ListAlt,
                    text = "Review",
                    modifier = Modifier.weight(0.4f),
                )
                com.d365fo.mobileops.presentation.common.components.HoldButton(
                    label = if (state.capture.mode == TransferMode.SHIP) "Hold to ship" else "Hold to receive",
                    onConfirm = onPost,
                    enabled = !state.busy,
                    modifier = Modifier.weight(0.6f),
                )
            }
        } else {
            Box(Modifier.padding(bottom = WarehouseDimens.ScreenPadding))
        }
    }
}

/** What is picked or scanned so far, latest first, under the item step's card. */
@Composable
private fun ScannedSoFar(state: TransferFlowState, onOpen: () -> Unit) {
    val shipping = state.capture.mode == TransferMode.SHIP
    val total = state.order?.outstandingLineCount?.takeIf { it > 0 }
    com.d365fo.mobileops.presentation.common.components.ListHeader(
        icon = Icons.AutoMirrored.Filled.ListAlt,
        title = if (shipping) "Picked" else "Scanned",
        count = state.captured.size,
        trailing = total?.let { "of $it ${if (it == 1) "line" else "lines"}" },
    )
    com.d365fo.mobileops.presentation.common.components.ListCard {
        state.captured.reversed().forEachIndexed { i, entry ->
            if (i > 0) com.d365fo.mobileops.presentation.common.components.RowDivider()
            com.d365fo.mobileops.presentation.common.components.LineRow(
                title = entry.itemId,
                description = entry.itemName,
                lines = listOfNotNull(
                    listOfNotNull(
                        entry.variantLabel.takeIf { it.isNotBlank() },
                        entry.batchId?.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                    ).joinToString(" · ").takeIf { it.isNotBlank() },
                    when (entry.serialIds.size) {
                        0 -> null
                        1 -> "Serial ${entry.serialIds[0]}"
                        else -> "${entry.serialIds.size} serials"
                    },
                    entry.locationId?.takeIf { it.isNotBlank() }?.let { "Location $it" },
                ),
                lead = { com.d365fo.mobileops.presentation.common.components.LeadDot(com.d365fo.mobileops.presentation.common.components.Lead.Done, icon = Icons.Default.Check) },
                trailing = { com.d365fo.mobileops.presentation.common.components.QtyTrail(plain(entry.quantity), entry.unitSymbol) },
                onClick = onOpen,
            )
        }
    }
}

/** What the scan is working on, as pills in the scan card. */
private fun capturePills(state: TransferFlowState): List<Pair<androidx.compose.ui.graphics.vector.ImageVector?, String>> {
    val c = state.capture
    return buildList {
        // Labelled chips (user, 9 Nov): what each value is, not just the value.
        c.transferId?.let { add(Icons.Default.SwapHoriz to "Transfer $it") }
        state.order?.let { o ->
            if (o.fromWarehouseId != null && o.toWarehouseId != null) {
                add(Icons.Default.Route to "Route ${o.fromWarehouseId} to ${o.toWarehouseId}")
            }
        }
        // Shipping asks the bin before the item, so it stays on the item step too.
        if (c.mode == TransferMode.SHIP || state.step != TransferStep.ITEM) c.locationId?.let { add(Icons.Default.LocationOn to "Location $it") }
        if (state.step != TransferStep.ITEM) {
            c.line?.let { add(com.d365fo.mobileops.presentation.common.components.BarcodeGlyph to "Item ${it.itemId} · ${it.itemName}") }
            c.line?.variant?.label?.takeIf { it.isNotBlank() }?.let { add(null to "Variant $it") }
            c.quantity?.let { add(Icons.Default.Numbers to "Qty " + com.d365fo.mobileops.util.Formatters.qtyUnit(it, c.unitSymbol)) }
            c.batchId?.let { add(Icons.Default.Tag to "Batch $it") }
            if (c.serialIds.isNotEmpty()) add(Icons.Default.QrCode2 to "Serials ${c.serialIds.size}")
        }
        // On the batch step, which batch the shipment carried (receive) or the order asked for
        // (ship) - a fact to act on, as a chip rather than a hint line.
        if (state.step == TransferStep.BATCH) c.line?.let { line ->
            when {
                c.mode == TransferMode.RECEIVE && line.shippedBatches.isNotEmpty() ->
                    line.shippedBatches.forEach { add(Icons.Default.Tag to "Shipped batch ${it.batchId} (${plain(it.quantity)})") }
                c.mode == TransferMode.SHIP && line.lineBatchId != null ->
                    add(Icons.Default.Tag to "Ordered batch ${line.lineBatchId}")
            }
        }
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
private fun ErrorPanel(message: String, onDismiss: () -> Unit) {
    ErrorBanner(message = message, onDismiss = onDismiss)
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

/** The step's name in the step trail - short, so the whole flow fits on one line. */
private fun TransferStep.shortName(): String = when (this) {
    TransferStep.TRANSFER_ORDER -> "Transfer"
    TransferStep.ITEM -> "Item"
    TransferStep.QUANTITY -> "Qty"
    TransferStep.LOCATION -> "Location"
    TransferStep.BATCH -> "Batch"
    TransferStep.SERIAL -> "Serial"
    TransferStep.CONFIRM -> "Confirm"
}
