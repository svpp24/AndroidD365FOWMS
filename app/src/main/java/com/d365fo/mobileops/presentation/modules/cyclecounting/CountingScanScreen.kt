package com.d365fo.mobileops.presentation.modules.cyclecounting

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
 * Start counting: the journal's items in a list, a scan box, and a quantity popup.
 *
 * Scan (hardware, camera, or type and press Enter) an item or barcode and a popup asks for the
 * quantity; OK saves it and the cursor goes straight back to the scan box for the next scan.
 * Tapping a row opens the same popup to correct it. What is typed in the scan box also narrows
 * the list, so it doubles as a search. The list is deliberately text-only rows (no inputs), so it
 * scrolls smoothly on a low-end handheld. (View lines, the read-only overview with a counted / not
 * counted filter, is opened from the journal card, not from here.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountingScanScreen(
    onBack: () -> Unit,
    viewModel: CountingScanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    val scanFocus = remember { FocusRequester() }
    // The scan box is read-only until tapped: this device's keyboard opens itself on any focus
    // change, whatever the field asks for, so a focused editable box covered the screen on every
    // arrival. Scans arrive through the scanner bridge, not the box. A tap switches to typing
    // (keyboard up); searching, or a popup closing, switches back.
    var typing by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val scanInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    LaunchedEffect(scanInteraction) {
        scanInteraction.interactions.collect {
            if (it is androidx.compose.foundation.interaction.PressInteraction.Release && !typing) {
                typing = true
                keyboard?.show()
            }
        }
    }

    // Cursor in the scan box on arrival, and again every time the popup closes. The short delay
    // lets the closing dialog give the window's focus back first.
    LaunchedEffect(Unit) {
        delay(300)
        runCatching { scanFocus.requestFocus() }
    }
    LaunchedEffect(state.refocusNonce) {
        if (state.refocusNonce > 0) {
            typing = false
            delay(250)
            runCatching { scanFocus.requestFocus() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // "Counting" in the bar and the journal number above the progress (feedback, 11 Oct):
                // the number wrapped in the bar beside its icons.
                title = { Text("Counting") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                // Record an item not on this journal - in the top bar (feedback 3c03d94e); it was
                // a whole row under the scan card, there even with nothing recorded.
                actions = {
                    if (state.exceptionsEnabled && !state.locked) {
                        IconButton(onClick = { viewModel.openUnexpected() }) {
                            Icon(Icons.Default.Add, contentDescription = "Record an item not on this journal")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = WarehouseDimens.ScreenPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // One header row (feedback, 14 Oct): the progress as a ring with the percentage
                // inside, the journal and its count beside it, Count by location on the right -
                // three rows before, so the scan card sits higher.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    com.d365fo.mobileops.presentation.common.components.CountProgressRing(
                        progress = if (state.totalLines > 0) state.counted.toFloat() / state.totalLines else 0f,
                        percent = state.percent,
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = viewModel.journalId,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = WarehouseStatusColors.accent,
                        )
                        Text(
                            text = "${state.counted} of ${state.totalLines} counted",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (state.exceptions.isNotEmpty()) {
                            Text(
                                text = "${state.exceptions.size} not on this journal",
                                style = MaterialTheme.typography.labelSmall,
                                color = WarehouseStatusColors.amber,
                            )
                        }
                    }
                    // Before the scan card (feedback 3c03d94e): it decides what the card asks for -
                    // an item, or a location first.
                    if (state.locationModeAvailable) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Switch(
                                checked = state.byLocation,
                                onCheckedChange = viewModel::setByLocation,
                                enabled = !state.locked,
                            )
                            Text("By location", style = MaterialTheme.typography.labelSmall, color = WarehouseStatusColors.text3)
                        }
                    }
                }

                // The shared scan card, first under the top bar - the same place as every other
                // scan screen. It sat below the progress, filters, "Count by location" and the
                // location bar, so it moved whenever one of those appeared (feedback, 10 Oct).
                com.d365fo.mobileops.presentation.common.components.PromptHeroCard(
                    prompt = if (state.byLocation && state.selectedLocation == null) "Scan a location" else "Scan item",
                    hint = if (state.byLocation && state.selectedLocation == null) "Or choose a location below" else null,
                ) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).focusRequester(scanFocus),
                        singleLine = true,
                        enabled = !state.locked,
                        readOnly = !typing,
                        interactionSource = scanInteraction,
                        leadingIcon = { BarcodeFieldIcon() },
                        shape = com.d365fo.mobileops.presentation.common.components.HeroFieldShape,
                        colors = com.d365fo.mobileops.presentation.common.components.heroFieldColors(),
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setQuery("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            typing = false
                            keyboard?.hide()
                            viewModel.submitTyped()
                        }),
                    )
                    com.d365fo.mobileops.presentation.common.components.HeroCameraButton(onClick = { showCamera = true }, enabled = !state.locked)
                }


                state.freezeBanner?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.amber)
                }

                if (state.locationTypes.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(selected = state.typeFilter == null, onClick = { viewModel.setTypeFilter(null) }, label = { Text("All") })
                        state.locationTypes.forEach { type ->
                            FilterChip(
                                selected = state.typeFilter == type,
                                onClick = { viewModel.setTypeFilter(type) },
                                label = { Text(com.d365fo.mobileops.domain.model.locationTypeLabel(type) ?: "Physical") },
                            )
                        }
                    }
                }

                if (state.zeroStockCheck) {
                    Text(
                        text = "D365FO expects these locations to be empty. Confirm each one empty, " +
                            "or record what you find.",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarehouseStatusColors.amber,
                    )
                }


                if (state.byLocation) {
                    LocationBar(
                        state = state,
                        onEmpty = viewModel::requestLocationEmpty,
                        onChange = viewModel::clearLocation,
                        onPick = viewModel::pickLocation,
                    )
                }


                state.message?.let { message ->
                    // The message and Undo on one line - Undo as its own full-height row pushed the
                    // list down after every scan (feedback a14afd70, "clumsy").
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (message.isError) MaterialTheme.colorScheme.error else WarehouseStatusColors.green,
                            modifier = Modifier.weight(1f).clickable(onClick = viewModel::dismissMessage),
                        )
                        if (message.undoable) {
                            TextButton(onClick = viewModel::undoLastScan) { Text("Undo") }
                        }
                    }
                    message.unexpectedItemId?.let { itemId ->
                        OutlinedButton(
                            onClick = { viewModel.openUnexpected(itemId) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
                        ) { Text("Not on this journal - record it") }
                    }
                }

                if (state.locked) {
                    Text(
                        text = "Counts submitted - reopen counting from the journal to change them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarehouseStatusColors.text3,
                    )
                }
            }

            // What the row icons mean, once the list has rows of its own (user, 14 Oct).
            if (state.exceptionsEnabled && state.exceptions.isNotEmpty()) {
                CountLegend(modifier = Modifier.padding(horizontal = WarehouseDimens.ScreenPadding))
            }

            if (state.lines.isEmpty() && state.exceptions.isEmpty()) {
                Text(
                    text = if (state.totalLines == 0) "This journal has no lines." else "No items match.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(WarehouseDimens.ScreenPadding),
                )
            }

            // Newest not-on-the-journal row first, and in view when one is added or changes
            // (seen 14 Oct: the new row sat half under the legend).
            val notOnJournal = state.exceptions.sortedByDescending { it.capturedAt }
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            val newest = notOnJournal.firstOrNull()?.let { it.clientId + it.quantity }
            androidx.compose.runtime.LaunchedEffect(newest) { if (newest != null) listState.animateScrollToItem(0) }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    horizontal = WarehouseDimens.ScreenPadding,
                    vertical = 6.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Items found that are not on the journal: first, marked as such (user, 14 Oct).
                if (state.exceptionsEnabled) {
                    items(notOnJournal, key = { "x-" + it.clientId }, contentType = { "notOnJournal" }) { exception ->
                        NotOnJournalRow(
                            exception = exception,
                            onClick = { viewModel.editUnexpected(exception.clientId) }.takeIf { exception.sentAt == null && !state.locked },
                        )
                    }
                }
                items(state.lines, key = { it.journalLineId }, contentType = { "line" }) { line ->
                    CountingLineRow(line = line, onClick = { viewModel.openLine(line) })
                }
            }
        }
    }

    state.prompt?.let { prompt ->
        QuantityDialog(
            prompt = prompt,
            onOk = { text -> viewModel.confirmQuantity(prompt.line.journalLineId, text) },
            onCancel = viewModel::cancelPrompt,
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
                    Text("This line is confirmed, not counted. Is it there?")
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
                        "All ${prompt.lines} line${if (prompt.lines == 1) "" else "s"} at this location will be " +
                            "counted as 0, and the location recorded as verified empty by you.",
                    )
                    if (prompt.alreadyCounted > 0) {
                        Text(
                            "${prompt.alreadyCounted} of them already have a count, which will be replaced with 0.",
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
                    Text("Not on this journal - choose the variant you found.", style = MaterialTheme.typography.bodyMedium)
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

    if (showCamera) {
        CameraScannerSheet(
            onDecoded = {
                viewModel.onCameraDecoded(it)
                showCamera = false
            },
            onDismiss = { showCamera = false },
        )
    }
}

/**
 * The quantity popup: which item, a whole-number box that is already focused, and Cancel / OK as
 * two full buttons side by side with room around them (the stock dialog's small text buttons
 * were easy to mis-hit with a glove).
 */
@Composable
private fun QuantityDialog(prompt: CountPrompt, onOk: (String) -> Unit, onCancel: () -> Unit) {
    val line = prompt.line
    // The shared picker (QuantityPicker): whole numbers, and the first key replaces an existing count.
    com.d365fo.mobileops.presentation.common.components.QuantityPicker(
        subtitle = listOfNotNull(
            line.itemId,
            line.itemName.takeIf { it.isNotBlank() && !it.equals(line.itemId, ignoreCase = true) },
        ).joinToString(" · "),
        hint = whereLine(line),
        initial = prompt.initial,
        replaceInitial = true,
        allowDecimal = false,
        confirmLabel = "Confirm",
        onConfirm = onOk,
        onDismiss = onCancel,
    )
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

/** Location-first: the location being counted, or a prompt to scan one. */
@Composable
private fun LocationBar(
    state: CountingScanState,
    onEmpty: () -> Unit,
    onChange: () -> Unit,
    onPick: (String) -> Unit,
) {
    val location = state.selectedLocation
    if (location == null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (state.locations.isEmpty()) "Scan a location to start counting it." else "Scan or choose a location.",
                style = MaterialTheme.typography.bodyMedium,
                color = WarehouseStatusColors.text3,
                modifier = Modifier.weight(1f),
            )
            if (state.locations.isNotEmpty()) LocationPicker(state.locations, onPick)
        }
        return
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Location $location",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${state.locationCounted} of ${state.locationTotal}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onEmpty,
                    enabled = !state.locked,
                    modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                ) { Text("Location is empty") }
                OutlinedButton(
                    onClick = onChange,
                    modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                ) { Text("Other location") }
            }
        }
    }
}

/** The journal's locations as a dropdown - each with how many of its lines are counted. */
@Composable
private fun LocationPicker(locations: List<LocationChoice>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Choose location") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            locations.forEach { choice ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${choice.locationId}   ${choice.counted}/${choice.total}",
                            color = if (choice.counted == choice.total) WarehouseStatusColors.green else Color.Unspecified,
                        )
                    },
                    onClick = {
                        open = false
                        onPick(choice.locationId)
                    },
                )
            }
        }
    }
}

/**
 * "Not on this journal": what was found, where, how many. Recorded as an exception for the
 * server to resolve - the device never adds a journal line.
 */
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

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Not on this journal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                prompt.error?.let { ErrorBanner(message = it) }
                OutlinedTextField(item, { item = it.trim() }, label = { Text("Item number") }, singleLine = true)
                // The variant found, for an item that comes in variants (14 Oct).
                if (prompt.variants.isNotEmpty() && item.equals(prompt.itemId, ignoreCase = true)) {
                    Text("Variant", style = MaterialTheme.typography.labelLarge)
                    prompt.variants.forEach { v ->
                        androidx.compose.material3.FilterChip(
                            selected = variant == v,
                            onClick = { variant = v },
                            label = { Text(v.label) },
                        )
                    }
                }
                OutlinedTextField(location, { location = it.trim() }, label = { Text("Location") }, singleLine = true)
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it.filter(Char::isDigit).take(12) },
                    label = { Text("Quantity found") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(batch, { batch = it.trim() }, label = { Text("Batch (optional)") }, singleLine = true)
                OutlinedTextField(serial, { serial = it.trim() }, label = { Text("Serial (optional)") }, singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") })
            }
        },
        confirmButton = {
            com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
                confirm = { com.d365fo.mobileops.presentation.common.components.ConfirmButton(onClick = { onSave(item, location, quantity, batch, serial, note, variant) }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Record") } },
            )
        },
    )
}


/** What the row icons mean - shown once the list has rows not on the journal (user, 14 Oct). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CountLegend(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        LegendItem(Icons.Default.RadioButtonUnchecked, WarehouseStatusColors.text3, "To count")
        LegendItem(Icons.Default.CheckCircle, WarehouseStatusColors.green, "Counted")
        LegendItem(Icons.Default.AddCircle, WarehouseStatusColors.amber, "Not on this journal")
    }
}

@Composable
private fun LegendItem(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = WarehouseStatusColors.text3)
    }
}

/** An item found that is not on the journal, in the list like a line, marked as such. */
@Composable
private fun NotOnJournalRow(exception: CountExceptionEntity, onClick: (() -> Unit)?) {
    val shape = MaterialTheme.shapes.medium
    val variant = com.d365fo.mobileops.domain.model.ProductVariant(exception.configId, exception.sizeId, exception.colorId, exception.styleId)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape)
            .border(1.5.dp, WarehouseStatusColors.amber, shape)
            .then(if (onClick != null) Modifier.clip(shape).clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.AddCircle, contentDescription = "Not on this journal", tint = WarehouseStatusColors.amber, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(exception.itemId, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = listOfNotNull(
                    "Not on this journal",
                    variant.label.takeIf { it.isNotBlank() },
                    exception.locationId?.let { "Loc $it" },
                    exception.batchId?.let { "Batch $it" },
                    exception.serialId?.let { "Serial $it" },
                    exception.sendError?.let { "not sent" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = WarehouseStatusColors.amber,
            )
        }
        Text(exception.quantity, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
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
        title = { Text(exception.itemId) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Not on this journal - quantity found", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it.filter(Char::isDigit).take(12) },
                    label = { Text("Quantity found") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                androidx.compose.material3.TextButton(onClick = onRemove) { Text("Remove from the list") }
            }
        },
        confirmButton = {
            com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
                confirm = { com.d365fo.mobileops.presentation.common.components.ConfirmButton(onClick = { onOk(quantity) }, enabled = quantity.isNotBlank(), modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("OK") } },
            )
        },
    )
}
