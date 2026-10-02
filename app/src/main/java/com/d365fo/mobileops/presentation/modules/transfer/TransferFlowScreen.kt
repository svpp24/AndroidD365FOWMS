package com.d365fo.mobileops.presentation.modules.transfer

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
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
            if (state.step == TransferStep.TRANSFER_ORDER) onBack() else viewModel.back()
        }
    }
    BackHandler(onBack = stepBack)

    com.d365fo.mobileops.presentation.modules.cyclecounting.opportunity.OpportunityCountPrompt()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleFor(state.step, mode)) },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = stepBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (mode == TransferMode.SHIP) {
                        com.d365fo.mobileops.presentation.modules.cyclecounting.opportunity.ReportDiscrepancyAction(
                            locationId = state.capture.locationId ?: state.capture.line?.locationId,
                            itemId = state.capture.line?.itemId,
                        )
                    }
                    // The "view scanned so far, and remove one if it is wrong" menu - see the
                    // identical one on PO receiving.
                    if (state.captured.isNotEmpty()) {
                        IconButton(onClick = { viewModel.showCaptured(true) }) {
                            BadgedBox(badge = { Badge { Text("${state.captured.size}") } }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ListAlt,
                                    contentDescription = "Scanned lines",
                                )
                            }
                        }
                    }
                    if (state.capture.transferId != null) {
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

            state.capture.transferId?.let { CaptureSummary(state) }

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
private fun StepProgress(state: TransferFlowState) {
    val sequence = stepsForTransfer(state.capture)
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
    state: TransferFlowState,
    onSubmit: (String) -> Unit,
    onTypeManually: () -> Unit,
    onOpenCamera: () -> Unit,
    onSearchItem: () -> Unit,
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
            PromptHeroCard(prompt = promptFor(state), hint = hintFor(state)) {
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
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            // Fixed, not derived from navigationBarsPadding() - see the identical note on
            // ReceivingFlowScreen's own StepPrompt. Confirmed live: without this, the button row
            // sat flush against the true bottom edge of the glass once the nav bar was hidden.
            modifier = Modifier.padding(bottom = WarehouseDimens.ScreenPadding),
        ) {
            // Offered as a button, never pre-filled: pre-filling has an operator confirm the
            // full quantity by reflex, which is exactly how a short shipment or receipt gets
            // booked in full.
            if (state.step == TransferStep.QUANTITY) {
                state.suggestedQuantity?.let { outstanding ->
                    TextButton(onClick = { onSubmit(plain(outstanding)) }) {
                        Text(
                            if (state.capture.mode == TransferMode.SHIP) {
                                "Ship all outstanding (${plain(outstanding)})"
                            } else {
                                "Receive all outstanding (${plain(outstanding)})"
                            },
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Search is only meaningful once there is a set of lines to search - the item
                // step on an order whose lines came back inline. A large order that only has
                // one line's worth of data at a time has nothing here to filter yet.
                if (state.step == TransferStep.ITEM && state.openLines.isNotEmpty()) {
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
                                "${plain(line.remainingQuantity)} ${line.unitId} outstanding",
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
                                entry.serialIds.size.takeIf { it > 0 }
                                    ?.let { n -> if (n == 1) "1 serial" else "$n serials" },
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

        // Post is the primary action here, for the same reason as the receiving screen: this is
        // the last thing an operator sees before the transfer leaves the device.
        Button(
            onClick = onPost,
            enabled = !busy && captured.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text(if (busy) "Posting…" else "Post transfer")
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
            Field(
                "Quantity",
                capture.quantity?.let { "${plain(it)} ${capture.unitSymbol.orEmpty()}".trimEnd() },
            )
            Field("Put away to", capture.locationId)
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
    TransferStep.LOCATION -> "Put away"
    TransferStep.BATCH -> "Batch"
    TransferStep.SERIAL -> "Serial"
    TransferStep.CONFIRM -> "Confirm"
}

private fun promptFor(state: TransferFlowState): String = when (state.step) {
    TransferStep.TRANSFER_ORDER -> "Scan transfer order"
    TransferStep.ITEM -> "Scan item"
    TransferStep.QUANTITY -> "Enter quantity"
    TransferStep.LOCATION -> "Scan put-away location"
    TransferStep.BATCH -> "Scan batch"
    TransferStep.SERIAL -> state.capture.serialsOutstanding
        .takeIf { it > 1 }
        ?.let { "Scan serial ($it to go)" }
        ?: "Scan serial"
    TransferStep.CONFIRM -> "Confirm"
}

private fun hintFor(state: TransferFlowState): String? = when (state.step) {
    TransferStep.TRANSFER_ORDER -> if (state.capture.mode == TransferMode.SHIP) {
        "Shipping out of this warehouse"
    } else {
        "Receiving into this warehouse"
    }

    TransferStep.ITEM -> state.order?.outstandingLineCount
        ?.takeIf { it > 0 }
        ?.let { count ->
            // The count, never openLines.size: on a large order the lines were not fetched.
            val lines = if (count == 1) "1 line" else "$count lines"
            if (state.captured.isEmpty()) {
                "$lines outstanding"
            } else {
                "$lines outstanding · ${state.captured.size} scanned"
            }
        }

    TransferStep.QUANTITY -> state.capture.line?.let { line ->
        if (state.capture.mode == TransferMode.SHIP) {
            "${plain(line.remainingQuantity)} ${line.unitId} still to ship"
        } else {
            // Says "shipped", not "ordered", because that is the number that bounds this.
            "${plain(line.shippedQuantity)} shipped · " +
                "${plain(line.remainingQuantity)} ${line.unitId} still to receive"
        }
    }

    TransferStep.LOCATION -> state.capture.toWarehouseId?.let { "Bulk storage in warehouse $it" }

    TransferStep.SERIAL -> state.capture.quantity?.let { "One serial per unit — ${plain(it)} needed" }

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
    TextEntryScreen(
        title = promptFor(state),
        hint = hintFor(state),
        keyboardType = if (state.step == TransferStep.QUANTITY) KeyboardType.Decimal else KeyboardType.Text,
        showNumericKeypad = state.step == TransferStep.QUANTITY,
        onBack = onBack,
        onSubmit = { value ->
            viewModel.onManualEntry(value)
            onBack()
        },
    )
}
