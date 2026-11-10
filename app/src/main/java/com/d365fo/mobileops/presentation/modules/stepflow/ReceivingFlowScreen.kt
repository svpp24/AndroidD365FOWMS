package com.d365fo.mobileops.presentation.modules.stepflow

import com.d365fo.mobileops.presentation.common.focus.BandKind
import com.d365fo.mobileops.presentation.common.focus.BandState
import com.d365fo.mobileops.presentation.common.focus.FocusCenter
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.QrCode2
import com.d365fo.mobileops.presentation.common.components.bleed
import com.d365fo.mobileops.presentation.common.components.BarcodeGlyph
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import androidx.compose.material3.ButtonDefaults
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.d365fo.mobileops.domain.model.PurchaseOrderLine
import com.d365fo.mobileops.domain.model.ReceivingStep
import com.d365fo.mobileops.domain.model.stepsFor
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.components.HeroEnterBadge
import com.d365fo.mobileops.presentation.common.components.HeroFieldShape
import com.d365fo.mobileops.presentation.common.components.heroFieldColors
import com.d365fo.mobileops.presentation.common.components.TextEntryScreen
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.util.Formatters
import java.math.BigDecimal

/**
 * One prompt at a time, the way a WMS handheld asks for it - back to this flow's original shape
 * after a scan-first, card-and-list redesign didn't hold up in practice. Every capture still goes
 * through the exact same [ReceivingFlowViewModel] and the exact same one-field-validated-against-
 * D365FO-before-advancing rule this flow has always used - this file is a visual layer, not a
 * rules change. [ReceivingStep], [ReceivingFlowViewModel] and the capture/validation logic they
 * drive are untouched by the redesign, and untouched by reverting it.
 *
 * The one thing worth carrying forward from the scan-first attempt: the numeric keypad on the
 * quantity step. It has nothing to do with the screen-per-field structure either way, and it was
 * a real improvement on its own - see [NumericKeypad].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivingFlowScreen(
    onBack: () -> Unit,
    onManualEntryRequested: () -> Unit,
    viewModel: ReceivingFlowViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    var showItemSearch by remember { mutableStateOf(false) }

    // Guarded everywhere it can be triggered - not just BackHandler below, but the top bar's own
    // IconButton onClick too (see its onClick further down). This handheld's physical scan
    // trigger fires a phantom back invocation of some kind on its own, roughly a second or two
    // after the scan - see HidScannerBridge.justScanned for how this was traced and confirmed
    // live. Live tracing ruled out both a real KEYCODE_BACK KeyEvent and Compose's own
    // BackHandler/predictive-back callback as the delivery mechanism - the phantom has shown up
    // with neither logged, which points at a phantom touch landing on the icon's own tap target
    // rather than a system back invocation at all. Without this guard on both paths, scanning a
    // PO that turns out invalid leaves the flow on this same step, and the phantom pops the whole
    // screen to Home before anyone can read the error that was just shown.
    val stepBack = {
        // Also gated on posting/postOutcome - see the identical note on the top bar's own back
        // icon and actions further down, on why leaving mid-post has to go through PostStatus's
        // own Done button instead.
        if (!viewModel.justScanned() && !state.posting && state.postOutcome == null) {
            when {
                // Back from "Scanned so far" closes the list - stepping the flow back from under
                // it cleared the order and left an empty list on the PO step (feedback, 6 Oct).
                state.showingCaptured -> viewModel.showCaptured(false)
                state.step == ReceivingStep.PURCHASE_ORDER -> onBack()
                else -> viewModel.back()
            }
        }
    }
    BackHandler(onBack = stepBack)

    var confirmDiscard by remember { mutableStateOf(false) }
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

    // The quantity step opens the number pad by itself, once per line: Back from the pad lands
    // on the step (unit chips, scan) without bouncing straight back in. Waits out the scanner's
    // phantom back (see stepBack above) so the pad isn't closed the moment it opens.
    var keypadOpenedFor by rememberSaveable { mutableStateOf<String?>(null) }
    // The shared quantity popup (QuantityPicker), shown over this screen - not the full typing screen.
    var showQuantity by remember { mutableStateOf(false) }
    val quantityKey = if (state.step == ReceivingStep.QUANTITY && !state.busy && state.lineChoices.isEmpty() &&
        !state.showingCaptured && !state.posting && state.postOutcome == null
    ) {
        "${state.capture.line?.lineNumber}:${state.capture.item?.itemNumber}:${state.captured.size}"
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

    if (showQuantity && state.step == ReceivingStep.QUANTITY) {
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
                    l.purchaseUnit,
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
                        lineUnit = l.purchaseUnit,
                        selected = state.entryUnit ?: l.purchaseUnit,
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

    // Focus design (brief sections 6 and 9), on the existing steps: the band says what the last
    // scan did, the centre shows the order or the line in hand, the pill takes a typed code.
    var showTodays by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val inPost = state.posting || state.postOutcome != null
    val scannedLines = state.captured.map { it.lineNumber }.distinct().size
    val total = state.outstandingLineCount
    // Camera, search and Start over: the flow's extra tools, in one menu on the top bar.
    val toolsMenu: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = if (inPost || state.showingCaptured) null else { {
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
                if (state.step == ReceivingStep.ITEM && state.linesInline && state.openLines.isNotEmpty()) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Search PO items") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        onClick = { menuOpen = false; showItemSearch = true },
                        modifier = Modifier.heightIn(min = 56.dp),
                    )
                }
                if (state.capture.purchId != null) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Start over") },
                        leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            // Asks first when anything is scanned - see DiscardScannedDialog - and
                            // ignores the scanner's phantom tap (see stepBack).
                            if (!viewModel.justScanned()) {
                                if (state.captured.isEmpty()) viewModel.reset() else confirmDiscard = true
                            }
                        },
                        modifier = Modifier.heightIn(min = 56.dp),
                    )
                }
            }
        }
    } }
    com.d365fo.mobileops.presentation.common.focus.FocusScreen(
        title = if (state.showingCaptured) "PO lines" else "PO receiving",
        // Hidden while a post is in flight or its summary shows - PostStatus's Done is the way out.
        onBack = if (inPost) null else stepBack,
        counter = if (state.order != null && total > 0) "$scannedLines / $total" else null,
        progress = if (state.order != null && total > 0) scannedLines.toFloat() / total else null,
        band = if (inPost || state.showingCaptured) null else receivingBand(state),
        remaining = if (!inPost && !state.showingCaptured && state.step == ReceivingStep.ITEM && state.captured.isNotEmpty() && total > 0) {
            (total - scannedLines).coerceAtLeast(0).let { "$it ${if (it == 1) "line" else "lines"} left" }
        } else {
            null
        },
        scanPill = if (inPost || state.showingCaptured || state.lineChoices.isNotEmpty()) null else {
            { com.d365fo.mobileops.presentation.common.focus.ScanPillField(enabled = !state.busy) }
        },
        topBarTrailing = toolsMenu,
        actions = receivingActions(
            state = state,
            showTodays = showTodays,
            onToggleTodays = { showTodays = !showTodays },
            onLines = { viewModel.showCaptured(true) },
            onPost = viewModel::postReceipt,
            onUndo = stepBack,
            onSkip = viewModel::skipStep,
        ),
    ) {
        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
        }
        when {
            inPost -> Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                PostStatus(state = state, onDone = viewModel::dismissPostOutcome, onRetry = viewModel::retryPost)
            }
            state.showingCaptured -> Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                CapturedLines(
                    captured = state.captured,
                    busy = state.busy,
                    onRemove = viewModel::removeCaptured,
                    onAdjustQuantity = viewModel::adjustCapturedQuantity,
                    onClose = { viewModel.showCaptured(false) },
                    onPost = viewModel::postReceipt,
                )
            }
            state.lineChoices.isNotEmpty() -> Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                LineChoice(state.lineChoices, viewModel::chooseLine)
            }
            state.step == ReceivingStep.PURCHASE_ORDER && showTodays && state.todaysOrders.isNotEmpty() ->
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    state.todaysOrders.forEach { order ->
                        com.d365fo.mobileops.presentation.common.focus.FocusListRow(
                            title = order.purchId,
                            subtitle = order.vendorAccount,
                            titleIsCode = true,
                            chevron = true,
                            onClick = { if (!state.busy) { showTodays = false; viewModel.pickTodaysOrder(order.purchId) } },
                        )
                    }
                }
            else -> ReceivingCenter(state, onQuantityTap = { showQuantity = true })
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

/**
 * Lets the operator find a line by typing part of its item name or ID instead of scanning - see
 * the identical [ItemSearchSheet] on TransferFlowScreen and the same reasoning.
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
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = 520.dp)
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
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                // weight(1f) so a long item name wraps within its own share of
                                // the row instead of overlapping or crowding out the quantity
                                // column entirely - confirmed live: without it, a name long
                                // enough to reach the row's natural width pushed the quantity
                                // text on top of it on one row and off past the row's own bounds
                                // (rendered but invisible) on another.
                                Column(modifier = Modifier.weight(1f)) {
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
                                // Remaining nets out what this device has already banked or
                                // staged for this line - see ReceivingFlowViewModel.sessionRemaining
                                // - so it can read lower than ordered even though nothing has
                                // been registered by D365FO yet.
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "${plain(remainingFor(line))} / ${plain(line.orderedQuantity)}",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        text = "remaining / total",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/**
 * Shown in place of the step prompt from the moment "Register scanned so far" (or the captured-
 * lines sheet's own Post button) is tapped, through to a summary of what happened - see
 * [ReceivingFlowViewModel.postReceipt]'s class doc for why this no longer silently resets
 * straight back to a fresh order prompt.
 */
@Composable
private fun PostStatus(
    state: ReceivingFlowState,
    onDone: () -> Unit,
    onRetry: () -> Unit,
) {
    val outcome = state.postOutcome

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (outcome == null) {
            CircularProgressIndicator()
            Text(
                text = "Registering ${state.captured.size} " +
                    (if (state.captured.size == 1) "line" else "lines") + "…",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            // Post immediately can take a minute or two after an AOS restart - see
            // LIVE_POST_NOW_TIMEOUT_MS. Said only once it is slow, so a normal post stays quiet.
            var slow by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(10_000L)
                slow = true
            }
            if (slow) {
                Text(
                    text = "D365FO is still working on it. This can take up to 2 minutes - please wait.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
                )
            }
        } else {
            val rejected = outcome.rejectedReason
            Icon(
                when {
                    rejected != null -> Icons.Default.ErrorOutline
                    outcome.postedLive -> Icons.Default.CloudDone
                    else -> Icons.Default.CloudQueue
                },
                contentDescription = null,
                tint = when {
                    rejected != null -> MaterialTheme.colorScheme.error
                    outcome.postedLive -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.tertiary
                },
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = when {
                    rejected != null -> "Not registered"
                    // Sent, not posted: D365FO stages it, and its batch job (or Process pending) registers it.
                    outcome.grnId != null -> "Received - GRN ${outcome.grnId}"
                    outcome.postedNow -> "Registered in D365FO"
                    outcome.postedLive -> "Sent to D365FO"
                    else -> "Saved on this device"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = "${outcome.lineCount} " + (if (outcome.lineCount == 1) "line" else "lines") +
                    " · ${outcome.purchId} · ${Formatters.clockTime(outcome.postedAtEpochMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (rejected != null) {
                Text(
                    text = "Refused: $rejected. Fix it in Activity.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
                )
            } else if (outcome.postedNow) {
                // Registered there and then - nothing more to say, and certainly not that
                // D365FO "did not answer in time", which the branch below used to show here.
            } else if (outcome.postedLive) {
                Text(
                    text = "Posts in a few minutes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
                )
            } else {
                val online = com.d365fo.mobileops.presentation.common.components.LocalSyncStatus.current?.fullyOnline != false
                Text(
                    text = if (outcome.held) {
                        "Posting is off - held here."
                    } else if (online) {
                        // Not "did not answer in time": this branch is also what a receipt saved
                        // offline shows once the connection is back - by then it may already be in
                        // D365FO (seen 11 Oct, PO 161714). Activity has its real status.
                        "Queued - see Activity."
                    } else {
                        "Offline - sent when back."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
                )
                // Nothing to retry while posting is switched off - it is held, not failing.
                if (!outcome.held) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.padding(top = 16.dp).heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) {
                        Text("Retry now")
                    }
                }
            }
            Button(
                onClick = onDone,
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
            ) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun LineChoice(lines: List<PurchaseOrderLine>, onPick: (PurchaseOrderLine) -> Unit) {
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
                                "${com.d365fo.mobileops.util.Formatters.qtyUnit(line.outstandingQuantity, line.purchaseUnit)} outstanding",
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
 * wrong, before posting the whole receipt. The WMS-style answer to "let me check what I've done
 * so far": one tap from the badge icon in the top bar, not buried per line.
 */
@Composable
private fun CapturedLines(
    captured: List<CapturedLine>,
    busy: Boolean,
    onRemove: (Int) -> Unit,
    onAdjustQuantity: (Int, Int) -> Unit,
    onClose: () -> Unit,
    onPost: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        com.d365fo.mobileops.presentation.common.components.ListHeader(
            icon = Icons.AutoMirrored.Filled.ListAlt,
            title = "Scanned so far",
            count = captured.size,
        )
        Text(
            text = "Check the lines - posting can't be undone.",
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
                            // is fixed by the serial list - see the identical note on
                            // TransferFlowScreen's own CapturedLines.
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
                                entry.variantLabel.takeIf { it.isNotBlank() },
                                entry.locationId?.takeIf { it.isNotBlank() },
                                entry.batchId?.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                                // A new batch's expiry, as typed - checked here before posting.
                                entry.batchExpiryDate?.takeIf { it.isNotBlank() }?.let { iso ->
                                    "expires " + iso.split('-').let { p -> if (p.size == 3) "${p[2]}-${p[1]}-${p[0]}" else iso }
                                },
                                // The serial itself, as transfer does.
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
                        }
                        IconButton(onClick = { onRemove(index) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove")
                        }
                    }
                }
            }
        }

        // Side by side, the same order as every dialog: the action on the left, going back on
        // the right (feedback, 10 Oct).
        com.d365fo.mobileops.presentation.common.components.FlowDock(modifier = Modifier.bleed(16.dp)) {
            com.d365fo.mobileops.presentation.common.components.DockOutlinedButton(
                onClick = onClose,
                enabled = !busy,
                icon = BarcodeGlyph,
                text = "Scan more",
                modifier = Modifier.weight(0.4f),
            )
            com.d365fo.mobileops.presentation.common.components.HoldButton(
                label = if (busy) "Posting…" else "Hold to post",
                onConfirm = onPost,
                enabled = !busy && captured.isNotEmpty(),
                modifier = Modifier.weight(0.6f),
            )
        }
    }
}

/** The result band: the error, else what the last action did, else where the flow stands. */
private fun receivingBand(state: ReceivingFlowState): BandState? {
    state.error?.let { return bandOf(BandKind.Bad, it) }
    state.lineNotice?.let { return bandOf(if (it.hint) BandKind.Warn else BandKind.Ok, it.text) }
    return when {
        state.step == ReceivingStep.PURCHASE_ORDER -> BandState(BandKind.Info, "Ready", "No PO open")
        state.step == ReceivingStep.ITEM && state.captured.isEmpty() && state.order != null -> state.order.let { o ->
            BandState(
                BandKind.Info,
                "PO found",
                listOfNotNull(o.purchId, (o.vendorName.takeIf { it.isNotBlank() } ?: o.vendorAccount).takeIf { it.isNotBlank() }).joinToString(" · "),
            )
        }
        else -> null
    }
}

/**
 * A message as a band: "HSN010 is not on BSP-PO-1 - nothing was added" reads as the title and,
 * after the dash, the detail line.
 */
internal fun bandOf(kind: BandKind, message: String): BandState {
    val parts = message.split(" - ", limit = 2)
    return BandState(kind, parts[0].trim(), parts.getOrNull(1)?.trim())
}

/** The bottom buttons for where the flow stands - two at most, the safe one left. */
private fun receivingActions(
    state: ReceivingFlowState,
    showTodays: Boolean,
    onToggleTodays: () -> Unit,
    onLines: () -> Unit,
    onPost: () -> Unit,
    onUndo: () -> Unit,
    onSkip: () -> Unit,
): (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? {
    if (state.posting || state.postOutcome != null || state.showingCaptured || state.lineChoices.isNotEmpty()) return null
    val step = state.step
    return when {
        step == ReceivingStep.PURCHASE_ORDER -> if (state.todaysOrders.isEmpty()) null else {
            {
                com.d365fo.mobileops.presentation.common.focus.FocusButton(
                    if (showTodays) "Close list" else "Today's POs",
                    onClick = onToggleTodays,
                    icon = Icons.Default.CalendarMonth,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        step == ReceivingStep.ITEM -> if (state.captured.isEmpty()) null else {
            {
                com.d365fo.mobileops.presentation.common.focus.FocusButton(
                    "Lines", onClick = onLines, icon = Icons.AutoMirrored.Filled.ListAlt,
                    enabled = !state.busy, modifier = Modifier.weight(1f),
                )
                com.d365fo.mobileops.presentation.common.focus.HoldToConfirmButton(
                    "Hold to post", onConfirm = onPost, enabled = !state.busy, modifier = Modifier.weight(1f),
                )
            }
        }
        // Mid-line: Undo steps back one; Skip only where D365FO doesn't need the value.
        else -> {
            {
                com.d365fo.mobileops.presentation.common.focus.FocusButton(
                    "Undo", onClick = onUndo, icon = Icons.AutoMirrored.Filled.Undo,
                    enabled = !state.busy, modifier = Modifier.weight(1f),
                )
                if (com.d365fo.mobileops.domain.model.canSkipStep(step, state.capture)) {
                    com.d365fo.mobileops.presentation.common.focus.FocusButton(
                        "Skip", onClick = onSkip, primary = true, enabled = !state.busy, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The centre: today's POs on the order step, the order's size before the first line, the last
 * line received after it, and the item in hand with what is being asked (a caption only, never
 * "Scan ...") on the line's own steps.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ReceivingCenter(state: ReceivingFlowState, onQuantityTap: () -> Unit) {
    val c = state.capture
    when (state.step) {
        ReceivingStep.PURCHASE_ORDER -> FocusCenter(
            caption = "Purchase orders today",
            code = state.todaysOrders.size.toString(),
        )
        ReceivingStep.ITEM -> {
            val last = state.captured.lastOrNull()
            if (last == null) {
                val units = state.openLines.takeIf { state.linesInline && it.isNotEmpty() }
                    ?.fold(BigDecimal.ZERO) { sum, l -> sum + l.outstandingQuantity }
                FocusCenter(
                    caption = "To receive",
                    code = when (state.outstandingLineCount) {
                        0 -> "Nothing left"
                        1 -> "1 line"
                        else -> "${state.outstandingLineCount} lines"
                    },
                    description = units?.takeIf { state.outstandingLineCount > 0 }?.let { "${plain(it)} units" },
                )
            } else {
                FocusCenter(
                    caption = "Last scanned",
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
            val item = c.item
            val line = c.line
            FocusCenter(
                caption = when (state.step) {
                    ReceivingStep.QUANTITY -> "Quantity"
                    ReceivingStep.LOCATION -> "Location"
                    ReceivingStep.BATCH -> "Batch"
                    ReceivingStep.BATCH_EXPIRY_DATE -> "New batch · expiry"
                    ReceivingStep.SERIAL -> "Serial"
                    ReceivingStep.PACKING_SLIP -> "Delivery note"
                    else -> titleFor(state.step)
                },
                code = item?.itemNumber ?: line?.itemId.orEmpty(),
                description = listOfNotNull(
                    item?.productName?.takeIf { it.isNotBlank() && !it.equals(item.itemNumber, ignoreCase = true) },
                    line?.variant?.label?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").takeIf { it.isNotBlank() },
                quantity = c.quantity?.let { plain(it) } ?: "–".takeIf { state.step == ReceivingStep.QUANTITY },
                unitLine = when (state.step) {
                    // How much is left on the line - a figure, not an instruction.
                    ReceivingStep.QUANTITY -> line?.let { l ->
                        val left = state.suggestedQuantity ?: l.outstandingQuantity
                        "${l.purchaseUnit} · ${plain(left)} left of ${plain(l.orderedQuantity)}"
                    }
                    ReceivingStep.SERIAL -> listOfNotNull(c.unitSymbol, "${c.serialsOutstanding} serial${if (c.serialsOutstanding == 1) "" else "s"} left").joinToString(" · ")
                    else -> listOfNotNull(
                        c.unitSymbol,
                        c.batchId?.let { "Batch $it" },
                        c.locationId,
                    ).joinToString(" · ")
                },
                onQuantityTap = if (state.step == ReceivingStep.QUANTITY && !state.busy) onQuantityTap else null,
            )
        }
    }
}
private fun titleFor(step: ReceivingStep): String = when (step) {
    ReceivingStep.PURCHASE_ORDER -> "Receive"
    ReceivingStep.PACKING_SLIP -> "Delivery note"
    ReceivingStep.ITEM -> "Item"
    ReceivingStep.QUANTITY -> "Quantity"
    ReceivingStep.LOCATION -> "Location"
    ReceivingStep.BATCH -> "Batch"
    ReceivingStep.BATCH_EXPIRY_DATE -> "New batch"
    ReceivingStep.SERIAL -> "Serial"
    ReceivingStep.CONFIRM -> "Confirm"
}

private fun promptFor(state: ReceivingFlowState): String = when (state.step) {
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

private fun hintFor(state: ReceivingFlowState): String? = when (state.step) {
    // No counters under "Scan item" (feedback f77ebd0d, the same as Transfer); only the one
    // thing a worker needs to know there - that the order has nothing left.
    ReceivingStep.ITEM -> if (state.outstandingLineCount == 0 && state.captured.isEmpty() && state.order != null) {
        "Nothing left to receive on this order"
    } else {
        null
    }

    ReceivingStep.QUANTITY -> state.capture.line?.let { line ->
        // What is still left for this device - the same figure as "Receive all outstanding" -
        // not D365FO's count, which still includes what was just scanned or sent from here.
        val left = state.suggestedQuantity
        val spoken = left?.let { line.outstandingQuantity - it }?.takeIf { it.signum() > 0 }
        "${com.d365fo.mobileops.util.Formatters.qtyUnit(left ?: line.outstandingQuantity, line.purchaseUnit)} " +
            "left of ${plain(line.orderedQuantity)}" +
            (spoken?.let { " · ${plain(it)} scanned" } ?: "") +
            (state.entryUnit?.let { unit -> " · entering in $unit" } ?: "")
    }

    // No information lines under the scan card (feedback: "these kind of info not required").
    ReceivingStep.PACKING_SLIP -> null

    // Only the order's suggested bin - something to act on.
    ReceivingStep.LOCATION -> state.suggestedLocation?.let { "Order suggests $it" }

    // From the line's own rule, not the quantity: without serial number control in D365FO one
    // serial covers the whole line, and asking for one per unit would be wrong.
    ReceivingStep.SERIAL -> state.capture.quantity?.let {
        serialPrompt(state.capture.line?.rules?.serialPerUnit != false, state.capture.serialsOutstanding)
    }

    ReceivingStep.BATCH_EXPIRY_DATE ->
        "New batch - expiry DD-MM-YYYY"

    else -> null
}

private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

internal fun serialPrompt(perUnit: Boolean, outstanding: Int): String = when {
    !perUnit -> "One serial for this line"
    outstanding == 1 -> "One serial per unit — 1 more to scan"
    else -> "One serial per unit — $outstanding more to scan"
}

/**
 * The dedicated typing screen for whichever step is current, reached from [StepPrompt]'s
 * read-only field. Fetches the **same** [ReceivingFlowViewModel] instance as the flow screen
 * underneath it - see [com.d365fo.mobileops.presentation.common.AppNavGraph]'s
 * `RECEIVE_FLOW_ENTRY` route, which scopes this to the still-alive `RECEIVE_FLOW` back-stack
 * entry rather than creating a new ViewModel - so submitting here calls straight into the flow
 * already in progress and popping back lands on it mid-step, one step further along.
 */
@Composable
fun ReceivingEntryScreen(
    viewModel: ReceivingFlowViewModel,
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
        // Typing here, so "Enter", not "Scan".
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
        // The unit choices were only on the step screen, which the keypad opens over at once.
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
        // The scanner's phantom back must not close the pad - see ReceivingFlowScreen.stepBack.
        onBack = { if (!viewModel.justScanned()) close() },
        onSubmit = { value ->
            viewModel.onManualEntry(value)
            close()
        },
    )
}

private const val MAX_TODAYS_ORDERS = 6

/**
 * PO receiving's Focus states with sample data, for the layout test: 0 ready, 1 PO found,
 * 2 last scanned, 3 a batch step, 4 an error.
 */
@Composable
internal fun ReceivingFocusSample(kind: Int) {
    val order = com.d365fo.mobileops.domain.model.PurchaseOrder(
        purchId = "BSP-PO-000161763", vendorAccount = "V-000123", vendorName = "Gulf Pharma Distribution LLC",
        status = com.d365fo.mobileops.domain.model.OrderStatus.BACKORDER, siteId = "1", warehouseId = "AAC",
        deliveryDate = null, approved = true,
        tolerance = com.d365fo.mobileops.domain.model.DeliveryTolerance(null, null), lines = emptyList(),
    )
    val received = CapturedLine(1.0, "HSN002", "Handset charger 20W", BigDecimal.ONE, "Each", batchId = "E0927")
    val state = when (kind) {
        0 -> ReceivingFlowState(todaysOrders = emptyList())
        1 -> ReceivingFlowState(step = ReceivingStep.ITEM, order = order, outstandingLineCount = 2, capture = com.d365fo.mobileops.domain.model.ReceivingCapture(purchId = order.purchId))
        2 -> ReceivingFlowState(
            step = ReceivingStep.ITEM, order = order, outstandingLineCount = 2, captured = listOf(received),
            capture = com.d365fo.mobileops.domain.model.ReceivingCapture(purchId = order.purchId),
            lineNotice = com.d365fo.mobileops.presentation.common.components.QuickNoticeMessage(1, "Received 1 × HSN002"),
        )
        3 -> ReceivingFlowState(
            step = ReceivingStep.BATCH, order = order, outstandingLineCount = 2,
            capture = com.d365fo.mobileops.domain.model.ReceivingCapture(
                purchId = order.purchId, quantity = BigDecimal("12"), unitSymbol = "Pack",
                item = com.d365fo.mobileops.domain.model.ItemDimensions("BSR-11397-0", "Panadol Extra 24s", "Pack", true, false, false),
            ),
        )
        else -> ReceivingFlowState(
            step = ReceivingStep.ITEM, order = order, outstandingLineCount = 2, captured = listOf(received),
            capture = com.d365fo.mobileops.domain.model.ReceivingCapture(purchId = order.purchId),
            error = "HSN010 is not on BSP-PO-000161763 - nothing was added",
        )
    }
    val total = state.outstandingLineCount
    val scanned = state.captured.map { it.lineNumber }.distinct().size
    com.d365fo.mobileops.presentation.common.focus.FocusScreen(
        title = "PO receiving",
        onBack = {},
        counter = if (state.order != null && total > 0) "$scanned / $total" else null,
        progress = if (state.order != null && total > 0) scanned.toFloat() / total else null,
        band = receivingBand(state),
        remaining = if (state.step == ReceivingStep.ITEM && state.captured.isNotEmpty()) "${total - scanned} line left" else null,
        scanPill = { com.d365fo.mobileops.presentation.common.focus.ScanPill(onTap = {}) },
        topBarTrailing = {
            IconButton(onClick = {}, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
        },
        actions = receivingActions(state, false, {}, {}, {}, {}, {}),
    ) {
        ReceivingCenter(state, onQuantityTap = {})
    }
}