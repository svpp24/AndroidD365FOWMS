package com.d365fo.mobileops.presentation.modules.cyclecounting

import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MoreVert
import com.d365fo.mobileops.presentation.common.focus.BandKind
import com.d365fo.mobileops.presentation.common.focus.BandState
import com.d365fo.mobileops.presentation.common.focus.FocusButton
import com.d365fo.mobileops.presentation.common.focus.FocusCenter
import com.d365fo.mobileops.presentation.common.focus.FocusListRow
import com.d365fo.mobileops.presentation.common.focus.FocusScreen
import com.d365fo.mobileops.presentation.common.focus.HoldToConfirmButton
import com.d365fo.mobileops.presentation.common.focus.QuantityPadSheet
import com.d365fo.mobileops.presentation.common.focus.RowState
import com.d365fo.mobileops.presentation.common.focus.ScanPillField
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.modules.stepflow.bandOf
import androidx.compose.material.icons.filled.Add
import com.d365fo.mobileops.presentation.common.components.BarcodeFieldIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import com.d365fo.mobileops.data.local.entity.CountExceptionEntity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import com.d365fo.mobileops.presentation.common.components.SquareTextButton as TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.data.local.dao.CountLine
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar
import com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton
import com.d365fo.mobileops.presentation.common.components.ConfirmButton
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton as OutlinedButton
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import kotlinx.coroutines.delay

/**
 * Cycle and wall-to-wall counting in the Focus design (brief sections 6 and 9), on the existing
 * steps: location (where the journal counts by location), bin (where the store has bins), then
 * items. A scan of an item opens the quantity pad for its line; the band says what the last scan
 * did and the centre shows how much of the journal is counted. The journal's lines, locations and
 * bins open on demand from the bottom button - tapping a line there corrects its count. Blind
 * throughout: lines carry no on-hand quantity.
 */
@Composable
fun CountingScanScreen(
    onBack: () -> Unit,
    viewModel: CountingScanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    var showList by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.leave.collect { onBack() } }

    CountingDialogs(state, viewModel)
    if (showCamera) {
        CameraScannerSheet(
            onDecoded = {
                viewModel.onCameraDecoded(it)
                showCamera = false
            },
            onDismiss = { showCamera = false },
        )
    }

    val choosingLocation = state.byLocation && state.selectedLocation == null
    val choosingBin = !choosingLocation && state.binsRequired && state.currentBin == null
    val prompt = state.prompt
    val pad: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = prompt?.let { p ->
        {
            QuantityPadSheet(
                initial = p.initial,
                onSet = { viewModel.confirmQuantity(p.line.journalLineId, it) },
                onDismiss = viewModel::cancelPrompt,
            )
        }
    }

    // A triggered check of one line: that line alone, its quantity and Hold to end count.
    state.focusLine?.let { line ->
        FocusedCount(state = state, line = line, onBack = onBack, onConfirm = viewModel::confirmAndEnd)
        return
    }

    if (showList) {
        val close = {
            viewModel.setQuery("")
            showList = false
        }
        androidx.activity.compose.BackHandler(onBack = close)
        CountingLists(
            state = state,
            choosingLocation = choosingLocation,
            choosingBin = choosingBin,
            onClose = close,
            onPickLocation = { close(); viewModel.pickLocation(it) },
            onPickBin = { close(); viewModel.pickBin(it) },
            onLine = viewModel::openLine,
            onException = viewModel::editUnexpected,
            onQuery = viewModel::setQuery,
            onTypeFilter = viewModel::setTypeFilter,
            overlay = pad,
        )
        return
    }

    FocusScreen(
        title = if (state.isWallToWall) "Wall-to-wall count" else "Cycle count",
        onBack = onBack,
        counter = state.currentBin ?: state.selectedLocation
            ?: "${state.counted} / ${state.totalLines}".takeIf { state.totalLines > 0 },
        progress = if (state.totalLines > 0) state.counted.toFloat() / state.totalLines else null,
        band = cycleCountBand(state),
        remaining = if (!choosingLocation && !choosingBin && state.totalLines > 0) {
            (state.totalLines - state.counted).let { if (it == 1) "1 line left" else "$it lines left" }
        } else {
            null
        },
        scanPill = { ScanPillField(enabled = !state.locked) },
        topBarTrailing = if (state.locked) null else { {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More", tint = FocusColors.ink)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    MenuRow("Camera scan", Icons.Default.PhotoCamera) { menuOpen = false; showCamera = true }
                    if (state.exceptionsEnabled) {
                        MenuRow("Item not on this journal", Icons.Default.AddCircle) { menuOpen = false; viewModel.openUnexpected() }
                    }
                    if (state.showLocationSwitch) {
                        MenuRow(if (state.byLocation) "Count by location: on" else "Count by location: off", Icons.Default.LocationOn) {
                            menuOpen = false
                            viewModel.setByLocation(!state.byLocation)
                        }
                    }
                    if (state.byLocation && state.selectedLocation != null && state.locations.size > 1) {
                        MenuRow("Change location", Icons.Default.LocationOn) { menuOpen = false; viewModel.clearLocation() }
                    }
                    if (state.binsRequired && state.currentBin != null) {
                        MenuRow("Change bin", Icons.Default.Inventory2) { menuOpen = false; viewModel.changeBin() }
                    }
                    if (state.isWallToWall && state.selectedLocation != null) {
                        MenuRow("Location is empty", Icons.Default.Clear) { menuOpen = false; viewModel.requestLocationEmpty() }
                    }
                }
            }
        } },
        actions = cycleCountActions(
            state = state,
            choosingLocation = choosingLocation,
            choosingBin = choosingBin,
            onCancelChange = viewModel::cancelChange,
            onList = { showList = true },
            onRecord = { viewModel.openUnexpected(it) },
            onUndo = viewModel::undoLastScan,
            onEmpty = viewModel::requestLocationEmpty,
        ),
        overlay = pad,
    ) {
        CycleCountCenter(state, choosingLocation, choosingBin)
    }
}

@Composable
private fun MenuRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 56.dp),
    )
}

/**
 * The band: the last scan's result, else where the count stands - submitted, should-be-empty,
 * a stock freeze, a change of place under way, or the journal itself.
 */
internal fun cycleCountBand(state: CountingScanState): BandState {
    val message = state.message
    if (message != null) {
        // A count needs a bin first - said as what went wrong, not as an instruction.
        if (message.text == "Scan a bin first.") return BandState(BandKind.Bad, "No bin open", "nothing counted")
        // "HSN001 · AAC: 1" - a scan that added to a count: the brief's "Counted · {item} · now 3".
        if (!message.isError && message.undoable) return BandState(BandKind.Ok, "Counted", message.text)
        // "X · 1 added (not on journal)": recorded for the supervisor, a warning rather than a result.
        if (!message.isError && message.text.contains("not on journal", ignoreCase = true)) {
            return BandState(BandKind.Warn, "Not on this journal", message.text)
        }
        val band = bandOf(if (message.isError) BandKind.Bad else BandKind.Ok, message.text.removeSuffix("."))
        // "A-01 empty - scan the next location": the result, without what to scan next.
        return if (band.detail?.startsWith("scan", ignoreCase = true) == true) band.copy(detail = null) else band
    }
    val freeze = state.freezeBanner
    return when {
        state.locked -> BandState(BandKind.Info, "Submitted", "reopen to change")
        state.changingFrom != null -> BandState(BandKind.Info, "Changing place", "from ${state.changingFrom}")
        state.zeroStockCheck -> BandState(BandKind.Warn, "Should be empty", state.journalDescription)
        freeze != null -> bandOf(BandKind.Warn, freeze)
        else -> BandState(
            BandKind.Info,
            state.journalDescription.ifBlank { "Count" },
            "Blind count",
        )
    }
}

private fun cycleCountActions(
    state: CountingScanState,
    choosingLocation: Boolean,
    choosingBin: Boolean,
    onCancelChange: () -> Unit,
    onList: () -> Unit,
    onRecord: (String) -> Unit,
    onUndo: () -> Unit,
    onEmpty: () -> Unit,
): @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
    val message = state.message
    if (choosingLocation || choosingBin) {
        if (state.changingFrom != null) {
            FocusButton("Cancel", onClick = onCancelChange, modifier = Modifier.weight(1f))
        }
        FocusButton(
            if (choosingLocation) "Locations" else "Bins",
            onClick = onList,
            icon = if (choosingLocation) Icons.Default.LocationOn else Icons.Default.Inventory2,
            modifier = Modifier.weight(1f),
        )
    } else {
        FocusButton("Lines", onClick = onList, icon = Icons.AutoMirrored.Filled.ListAlt, modifier = Modifier.weight(1f))
        val unexpected = message?.unexpectedItemId
        when {
            unexpected != null -> FocusButton("Record it", onClick = { onRecord(unexpected) }, primary = true, modifier = Modifier.weight(1f))
            message?.undoable == true -> FocusButton("Undo", onClick = onUndo, icon = Icons.AutoMirrored.Filled.Undo, modifier = Modifier.weight(1f))
            state.isWallToWall && state.selectedLocation != null && !state.locked ->
                FocusButton("Location is empty", onClick = onEmpty, modifier = Modifier.weight(1f))
        }
    }
}

/** The centre: the line in hand while its quantity is entered, else how much is counted. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CycleCountCenter(
    state: CountingScanState,
    choosingLocation: Boolean,
    choosingBin: Boolean,
) {
    val prompt = state.prompt
    when {
        prompt != null -> FocusCenter(
            caption = state.currentBin?.let { "In $it" } ?: "Quantity",
            code = prompt.line.itemId,
            description = listOfNotNull(
                prompt.line.itemName.takeIf { it.isNotBlank() && !it.equals(prompt.line.itemId, ignoreCase = true) },
                whereLine(prompt.line),
            ).joinToString(" · ").ifEmpty { null },
            quantity = prompt.initial.ifEmpty { "–" },
        )
        choosingLocation -> FocusCenter(
            caption = "Location",
            code = "${state.locations.count { it.total > 0 && it.counted == it.total }} of ${state.locations.size}",
            description = "locations counted",
        )
        choosingBin -> FocusCenter(
            caption = "Bin",
            code = "${state.locationCounted} of ${state.locationTotal}",
            description = "lines counted in ${state.selectedLocation.orEmpty()}".trim(),
        )
        state.selectedLocation != null -> FocusCenter(
            caption = "Counted",
            code = "${state.locationCounted} of ${state.locationTotal}",
            description = "lines in ${state.selectedLocation}",
        )
        else -> FocusCenter(
            caption = "Counted",
            code = "${state.counted} of ${state.totalLines}",
            description = if (state.totalLines == 0) "This journal has no lines" else "lines",
        )
    }
}

/** The dialogs the count keeps: line choice, verify, empty location, a new bin, items not on the journal. */
@Composable
private fun CountingDialogs(state: CountingScanState, viewModel: CountingScanViewModel) {
    state.addBinPrompt?.let { bin ->
        AlertDialog(
            onDismissRequest = viewModel::cancelAddBin,
            title = { Text("Add bin $bin?") },
            text = { Text("$bin is not on this store's list. Add it, and count in it now?") },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = {
                        CancelOutlinedButton(
                            onClick = viewModel::cancelAddBin,
                            modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                        ) { Text("Cancel") }
                    },
                    confirm = {
                        ConfirmButton(
                            onClick = viewModel::confirmAddBin,
                            modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                        ) { Text("Add bin") }
                    },
                )
            },
        )
    }
    if (state.choice.isNotEmpty()) {
        ChoiceDialog(
            lines = state.choice,
            onPick = viewModel::pickLine,
            onCancel = viewModel::cancelChoice,
        )
    }

    state.verifyLine?.let { line ->
        AlertDialog(
            onDismissRequest = viewModel::cancelVerify,
            title = { Text("Verify, don't count") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${line.itemId} · ${com.d365fo.mobileops.domain.model.locationTypeLabel(line.locationType) ?: "Physical"}")
                    line.locationOwner?.let { Text("Owner $it", style = MaterialTheme.typography.bodySmall) }
                    Text("Is it there?")
                }
            },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = {
                        if (line.confirmedAt != null) viewModel.confirmVerified(line.journalLineId, false) else viewModel.cancelVerify()
                    }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text(if (line.confirmedAt != null) "Withdraw confirmation" else "Cancel") } },
                    confirm = { com.d365fo.mobileops.presentation.common.components.ConfirmButton(onClick = { viewModel.confirmVerified(line.journalLineId, true) }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Confirm present") } },
                )
            },
        )
    }

    state.emptyPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = viewModel::cancelLocationEmpty,
            title = { Text("Is ${prompt.locationId} empty?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${prompt.lines} line${if (prompt.lines == 1) "" else "s"} set to 0.",
                    )
                    if (prompt.alreadyCounted > 0) {
                        Text(
                            "${prompt.alreadyCounted} counted already - replaced with 0.",
                            color = WarehouseStatusColors.amber,
                        )
                    }
                }
            },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = viewModel::cancelLocationEmpty, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
                    confirm = { com.d365fo.mobileops.presentation.common.components.ConfirmButton(onClick = viewModel::confirmLocationEmpty, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Yes, it's empty") } },
                )
            },
        )
    }

    state.unexpected?.let { prompt ->
        UnexpectedItemDialog(
            prompt = prompt,
            onSave = viewModel::saveUnexpected,
            onCancel = viewModel::cancelUnexpected,
        )
    }

    state.variantChoice?.let { choice ->
        AlertDialog(
            onDismissRequest = viewModel::cancelVariantChoice,
            title = { Text("Which ${choice.itemId}?") },
            text = {
                // Scrolls, with a filter once there are many (seen 14 Oct: HST002's variants ran
                // off the dialog and only five could be reached).
                var filter by remember(choice) { mutableStateOf("") }
                val shown = choice.variants.filter { filter.isBlank() || it.matches(filter.trim()) || it.label.contains(filter.trim(), ignoreCase = true) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Which variant?", style = MaterialTheme.typography.bodyMedium)
                    if (choice.variants.size > 5) {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            label = { Text("Filter (size, colour…)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(shown.size) { i ->
                            val variant = shown[i]
                            OutlinedButton(
                                onClick = { viewModel.chooseUnexpectedVariant(variant) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
                            ) { Text(variant.label) }
                        }
                    }
                    if (shown.isEmpty()) Text("No variant matches.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = null,
                    confirm = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = viewModel::cancelVariantChoice, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
                )
            },
        )
    }

    state.editingException?.let { exception ->
        NotOnJournalQuantityDialog(
            exception = exception,
            onOk = { viewModel.setUnexpectedQuantity(exception.clientId, it) },
            onRemove = { viewModel.removeUnexpected(exception.clientId) },
            onCancel = viewModel::closeUnexpectedEdit,
        )
    }

}

/**
 * The scanned item is on several lines: pick the location / variant / batch to count. A variant
 * master can be on hundreds of lines (432 seen live), so there is a filter - "red", "M slim" or
 * a location narrows it - and the list is lazy rather than every card drawn up front.
 */
@Composable
private fun ChoiceDialog(lines: List<CountLine>, onPick: (String) -> Unit, onCancel: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    // Read-only until tapped, like the scan box: the dialog hands this field focus as it opens,
    // and this device's keyboard then covered the list the worker was about to tap.
    var typing by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val filterInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    LaunchedEffect(filterInteraction) {
        filterInteraction.interactions.collect {
            if (it is androidx.compose.foundation.interaction.PressInteraction.Release && !typing) {
                typing = true
                keyboard?.show()
            }
        }
    }
    val shown = remember(filter, lines) {
        val tokens = filter.trim().split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) lines else lines.filter { line ->
            val text = whereLine(line).orEmpty()
            tokens.all { text.contains(it, ignoreCase = true) }
        }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("${lines.first().itemId} is on ${lines.size} lines") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pick the location, variant or batch to count.", style = MaterialTheme.typography.bodyMedium)
                if (lines.size > CHOICE_FILTER_FROM) {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        singleLine = true,
                        readOnly = !typing,
                        interactionSource = filterInteraction,
                        placeholder = { Text("Filter - e.g. Red M") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${shown.size} of ${lines.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = WarehouseStatusColors.text3,
                    )
                }
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.journalLineId }) { line ->
                        Card(modifier = Modifier.fillMaxWidth().clickable { onPick(line.journalLineId) }) {
                            Row(
                                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = whereLine(line) ?: "Line ${line.lineNumber}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = line.countedQuantity ?: "-",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                dismiss = null,
                confirm = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
            )
        },
    )
}

/** Lines in the picker from which a filter box is shown. */
private const val CHOICE_FILTER_FROM = 6

/** "Loc A-01 · Batch B7" - where this line is, or null when it has no location/batch/serial. */
private fun whereLine(line: CountLine): String? =
    listOfNotNull(
        line.locationId?.takeIf { it.isNotBlank() }?.let { "Loc $it" },
        line.variant.label.takeIf { it.isNotBlank() },
        line.batchId?.takeIf { it.isNotBlank() }?.let { "Batch $it" },
        line.serialId?.takeIf { it.isNotBlank() }?.let { "Serial $it" },
    ).joinToString(" · ").ifEmpty { null }

/**
 * "Not on this journal": what was found, where, how many. Recorded as an exception for the
 * server to resolve - the device never adds a journal line.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun UnexpectedItemDialog(
    prompt: UnexpectedItemPrompt,
    onSave: (item: String, location: String, quantity: String, batch: String, serial: String, note: String, variant: com.d365fo.mobileops.domain.model.ProductVariant?) -> Unit,
    onCancel: () -> Unit,
) {
    var variant by remember(prompt) { mutableStateOf(prompt.variant) }
    var item by remember(prompt) { mutableStateOf(prompt.itemId) }
    var location by remember(prompt) { mutableStateOf(prompt.locationId.orEmpty()) }
    var quantity by remember(prompt) { mutableStateOf("") }
    var batch by remember(prompt) { mutableStateOf("") }
    var serial by remember(prompt) { mutableStateOf("") }
    var note by remember(prompt) { mutableStateOf("") }

    // Harbour: a white sheet-like dialog, an amber tag saying what this is, the app's field
    // style with small labels above, variants as pills, and the gradient Record.
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Color.White,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.ifBlank { "Item found" },
                    fontWeight = FontWeight.ExtraBold,
                    color = WarehouseStatusColors.accent,
                )
                com.d365fo.mobileops.presentation.common.components.StatusTag(
                    "Not on this journal",
                    com.d365fo.mobileops.presentation.common.components.Lead.Warn,
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                prompt.error?.let { ErrorBanner(message = it) }
                NjField("Item number", item, { item = it.trim() })
                // The variant found, for an item that comes in variants (14 Oct).
                if (prompt.variants.isNotEmpty() && item.equals(prompt.itemId, ignoreCase = true)) {
                    NjLabel("Variant")
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        prompt.variants.forEach { v ->
                            val on = variant == v
                            Text(
                                v.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (on) Color.White else WarehouseStatusColors.accent,
                                modifier = Modifier
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                    .background(if (on) WarehouseStatusColors.accent else WarehouseStatusColors.accentDim)
                                    .clickable { variant = v }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
                NjField("Location", location, { location = it.trim() })
                NjField(
                    "Quantity found",
                    quantity,
                    { quantity = it.filter(Char::isDigit).take(12) },
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                NjField("Batch (optional)", batch, { batch = it.trim() })
                NjField("Serial (optional)", serial, { serial = it.trim() })
                NjField("Note (optional)", note, { note = it }, singleLine = false)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                com.d365fo.mobileops.presentation.common.components.DockOutlinedButton(
                    onClick = onCancel,
                    text = "Cancel",
                    modifier = Modifier.weight(1f),
                )
                com.d365fo.mobileops.presentation.common.components.GradientButton(
                    onClick = { onSave(item, location, quantity, batch, serial, note, variant) },
                    modifier = Modifier.weight(1f),
                ) { Text("Record") }
            }
        },
    )
}

/** A field's small label, as on the creation screens. */
@Composable
private fun NjLabel(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    fontWeight = FontWeight.Bold,
    color = WarehouseStatusColors.text3,
    modifier = Modifier.padding(top = 6.dp),
)

/** A labelled field in the app's own style - accent outline, rounded. */
@Composable
private fun NjField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
) {
    NjLabel(label)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        keyboardOptions = keyboard,
        shape = com.d365fo.mobileops.presentation.common.components.HeroFieldShape,
        colors = com.d365fo.mobileops.presentation.common.components.heroFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}


@Composable
private fun NotOnJournalQuantityDialog(
    exception: CountExceptionEntity,
    onOk: (String) -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    var quantity by remember(exception.clientId) { mutableStateOf(exception.quantity) }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Color.White,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(exception.itemId, fontWeight = FontWeight.ExtraBold, color = WarehouseStatusColors.accent)
                com.d365fo.mobileops.presentation.common.components.StatusTag(
                    "Not on this journal",
                    com.d365fo.mobileops.presentation.common.components.Lead.Warn,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NjField(
                    "Quantity found",
                    quantity,
                    { quantity = it.filter(Char::isDigit).take(12) },
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                androidx.compose.material3.TextButton(onClick = onRemove) {
                    Text("Remove from the list", color = WarehouseStatusColors.red, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                com.d365fo.mobileops.presentation.common.components.DockOutlinedButton(
                    onClick = onCancel,
                    text = "Cancel",
                    modifier = Modifier.weight(1f),
                )
                com.d365fo.mobileops.presentation.common.components.GradientButton(
                    onClick = { onOk(quantity) },
                    enabled = quantity.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("OK") }
            }
        },
    )
}


/**
 * The lists on demand: the journal's locations on the location step, the location's bins on the
 * bin step (tap = the same as scanning it), else the journal's lines - items found that are not
 * on it first - with a search and, where the journal has several, a location-type filter. Tapping
 * a line opens its quantity to correct it.
 */
@Composable
private fun CountingLists(
    state: CountingScanState,
    choosingLocation: Boolean,
    choosingBin: Boolean,
    onClose: () -> Unit,
    onPickLocation: (String) -> Unit,
    onPickBin: (String) -> Unit,
    onLine: (CountLine) -> Unit,
    onException: (String) -> Unit,
    onQuery: (String) -> Unit,
    onTypeFilter: (String?) -> Unit,
    overlay: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)?,
) {
    FocusScreen(
        title = when {
            choosingLocation -> "Locations"
            choosingBin -> state.selectedLocation?.let { "Bins in $it" } ?: "Bins"
            else -> "Journal lines"
        },
        onBack = onClose,
        counter = when {
            choosingLocation -> state.locations.size.toString()
            choosingBin -> state.binsHere.size.toString()
            else -> "${state.counted} / ${state.totalLines}"
        },
        progress = if (!choosingLocation && !choosingBin && state.totalLines > 0) state.counted.toFloat() / state.totalLines else null,
        overlay = overlay,
    ) {
        if (!choosingLocation && !choosingBin) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                singleLine = true,
                placeholder = { Text("Search item, location, batch") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                shape = com.d365fo.mobileops.presentation.common.theme.FocusShapes.button,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).heightIn(min = 56.dp),
            )
            if (state.locationTypes.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = state.typeFilter == null, onClick = { onTypeFilter(null) }, label = { Text("All") },
                        modifier = Modifier.heightIn(min = 56.dp),
                    )
                    state.locationTypes.forEach { type ->
                        FilterChip(
                            selected = state.typeFilter == type,
                            onClick = { onTypeFilter(type) },
                            label = { Text(com.d365fo.mobileops.domain.model.locationTypeLabel(type) ?: "Physical") },
                            modifier = Modifier.heightIn(min = 56.dp),
                        )
                    }
                }
            }
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                choosingLocation -> items(state.locations, key = { "loc-" + it.locationId }) { choice ->
                    val done = choice.total > 0 && choice.counted == choice.total
                    FocusListRow(
                        title = choice.locationId,
                        titleIsCode = true,
                        state = if (done) RowState.Done else RowState.Open,
                        count = "${choice.counted}/${choice.total}",
                        chevron = true,
                        onClick = { if (!state.locked) onPickLocation(choice.locationId) },
                    )
                }
                choosingBin -> {
                    if (state.binsHere.isEmpty()) {
                        item { EmptyListText("No bins listed here.") }
                    }
                    items(state.binsHere, key = { "bin-$it" }) { bin ->
                        FocusListRow(
                            title = bin, titleIsCode = true, state = RowState.Open, chevron = true,
                            onClick = { onPickBin(bin) },
                        )
                    }
                }
                else -> {
                    if (state.lines.isEmpty() && state.exceptions.isEmpty()) {
                        item { EmptyListText(if (state.totalLines == 0) "This journal has no lines." else "No items match.") }
                    }
                    if (state.exceptionsEnabled) {
                        items(state.exceptions.sortedByDescending { it.capturedAt }, key = { "x-" + it.clientId }) { exception ->
                            val variant = com.d365fo.mobileops.domain.model.ProductVariant(exception.configId, exception.sizeId, exception.colorId, exception.styleId)
                            FocusListRow(
                                title = exception.itemId,
                                titleIsCode = true,
                                state = RowState.Warn,
                                icon = Icons.Default.AddCircle,
                                subtitle = listOfNotNull(
                                    if (exception.sendError != null) "Not sent" else "Not on this journal",
                                    variant.label.takeIf { it.isNotBlank() },
                                    exception.locationId?.let { "Loc $it" },
                                    exception.batchId?.let { "Batch $it" },
                                    exception.serialId?.let { "Serial $it" },
                                ).joinToString(" · "),
                                count = exception.quantity,
                                onClick = { onException(exception.clientId) }.takeIf { exception.sentAt == null && !state.locked },
                            )
                        }
                    }
                    items(state.lines, key = { it.journalLineId }) { line ->
                        CountingLineRow(line = line, onClick = { onLine(line) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyListText(text: String) = Text(
    text,
    style = com.d365fo.mobileops.presentation.common.theme.FocusType.description,
    color = FocusColors.muted,
    modifier = Modifier.padding(20.dp),
)

/**
 * A triggered or opportunity check of one line: the line in the centre, its quantity (tap the
 * number for the pad, open on arrival while empty) and Hold to end count, which saves it and
 * ends the count; the screen then closes.
 */
@Composable
private fun FocusedCount(
    state: CountingScanState,
    line: CountLine,
    onBack: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var quantity by androidx.compose.runtime.saveable.rememberSaveable(line.journalLineId) { mutableStateOf(line.countedQuantity.orEmpty()) }
    var pad by androidx.compose.runtime.saveable.rememberSaveable(line.journalLineId) { mutableStateOf(!state.locked && quantity.isEmpty()) }
    val origin = com.d365fo.mobileops.domain.model.originLabel(state.originType) ?: "Count"
    FocusScreen(
        title = origin,
        onBack = onBack,
        counter = line.locationId?.takeIf { it.isNotBlank() },
        band = state.message?.let { bandOf(if (it.isError) BandKind.Bad else BandKind.Ok, it.text.removeSuffix(".")) }
            ?: if (state.locked) BandState(BandKind.Info, "Submitted", "reopen to change") else BandState(BandKind.Info, origin, "Blind count"),
        actions = if (state.locked) null else { {
            HoldToConfirmButton(
                "Hold to end count",
                onConfirm = { onConfirm(quantity) },
                enabled = quantity.isNotEmpty(),
                modifier = Modifier.weight(1f),
            )
        } },
        overlay = if (pad) { {
            QuantityPadSheet(
                initial = quantity,
                onSet = { quantity = it; pad = false },
                onDismiss = { pad = false },
            )
        } } else {
            null
        },
    ) {
        FocusCenter(
            caption = line.locationId?.takeIf { it.isNotBlank() }?.let { "At $it" } ?: "No location",
            code = line.itemId,
            description = listOfNotNull(
                line.itemName.takeIf { it.isNotBlank() && !it.equals(line.itemId, ignoreCase = true) },
                line.variant.label.takeIf { it.isNotBlank() },
                line.batchId?.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                line.serialId?.takeIf { it.isNotBlank() }?.let { "Serial $it" },
            ).joinToString(" · ").ifEmpty { null },
            quantity = quantity.ifEmpty { "–" },
            unitLine = "Quantity found",
            onQuantityTap = if (state.locked) null else { { pad = true } },
        )
    }
}

/** Cycle count samples for the layout test: 0 journal open, 1 counted, 2 quantity pad, 3 not on journal, 4 location step. */
@Composable
internal fun CycleCountFocusSample(kind: Int) {
    val line = CountLine(
        journalLineId = "L1", journalId = "BSP-JN-000552040", lineNumber = 1, itemId = "BSR-11397-0",
        itemName = "Panadol Extra 24s", locationId = "AAC", batchId = null, serialId = null, countedQuantity = null,
        markedCounted = false, countedBy = null, countedAt = null,
    )
    val base = CountingScanState(
        journalDescription = "Weekly cycle count A-class",
        totalLines = 18, counted = 7, lines = listOf(line),
        locations = listOf(LocationChoice("AAC", 7, 12), LocationChoice("DAMAGED", 0, 6)),
    )
    val state = when (kind) {
        1 -> base.copy(message = ScanMessage("BSR-11397-0 counted: 3", isError = false, undoable = true))
        2 -> base.copy(prompt = CountPrompt(line, "3"))
        3 -> base.copy(message = ScanMessage("HSN010 is not an item on this journal", isError = true, unexpectedItemId = "HSN010"), exceptionsEnabled = true)
        4 -> base.copy(byLocation = true)
        else -> base
    }
    val choosingLocation = state.byLocation && state.selectedLocation == null
    FocusScreen(
        title = "Cycle count",
        onBack = {},
        counter = "${state.counted} / ${state.totalLines}",
        progress = state.counted.toFloat() / state.totalLines,
        band = cycleCountBand(state),
        remaining = if (choosingLocation) null else "${state.totalLines - state.counted} lines left",
        scanPill = { com.d365fo.mobileops.presentation.common.focus.ScanPill(onTap = {}) },
        actions = cycleCountActions(state, choosingLocation, false, {}, {}, {}, {}, {}),
        overlay = state.prompt?.let { p -> { QuantityPadSheet(initial = p.initial, onSet = {}, onDismiss = {}) } },
    ) {
        CycleCountCenter(state, choosingLocation, false)
    }
}
