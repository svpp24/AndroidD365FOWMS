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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.d365fo.mobileops.presentation.common.components.TextEntryScreen
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
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

    // Without this, system back (the hardware button, or - since this device's system bars are
    // hidden immersively, see MainActivity.hideSystemBars - an edge-swipe gesture that can fire
    // by accident while gripping the handheld to reach the physical scan trigger) falls through
    // to Navigation-Compose's default handling: an unconditional popBackStack straight to Home
    // from *any* step, silently discarding whatever was captured so far. The top bar's own back
    // icon below already knows to step back one prompt instead; this makes system back agree
    // with it rather than bypassing it.
    val stepBack = { if (state.step == ReceivingStep.PURCHASE_ORDER) onBack() else viewModel.back() }
    BackHandler(onBack = stepBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleFor(state.step)) },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = stepBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // The "view scanned so far, and remove one if it is wrong" menu - a WMS
                    // handheld's own "review" screen, one tap away for the whole visit rather
                    // than buried per line. See CapturedLines below for the remove action.
                    if (state.captured.isNotEmpty()) {
                        IconButton(onClick = { viewModel.showCaptured(true) }) {
                            BadgedBox(badge = { Badge { Text("${state.captured.size}") } }) {
                                Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = "Scanned lines")
                            }
                        }
                    }
                    if (state.capture.purchId != null) {
                        IconButton(onClick = viewModel::reset) {
                            Icon(Icons.Default.Close, contentDescription = "Start over")
                        }
                    }
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

            state.capture.purchId?.let { CaptureSummary(state) }

            // weight(1f) so StepPrompt has the screen's actual remaining height to work with -
            // it pins its scan field near the top and its buttons to the true bottom of the
            // screen, not the bottom of however tall its own content happens to be.
            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.showingCaptured -> CapturedLines(
                        captured = state.captured,
                        busy = state.busy,
                        onRemove = viewModel::removeCaptured,
                        onAdjustQuantity = viewModel::adjustCapturedQuantity,
                        onClose = { viewModel.showCaptured(false) },
                        onPost = viewModel::postReceipt,
                    )

                    state.lineChoices.isNotEmpty() ->
                        LineChoice(state.lineChoices, viewModel::chooseLine)

                    else -> StepPrompt(
                        state = state,
                        onSubmit = viewModel::onManualEntry,
                        onTypeManually = onManualEntryRequested,
                        onOpenCamera = { showCamera = true },
                        onSearchItem = { showItemSearch = true },
                    )
                }
            }

            state.error?.let { message ->
                ErrorPanel(message, viewModel::dismissError)
            }
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
                viewModel.onManualEntry(line.itemId)
            },
            onDismiss = { showItemSearch = false },
        )
    }
}

@Composable
private fun StepProgress(state: ReceivingFlowState) {
    val sequence = stepsFor(state.capture)
    val position = (sequence.indexOf(state.step) + 1).coerceAtLeast(1)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            // Placeholder rather than nothing, so the prompt does not jump under the operator's
            // eyes every time a lookup starts and finishes.
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 4.dp))
        }
        Text(
            text = "Step $position of ${sequence.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepPrompt(
    state: ReceivingFlowState,
    onSubmit: (String) -> Unit,
    onTypeManually: () -> Unit,
    onOpenCamera: () -> Unit,
    onSearchItem: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    // Auto-focused on every step - the "goes back to Home" reports this app used to get had
    // nothing to do with focus. Confirmed live long ago that this device's trigger button fires
    // a genuine KEY_HOME at the OS input-dispatch level on every press, independent of anything
    // on screen - so there is no reason not to keep the cursor sitting here for whoever wants to
    // type instead of scan.
    //
    // No keyboardController.hide() needed any more - this field is readOnly (see below), which
    // makes the OS itself refuse to show the IME for it on tap, structurally, rather than this
    // screen racing the device's own IME auto-show and having to win a timing fight every time
    // (see OnHandInquiryScreen's own note on that fight, and the bottom-tab case where a single
    // hide() call lost it outright). Typing instead of scanning is a tap away too, just to a
    // different screen now - see onTypeManually.
    LaunchedEffect(state.step, state.capture.serialIds.size) {
        focusRequester.requestFocus()
    }

    // Top group weighted + scrollable, bottom group fixed - not a plain SpaceBetween Column,
    // which does not scroll: on the quantity step the keypad alone makes the top group taller
    // than some screens, and a non-scrolling SpaceBetween silently pushes the buttons off the
    // bottom edge instead of shrinking anything (confirmed live - a real cut-off, not
    // a hypothetical). Weighting the top group means it only ever claims what is left after the
    // bottom group's own height, and scrolls internally rather than overflowing when that is not
    // enough. On a short step (no keypad) the top group does not fill its weighted space either,
    // so the same layout still reads as "field near the top, buttons pinned to the bottom."
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = promptFor(state),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            hintFor(state)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Read-only and empty, always - a scan never lands here (it goes straight to the
            // ViewModel, see HidScannerBridge), and typing now happens on a dedicated screen,
            // not inline. What remains is a tap target styled like the field it replaces: a
            // cursor to show where typing would start, and a tap that takes you there.
            //
            // clickable() layered directly onto the field's own modifier chain does not fire -
            // confirmed live: a read-only OutlinedTextField still runs its own pointer input for
            // cursor placement and swallows the tap before an outer clickable sees it. An
            // invisible Box on top, sized to match, is the reliable way to catch the tap instead.
            Box {
                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    enabled = !state.busy,
                    textStyle = if (state.step == ReceivingStep.QUANTITY) {
                        MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center)
                    } else {
                        MaterialTheme.typography.headlineSmall
                    },
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
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            // Fixed, not derived from navigationBarsPadding(): with the system nav bar hidden
            // (see MainActivity.hideSystemBars) that inset reads as zero, and a transient bar
            // swiped back in overlays content rather than resizing it - either way there is no
            // live inset value here to lean on. Confirmed live: without this, the button row sat
            // flush against the true bottom edge of the glass, no margin at all.
            modifier = Modifier.padding(bottom = WarehouseDimens.ScreenPadding),
        ) {
            // Offered as a button, never pre-filled: pre-filling has an operator confirm the
            // full quantity by reflex, which is exactly how a short delivery gets received in
            // full.
            if (state.step == ReceivingStep.QUANTITY) {
                state.suggestedQuantity?.let { outstanding ->
                    TextButton(onClick = { onSubmit(plain(outstanding)) }) {
                        Text("Receive all outstanding (${plain(outstanding)})")
                    }
                }
                state.scannedPackSize?.let { pack ->
                    Text(
                        text = "Scanned barcode is a pack of ${plain(pack)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = WarehouseStatusColors.text3,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // See the identical note on TransferFlowScreen's StepPrompt - only meaningful
                // once there is a set of lines in hand to search.
                if (state.step == ReceivingStep.ITEM && state.linesInline && state.openLines.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onSearchItem,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search by item name or ID")
                    }
                }
                OutlinedButton(
                    onClick = onOpenCamera,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = "Scan with camera")
                }
            }
        }
    }
}

/**
 * Lets the operator find a line by typing part of its item name or ID instead of scanning - see
 * the identical [ItemSearchSheet] on TransferFlowScreen and the same reasoning.
 */
@Composable
private fun ItemSearchSheet(
    lines: List<PurchaseOrderLine>,
    onPick: (PurchaseOrderLine) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(query, lines) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            lines
        } else {
            lines.filter {
                it.itemId.contains(needle, ignoreCase = true) ||
                    it.itemName.contains(needle, ignoreCase = true)
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
                            ) {
                                Column {
                                    Text(text = line.itemId, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = line.itemName,
                                        style = MaterialTheme.typography.bodyMedium,
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
                                "${plain(line.outstandingQuantity)} ${line.purchaseUnit} outstanding",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = line.itemName.ifBlank { line.itemId },
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
        Text(
            text = "Scanned so far (${captured.size})",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Held on this device until you post it. Check every line before you do — " +
                "posting cannot be undone from here.",
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
                                    text = "${plain(entry.quantity)} ${entry.unitSymbol}",
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
                                entry.locationId?.takeIf { it.isNotBlank() },
                                entry.batchId?.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                                entry.serialIds.size
                                    .takeIf { it > 0 }
                                    ?.let { count -> if (count == 1) "1 serial" else "$count serials" },
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

        Button(
            onClick = onPost,
            enabled = !busy && captured.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text(if (busy) "Posting…" else "Post receipt")
        }
        OutlinedButton(
            onClick = onClose,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text("Back to scanning")
        }
    }
}

/**
 * The trail so far, growing one field per step rather than stopping at quantity - by the batch
 * or serial prompt an operator has usually forgotten which item they are on three fields back,
 * and re-scrolling to the confirm screen to check is a worse answer than just leaving the trail
 * visible the whole way through. Item name alongside the code for the same reason: a code alone
 * confirms nothing to someone who does not have the order sheet memorised. Labelled rows, not
 * one dot-separated line - "Item" and "Batch" read at a glance; a string of values with nothing
 * saying which is which makes an operator work out which number was the quantity.
 */
@Composable
private fun CaptureSummary(state: ReceivingFlowState) {
    val capture = state.capture
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
            Field("Purchase order", capture.purchId)
            Field("Item", capture.item?.let { "${it.itemNumber} — ${it.productName}" })
            Field(
                "Quantity",
                capture.quantity?.let { "${plain(it)} ${capture.unitSymbol.orEmpty()}".trimEnd() },
            )
            Field("Put away to", capture.locationId)
            Field("Batch", capture.batchId)
            if (capture.batchIsNew == true) {
                Field("Expiry date", capture.batchExpiryDate)
            }
            if (capture.serialIds.isNotEmpty()) {
                val count = capture.serialIds.size
                Field("Serials", "$count ${if (count == 1) "serial" else "serials"}")
            }
        }
    }
}

@Composable
private fun ErrorPanel(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("OK") }
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

private fun titleFor(step: ReceivingStep): String = when (step) {
    ReceivingStep.PURCHASE_ORDER -> "Receive"
    ReceivingStep.PACKING_SLIP -> "Delivery note"
    ReceivingStep.ITEM -> "Item"
    ReceivingStep.QUANTITY -> "Quantity"
    ReceivingStep.LOCATION -> "Put away"
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
    ReceivingStep.LOCATION -> "Scan put-away location"
    ReceivingStep.BATCH -> "Scan batch"
    ReceivingStep.BATCH_EXPIRY_DATE -> "Expiry date"
    ReceivingStep.SERIAL -> {
        val remaining = state.capture.serialsOutstanding
        if (remaining > 1) "Scan serial ($remaining to go)" else "Scan serial"
    }
    ReceivingStep.CONFIRM -> "Confirm"
}

private fun hintFor(state: ReceivingFlowState): String? = when (state.step) {
    ReceivingStep.ITEM -> state.outstandingLineCount
        .takeIf { it > 0 }
        ?.let { count ->
            val lines = if (count == 1) "1 line" else "$count lines"
            if (state.captured.isEmpty()) {
                "$lines outstanding on this order"
            } else {
                "$lines outstanding · ${state.captured.size} scanned"
            }
        }

    ReceivingStep.QUANTITY -> state.capture.line?.let { line ->
        "${plain(line.outstandingQuantity)} ${line.purchaseUnit} outstanding of " +
            "${plain(line.orderedQuantity)} ordered"
    }

    ReceivingStep.PACKING_SLIP ->
        "The vendor's note number, from the paperwork the driver handed over"

    ReceivingStep.LOCATION -> listOfNotNull(
        state.capture.warehouseId?.let { "Bulk storage location in warehouse $it" },
        state.suggestedLocation?.let { "Order suggests $it" },
    ).joinToString(" · ").takeIf { it.isNotBlank() }

    ReceivingStep.SERIAL -> state.capture.quantity?.let { qty ->
        "One serial per unit — ${plain(qty)} needed"
    }

    ReceivingStep.BATCH_EXPIRY_DATE ->
        "Batch ${state.capture.batchId.orEmpty()} is new — type the date as YYYY-MM-DD"

    else -> null
}

private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

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
    TextEntryScreen(
        title = promptFor(state),
        hint = hintFor(state),
        keyboardType = if (state.step == ReceivingStep.QUANTITY) KeyboardType.Decimal else KeyboardType.Text,
        showNumericKeypad = state.step == ReceivingStep.QUANTITY,
        onBack = onBack,
        onSubmit = { value ->
            viewModel.onManualEntry(value)
            onBack()
        },
    )
}
