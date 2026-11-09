package com.d365fo.mobileops.presentation.modules.cyclecounting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.presentation.common.components.FlowAction
import com.d365fo.mobileops.presentation.common.components.FlowActionBar
import com.d365fo.mobileops.presentation.common.components.FlowHeader
import com.d365fo.mobileops.presentation.common.components.FlowKeyHandler
import com.d365fo.mobileops.presentation.common.components.HoldToConfirmButton
import com.d365fo.mobileops.presentation.common.components.ListRow
import com.d365fo.mobileops.presentation.common.components.ListRowValue
import com.d365fo.mobileops.presentation.common.components.NextCard
import com.d365fo.mobileops.presentation.common.components.SendTray
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton
import com.d365fo.mobileops.presentation.common.components.StatusBand
import com.d365fo.mobileops.presentation.common.components.StatusKind
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.presentation.common.theme.WarehouseType
import com.d365fo.mobileops.presentation.modules.ordercreate.CodeEntryDialog

/**
 * Counting one journal (Start counting on the list). A cycle count - the ordinary kind - opens the
 * redesigned flow ([CycleCountFlowScreen]); wall-to-wall and the one-line triggered check keep
 * their own screen ([LegacyCountingScanScreen]) until they are redesigned. One view model serves
 * both, so the choice waits until the journal is read.
 */
@Composable
fun CountingScanScreen(
    onBack: () -> Unit,
    viewModel: CountingScanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when {
        !state.loaded -> Box(Modifier.fillMaxSize().background(WarehouseStatusColors.pageComfort))
        state.layout == com.d365fo.mobileops.domain.model.CountingLayout.STANDARD -> CycleCountFlowScreen(onBack, viewModel)
        else -> LegacyCountingScanScreen(onBack, viewModel)
    }
}

/**
 * Cycle count, rebuilt on the redesign's flow layout (docs/ui-redesign-brief.md, phase 6):
 * [FlowHeader] → [StatusBand] → [NextCard] → [SendTray] → [FlowActionBar], on the comfort page.
 *
 * "Go to" names the next bin in walking order. Scan its label, then the items in it - blind, one
 * scan counts one. Scanning the label again settles the bin (an empty one at 0) and names the
 * next; scanning another bin's label settles this one and moves there. A wrong item is a Bad
 * band and adds nothing. **Hold to end count** sends the journal through the same CountSubmissions
 * path as End count on the list. Undo (F3) takes back the last +1 or settled bin, Keypad (F4, or
 * tapping the count) changes the last count, Lines (F2) lists the lines here.
 */
@Composable
internal fun CycleCountFlowScreen(onBack: () -> Unit, viewModel: CountingScanViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val flow by viewModel.flow.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var showingLines by remember { mutableStateOf(false) }

    DisposableEffect(viewModel) {
        viewModel.scannerOnScreen(true, flow = true)
        onDispose { viewModel.scannerOnScreen(false, flow = true) }
    }
    LaunchedEffect(viewModel) { viewModel.leave.collect { onBack() } }

    // Back keeps everything counted on the device - "Continue counting" on the list picks it up.
    val goBack = {
        if (!viewModel.justScanned()) {
            if (showingLines) showingLines = false else onBack()
        }
    }
    BackHandler(onBack = goBack)

    val onAction: (FlowAction) -> Unit = { action ->
        when (action) {
            FlowAction.Back -> goBack()
            FlowAction.Lines -> showingLines = !showingLines
            FlowAction.Undo -> viewModel.undo()
            FlowAction.Keypad -> viewModel.keypadOnLast()
        }
    }
    FlowKeyHandler(onAction)

    CountingDialogs(state, viewModel)
    if (typing) {
        CodeEntryDialog(
            title = "Type it in",
            label = "Bin, location or item",
            onSubmit = { typing = false; viewModel.onTyped(it) },
            onDismiss = { typing = false },
        )
    }
    if (showCamera) {
        CameraScannerSheet(
            onDecoded = { viewModel.onCameraDecoded(it); showCamera = false },
            onDismiss = { showCamera = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WarehouseStatusColors.pageComfort),
    ) {
        FlowHeader(
            title = state.journalDescription.ifBlank { viewModel.journalId },
            onBack = goBack,
            progress = flow.takeIf { it.loaded && it.total > 0 }?.let { "${it.counted} of ${it.total}" },
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
            },
        )
        Box(Modifier.fillMaxWidth().heightIn(min = 3.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = WarehouseDimens.ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val band = cycleCountBand(state, flow)
            val missed = state.message?.unexpectedItemId
            StatusBand(
                kind = band.kind,
                title = band.title,
                line = band.line,
                onUndo = if (band.undoable && viewModel.canUndo) viewModel::undo else null,
                undoKey = band.key,
                actions = if (missed != null) {
                    { SquareOutlinedButton(onClick = viewModel::recordMissAsFound) { Text("Record as found here") } }
                } else {
                    null
                },
            )

            Box(Modifier.weight(1f)) {
                if (showingLines) {
                    LinesHere(state, onPick = { line -> showingLines = false; viewModel.openLine(line) })
                } else androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                    // Fills the space, and scrolls rather than cutting text when the band is tall.
                    val cardMin = maxHeight
                    val card = cycleCountCard(state, flow)
                    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    NextCard(
                        label = card.label,
                        code = card.code,
                        description = card.description,
                        location = card.location,
                        locationLabel = card.locationLabel,
                        quantity = card.quantity,
                        quantityUnit = card.unit,
                        onQuantityClick = if (card.quantity != null) ({ viewModel.keypadOnLast() }) else null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = cardMin),
                    )
                    }
                }
            }

            if (!state.locked && flow.counted > 0) {
                HoldToConfirmButton(
                    label = "Hold to end count",
                    onConfirm = viewModel::endCount,
                    hint = if (flow.allSettled) {
                        "Hold to send ${flow.counted} counted lines"
                    } else {
                        "${flow.total - flow.counted} lines not counted yet - hold to end anyway"
                    },
                )
            }

            SendTray(
                lastSent = flow.takeIf { it.loaded && it.stops.size > 1 }
                    ?.let { f -> "${f.settled.size} of ${f.stops.size} " + (if (f.mode == StopMode.BIN) "bins" else "locations") + " done · on this device until End count" },
            )
        }

        FlowActionBar(
            onAction = onAction,
            highlighted = if (state.message?.undoable == true) FlowAction.Undo else null,
            enabled = buildSet {
                add(FlowAction.Back)
                add(FlowAction.Lines)
                if (viewModel.canUndo) add(FlowAction.Undo)
                if (flow.lastCounted != null && !state.locked) add(FlowAction.Keypad)
            },
        )
    }
}

/** Lines (F2): the journal's lines here - blind, the worker's own counts only. A tap opens one to change. */
@Composable
private fun LinesHere(state: CountingScanState, onPick: (com.d365fo.mobileops.data.local.dao.CountLine) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(WarehouseStatusColors.surface),
    ) {
        item {
            Text(
                listOfNotNull(state.selectedLocation, state.currentBin).joinToString(" › ").ifBlank { "All lines" } +
                    " (${state.lines.size})",
                style = WarehouseType.label,
                color = WarehouseStatusColors.inkMuted,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
            )
        }
        items(state.lines, key = { it.journalLineId }) { line ->
            ListRow(
                title = line.itemId,
                codeTitle = true,
                subtitles = listOfNotNull(
                    line.itemName.takeIf { it.isNotBlank() && !it.equals(line.itemId, ignoreCase = true) },
                    listOfNotNull(
                        line.variant.label.takeIf { it.isNotBlank() },
                        line.locationId?.takeIf { it.isNotBlank() },
                        line.batchId?.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                        line.serialId?.takeIf { it.isNotBlank() }?.let { "Serial $it" },
                    ).joinToString(" · ").ifBlank { null },
                ),
                status = if (line.isCounted) StatusKind.Ok else StatusKind.Idle,
                trailing = { ListRowValue(line.countedQuantity ?: if (line.isCounted) "✓" else "–") },
                onClick = if (state.locked) null else ({ onPick(line) }),
            )
            HorizontalDivider(color = WarehouseStatusColors.divider)
        }
    }
}
