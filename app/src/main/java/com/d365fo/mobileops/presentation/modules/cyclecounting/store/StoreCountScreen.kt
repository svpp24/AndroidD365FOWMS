package com.d365fo.mobileops.presentation.modules.cyclecounting.store

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.data.repository.OrderVariant
import com.d365fo.mobileops.presentation.common.components.FlowAction
import com.d365fo.mobileops.presentation.common.components.FlowActionBar
import com.d365fo.mobileops.presentation.common.components.FlowHeader
import com.d365fo.mobileops.presentation.common.components.FlowKeyHandler
import com.d365fo.mobileops.presentation.common.components.HoldToConfirmButton
import com.d365fo.mobileops.presentation.common.components.ListRow
import com.d365fo.mobileops.presentation.common.components.ListRowValue
import com.d365fo.mobileops.presentation.common.components.LocalSyncStatus
import com.d365fo.mobileops.presentation.common.components.NextCard
import com.d365fo.mobileops.presentation.common.components.QuantityPicker
import com.d365fo.mobileops.presentation.common.components.SendTray
import com.d365fo.mobileops.presentation.common.components.SquareButton
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton
import com.d365fo.mobileops.presentation.common.components.StatusBand
import com.d365fo.mobileops.presentation.common.components.StatusKind
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.presentation.common.theme.WarehouseType
import com.d365fo.mobileops.presentation.modules.cyclecounting.CountBand
import com.d365fo.mobileops.presentation.modules.cyclecounting.CountCard
import com.d365fo.mobileops.presentation.modules.cyclecounting.countText
import com.d365fo.mobileops.presentation.modules.cyclecounting.nLines
import com.d365fo.mobileops.presentation.modules.cyclecounting.toBand
import com.d365fo.mobileops.presentation.modules.ordercreate.CodeEntryDialog
import com.d365fo.mobileops.presentation.modules.ordercreate.VariantPickerDialog

/**
 * Store count, rebuilt on the redesign's flow layout (docs/ui-redesign-brief.md, phase 6):
 * [FlowHeader] → [StatusBand] → [NextCard] → [SendTray] → [FlowActionBar], on the comfort page.
 *
 * Start shows the count number and where it starts (tap to change). Then scan only: a bin label
 * sets location and bin, each item scan adds one, the next bin label sends the bin before, and
 * **Hold to end count** ends it. Undo (F3) takes back the last +1, Keypad (F4, or tapping the
 * count) overrides it, Lines (F2) lists what was counted. See [StoreCountViewModel].
 */
@Composable
fun StoreCountScreen(onBack: () -> Unit, viewModel: StoreCountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var changingStart by remember { mutableStateOf(false) }
    var showKeypad by remember { mutableStateOf(false) }

    DisposableEffect(viewModel) {
        viewModel.scannerOnScreen(true)
        onDispose { viewModel.scannerOnScreen(false) }
    }

    // Back (arrow, system back, F1) leaves; what is counted stays on the device for next time.
    // An open list closes first. The scanner's phantom back right after a scan is ignored.
    val goBack = {
        if (!viewModel.justScanned()) {
            if (state.showingLines) viewModel.showLines(false) else onBack()
        }
    }
    BackHandler(onBack = goBack)

    val onAction: (FlowAction) -> Unit = { action ->
        when (action) {
            FlowAction.Back -> goBack()
            FlowAction.Lines -> if (state.counting) viewModel.showLines(!state.showingLines)
            FlowAction.Undo -> viewModel.undo()
            FlowAction.Keypad -> if (viewModel.keypadLine != null) showKeypad = true
        }
    }
    FlowKeyHandler(onAction)

    if (typing) {
        CodeEntryDialog(
            title = "Type it in",
            label = if (state.pending?.needsBatch == true) "Batch" else if (state.pending?.needsSerial == true) "Serial" else "Bin, location or item",
            onSubmit = { typing = false; viewModel.onTyped(it) },
            onDismiss = { typing = false },
        )
    }
    if (changingStart) {
        CodeEntryDialog(
            title = "Start location",
            label = "Location",
            onSubmit = { changingStart = false; viewModel.setStartLocation(it) },
            onDismiss = { changingStart = false },
        )
    }
    if (showCamera) {
        CameraScannerSheet(
            onDecoded = { viewModel.onCameraDecoded(it); showCamera = false },
            onDismiss = { showCamera = false },
        )
    }
    state.pending?.takeIf { it.needsVariant }?.let { pending ->
        VariantPickerDialog(item = pending.item, onPick = viewModel::pickVariant, onDismiss = viewModel::cancelPending)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WarehouseStatusColors.pageComfort),
    ) {
        FlowHeader(
            title = "Store count",
            onBack = goBack,
            progress = state.draft?.lines?.size?.takeIf { it > 0 && !state.ended }?.let { nLines(it) },
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
                if (!state.counting) {
                    DropdownMenuItem(
                        text = { Text("Change start location") },
                        leadingIcon = { Icon(Icons.Default.Place, contentDescription = null) },
                        onClick = { close(); changingStart = true },
                    )
                }
            },
        )
        Box(Modifier.fillMaxWidth().heightIn(min = 3.dp)) {
            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = WarehouseDimens.ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val band = storeCountBand(state)
            StatusBand(
                kind = band.kind,
                title = band.title,
                line = band.line,
                onUndo = if (band.undoable && viewModel.canUndo) viewModel::undo else null,
                undoKey = band.key,
                actions = if (state.countClosed) {
                    {
                        SquareOutlinedButton(onClick = viewModel::moveToNewCount, enabled = !state.busy) {
                            Text("Move ${nLines(state.unsent)} to a new count")
                        }
                    }
                } else {
                    null
                },
            )

            Box(Modifier.weight(1f)) {
                when {
                    state.showingLines -> LinesView(state, onRemove = viewModel::remove)
                    else -> androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                        // Fills the space, and scrolls rather than cutting text when the band is tall.
                        val cardMin = maxHeight
                        val card = storeCountCard(state)
                        Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                        NextCard(
                            label = card.label,
                            code = card.code,
                            description = card.description,
                            location = card.location,
                            locationLabel = card.locationLabel,
                            quantity = card.quantity,
                            quantityUnit = card.unit,
                            onQuantityClick = if (card.quantity != null && viewModel.keypadLine != null) ({ showKeypad = true }) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = cardMin)
                                .then(if (!state.counting && !state.ended) Modifier.clickable { changingStart = true } else Modifier),
                        )
                        }
                    }
                }
            }

            when {
                state.ended -> SquareButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.StepperButton),
                ) { Text("Done") }
                !state.counting -> SquareButton(
                    onClick = viewModel::start,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.StepperButton),
                ) { Text(if (state.busy) "Starting…" else "Start count") }
                !state.draft?.lines.isNullOrEmpty() -> HoldToConfirmButton(
                    label = "Hold to end count",
                    onConfirm = viewModel::endCount,
                    enabled = !state.busy,
                    hint = "Hold to end - ${nLines(state.draft?.lines?.size ?: 0)} go to your supervisor",
                )
            }

            val sync = LocalSyncStatus.current
            SendTray(
                status = sync?.let { it.copy(pendingCount = it.pendingCount + state.waitingToSend) },
                lastSent = state.lastSent,
            )
        }

        FlowActionBar(
            onAction = onAction,
            highlighted = if (state.notice?.undoable == true) FlowAction.Undo else null,
            enabled = buildSet {
                add(FlowAction.Back)
                if (state.counting) add(FlowAction.Lines)
                if (viewModel.canUndo) add(FlowAction.Undo)
                if (viewModel.keypadLine != null) add(FlowAction.Keypad)
            },
        )
    }

    if (showKeypad) {
        val line = viewModel.keypadLine
        if (line == null) {
            showKeypad = false
        } else {
            QuantityPicker(
                subtitle = listOfNotNull(
                    line.itemId,
                    OrderVariant(line.configId, line.sizeId, line.colorId, line.styleId).takeIf { it != OrderVariant() }?.label,
                    line.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                    line.serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" },
                    line.unitId,
                ).joinToString(" · "),
                hint = "Counted at ${line.place} - 0 takes it off",
                confirmLabel = "Set count",
                initial = countText(line.quantity),
                replaceInitial = true,
                validate = viewModel::keypadError,
                onConfirm = { showKeypad = false; viewModel.applyKeypad(it) },
                onDismiss = { if (!viewModel.justScanned()) showKeypad = false },
            )
        }
    }
}

// -- what the band and the card say ---------------------------------------------------------

/** The status band for a store count - pure, so it is unit-tested (StoreCountFlowTest). */
internal fun storeCountBand(s: StoreCountState): CountBand {
    s.notice?.let { return it.toBand() }
    return when {
        s.ended -> CountBand(StatusKind.Ok, "Count ended")
        !s.counting -> CountBand(StatusKind.Idle, "Ready", "Tap Start, or scan a bin label")
        s.needsLocation -> CountBand(StatusKind.Idle, if (s.binsRequired) "Scan a bin label" else "Scan a location")
        s.needsBin -> CountBand(StatusKind.Info, "Counting ${s.locationId}", "Scan a bin label")
        else -> CountBand(StatusKind.Info, "Scan items", "+1 per scan at ${placeText(s.locationId, s.binId)}")
    }
}

/** The next-card for a store count - pure, so it is unit-tested (StoreCountFlowTest). */
internal fun storeCountCard(s: StoreCountState): CountCard {
    val masterId = s.draft?.masterId?.takeIf { it.isNotBlank() }
    if (s.ended) return CountCard(label = "Done", code = masterId ?: "Store count", description = "Your lines are with your supervisor")
    if (!s.counting) {
        return CountCard(
            label = "Start · tap to change",
            code = masterId ?: "New store count",
            description = listOfNotNull(s.warehouseId?.let { "Store $it" }, masterId?.let { "Count number" }).joinToString(" · ").ifBlank { null },
            location = s.startLocation?.takeIf { it.isNotBlank() } ?: "Scan a bin label",
            locationLabel = "Starts at",
        )
    }
    if (s.needsLocation) {
        return CountCard(
            label = "Next",
            code = if (s.binsRequired) "Bin label" else "Location",
            description = "Stock, damaged, claim, return... - where you count now",
        )
    }
    if (s.needsBin) {
        return CountCard(label = "Next", code = "Bin label", description = "A bin in ${s.locationId}", location = s.locationId)
    }
    val label = "Counting · ${nLines(s.here.size)} here"
    s.pending?.let { p ->
        return CountCard(
            label = label,
            code = p.item.itemId,
            description = p.item.itemName.takeIf { it.isNotBlank() && !it.equals(p.item.itemId, ignoreCase = true) },
            location = placeText(s.locationId, s.binId),
            locationLabel = if (s.binId.isBlank()) "Location" else "Bin",
        )
    }
    val line = s.lastLine?.takeIf { it.isAt(s.locationId, s.binId) } ?: s.here.firstOrNull()
        ?: return CountCard(
            label = label,
            code = s.binId.ifBlank { s.locationId },
            description = "Scan each item - one scan counts one",
            location = s.locationId.takeIf { s.binId.isNotBlank() },
        )
    return CountCard(
        label = label,
        code = line.itemId,
        description = listOfNotNull(
            line.itemName.takeIf { it.isNotBlank() && !it.equals(line.itemId, ignoreCase = true) },
            OrderVariant(line.configId, line.sizeId, line.colorId, line.styleId).takeIf { it != OrderVariant() }?.label,
            line.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
            line.serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" },
        ).joinToString(" · ").ifBlank { null },
        location = line.place,
        locationLabel = if (line.binId.isBlank()) "Location" else "Bin",
        quantity = countText(line.quantity),
        unit = line.unitId,
    )
}

// -- parts ----------------------------------------------------------------------------------

/** Lines (F2): everything counted, newest first; lines still on the device can be taken off. */
@Composable
private fun LinesView(state: StoreCountState, onRemove: (StoreCountLine) -> Unit) {
    val lines = state.draft?.lines.orEmpty()
    LazyColumn(
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(WarehouseStatusColors.surface),
    ) {
        item {
            Text(
                "Counted (${lines.size})",
                style = WarehouseType.label,
                color = WarehouseStatusColors.inkMuted,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
            )
        }
        if (lines.isEmpty()) {
            item {
                Text("Nothing counted yet", style = WarehouseType.body, color = WarehouseStatusColors.inkMuted, modifier = Modifier.padding(16.dp))
            }
        }
        items(lines, key = { it.clientLineId }) { line ->
            val sending = line.clientLineId in state.inFlight
            ListRow(
                title = line.itemId,
                codeTitle = true,
                subtitles = listOfNotNull(
                    line.itemName.takeIf { it.isNotBlank() && !it.equals(line.itemId, ignoreCase = true) },
                    listOfNotNull(
                        OrderVariant(line.configId, line.sizeId, line.colorId, line.styleId).takeIf { it != OrderVariant() }?.label,
                        line.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                        line.serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" },
                        line.place,
                    ).joinToString(" · "),
                    when {
                        line.sent -> "With your supervisor"
                        sending -> "Sending"
                        else -> "On this device"
                    },
                ),
                status = if (line.sent) StatusKind.Ok else StatusKind.Idle,
                trailing = {
                    ListRowValue("${countText(line.quantity)} ${line.unitId}")
                    if (!line.sent && !sending) {
                        IconButton(onClick = { onRemove(line) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove ${line.itemId}", tint = WarehouseStatusColors.inkMuted)
                        }
                    }
                },
            )
            HorizontalDivider(color = WarehouseStatusColors.divider)
        }
    }
}
