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
    LaunchedEffect(viewModel) { viewModel.leave.collect { onBack() } }
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
                title = { Text(if (state.isWallToWall) "Wall-to-wall count" else "Cycle count") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                // Record an item not on this journal - in the top bar (feedback 3c03d94e); it was
                // a whole row under the scan card, there even with nothing recorded.
                actions = {
                    if (state.exceptionsEnabled && !state.locked && state.showList) {
                        IconButton(onClick = { viewModel.openUnexpected() }) {
                            Icon(Icons.Default.Add, contentDescription = "Record an item not on this journal")
                        }
                    }
                },
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
        val headerMax = maxHeight * 0.6f
        // A triggered check of one line: that line alone, no list, filters or progress (user, 8 Nov).
        val focusLine = state.focusLine
        if (focusLine != null) {
            FocusedCountCard(
                line = focusLine,
                originType = state.originType,
                locked = state.locked,
                message = state.message,
                onConfirm = viewModel::confirmAndEnd,
            )
        } else Column(modifier = Modifier.fillMaxSize()) {
            // At most 60% of the height, scrolling on its own past that: with a bin, a location
            // and the freeze notice, a small handheld (360x640dp) left the list one row, mostly
            // under the gesture bar (seen 8 Nov, wall-to-wall). It takes only what it needs.
            val headerScroll = rememberScrollState()
            // A scan's result and its Undo sit under the scan card: brought into view each time,
            // or with a bin on a small screen they were below the fold (test, 8 Nov).
            LaunchedEffect(state.message) {
                if (state.message != null) {
                    delay(50)
                    headerScroll.animateScrollTo(headerScroll.maxValue)
                }
            }
            // Where the counter is in location > bin > items, the same trail as PO receiving
            // (user, 8 Nov). Above the scrolling header, so it stays in view when a scan message
            // scrolls the header down. Only when there is more than the item step.
            if (state.byLocation || state.binsRequired) {
                val steps = listOfNotNull(
                    "Location".takeIf { state.byLocation },
                    "Bin".takeIf { state.binsRequired },
                    "Items",
                )
                val current = when {
                    state.byLocation && state.selectedLocation == null -> 0
                    state.binsRequired && state.currentBin == null -> steps.indexOf("Bin")
                    else -> steps.lastIndex
                }
                com.d365fo.mobileops.presentation.common.components.StepTrail(
                    names = steps,
                    current = current,
                    // A done tab goes back to that step - the way back now there are no Back /
                    // Change buttons (user, 9 Nov). Only to another location when there is one.
                    onStepClick = { i ->
                        if (!state.locked) when (steps[i]) {
                            "Location" -> if (state.locations.size > 1) viewModel.clearLocation()
                            "Bin" -> viewModel.changeBin()
                        }
                    },
                    // Edge to edge under the bar (Harbour).
                    bleed = 0.dp,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = headerMax)
                    .verticalScroll(headerScroll)
                    .padding(horizontal = WarehouseDimens.ScreenPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The scan card first (user, 9 Nov); what it is counting - the journal, location,
                // bin and how far along - as labelled chips in the card under it. No progress
                // card or hints: the chips say it.
                state.addBinPrompt?.let { bin ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = viewModel::cancelAddBin,
                        title = { Text("Add bin $bin?") },
                        text = { Text("$bin is not on this store's list. Add it, and count in it now?") },
                        confirmButton = {
                            com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                                dismiss = {
                                    com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(
                                        onClick = viewModel::cancelAddBin,
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    ) { Text("Cancel") }
                                },
                                confirm = {
                                    com.d365fo.mobileops.presentation.common.components.ConfirmButton(
                                        onClick = viewModel::confirmAddBin,
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    ) { Text("Add bin") }
                                },
                            )
                        },
                    )
                }
                val needsBin = state.binsRequired && state.currentBin == null
                com.d365fo.mobileops.presentation.common.components.PromptHeroCard(
                    // Location, then bin, then item.
                    prompt = when {
                        state.byLocation && state.selectedLocation == null -> "Scan a location"
                        needsBin -> "Scan a bin"
                        else -> "Scan item"
                    },
                    pills = listOfNotNull(
                        Icons.Default.Description to "Journal ${viewModel.journalId}",
                        state.selectedLocation?.let { Icons.Default.LocationOn to "Location $it" },
                        state.currentBin?.let { Icons.Default.Inventory2 to "Bin $it" },
                        (Icons.Default.Checklist to "${state.counted} of ${state.totalLines} counted").takeIf { state.showProgress },
                        (Icons.Default.AddCircle to "${state.exceptions.size} not on journal").takeIf { state.exceptions.isNotEmpty() },
                    ),
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

                // Count by location, where the journal allows it - a quiet switch row.
                if (state.showProgress && state.showLocationSwitch) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Count by location",
                            style = MaterialTheme.typography.bodyMedium,
                            color = WarehouseStatusColors.text2,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = state.byLocation,
                            onCheckedChange = viewModel::setByLocation,
                            enabled = !state.locked,
                        )
                    }
                }

                // A wall-to-wall location's "is empty".
                if (state.byLocation || state.binsRequired) {
                    PlaceCard(
                        state = state,
                        onEmpty = viewModel::requestLocationEmpty,
                        onPick = viewModel::pickLocation,
                    )
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
                        text = "Should be empty - confirm, or count what's there.",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarehouseStatusColors.amber,
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
                        text = "Submitted - reopen to change.",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarehouseStatusColors.text3,
                    )
                }
            }

            // The location and bin steps list where to count, not the journal's items (user,
            // 9 Nov): the scan field first, then the locations / this location's bins.
            val choosingLocation = state.byLocation && state.selectedLocation == null
            val choosingBin = !choosingLocation && state.binsRequired && state.currentBin == null
            if (choosingLocation || choosingBin) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = WarehouseDimens.ScreenPadding, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (choosingLocation) {
                        item { LocationRows(state.locations, viewModel::pickLocation, enabled = !state.locked) }
                    } else {
                        item {
                            com.d365fo.mobileops.presentation.common.components.ListHeader(
                                icon = Icons.Default.Inventory2,
                                title = state.selectedLocation?.let { "Bins in $it" } ?: "Bins",
                                count = state.binsHere.size,
                            )
                        }
                        if (state.binsHere.isEmpty()) {
                            item {
                                Text(
                                    "No bins listed here - scan the bin; it is counted as scanned.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = WarehouseStatusColors.text3,
                                )
                            }
                        }
                        items(state.binsHere, key = { "bin-$it" }) { bin ->
                            com.d365fo.mobileops.presentation.common.components.ListCard {
                                com.d365fo.mobileops.presentation.common.components.LineRow(
                                    title = bin,
                                    lead = {
                                        com.d365fo.mobileops.presentation.common.components.LeadDot(
                                            com.d365fo.mobileops.presentation.common.components.Lead.Idle,
                                            icon = Icons.Default.Inventory2,
                                        )
                                    },
                                    trailing = { com.d365fo.mobileops.presentation.common.components.RowChevron() },
                                    onClick = { viewModel.pickBin(bin) },
                                )
                            }
                        }
                    }
                }
                return@Column
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
    }

    state.prompt?.let { prompt ->
        QuantityDialog(
            prompt = prompt,
            bin = state.currentBin,
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
private fun QuantityDialog(prompt: CountPrompt, bin: String?, onOk: (String) -> Unit, onCancel: () -> Unit) {
    val line = prompt.line
    // The shared picker (QuantityPicker): whole numbers, and the first key replaces an existing count.
    com.d365fo.mobileops.presentation.common.components.QuantityPicker(
        subtitle = listOfNotNull(
            line.itemId,
            line.itemName.takeIf { it.isNotBlank() && !it.equals(line.itemId, ignoreCase = true) },
        ).joinToString(" · "),
        // With bins the quantity is for this bin, not the line's total: it says so.
        hint = listOfNotNull(bin?.let { "Bin $it" }, whereLine(line)).joinToString(" · "),
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
/**
 * Where the counter is: "AAC  >  BIN-A1" and how much of the location is counted. Moving is the
 * same in every count type (user, 8 Nov): Back one step (bin step: to the location; item step: to
 * the bin) and "Change location / bin", a dialog showing the current location and bin. Choose
 * location before one is chosen. While changing, what is being left and Cancel back to it.
 * Location is empty only on a wall-to-wall count, which settles a location at a time.
 */
@Composable
private fun PlaceCard(
    state: CountingScanState,
    onEmpty: () -> Unit,
    onPick: (String) -> Unit,
) {
    // Harbour (9 Nov): where the counter is shows as pills on the scan card and the step tabs
    // go back a step - no Back, Change or Cancel buttons here. What is left: the journal's
    // locations as rows to pick from, and a wall-to-wall location's "is empty".
    val location = state.selectedLocation
    if (state.isWallToWall && location != null) {
        androidx.compose.material3.OutlinedButton(
            onClick = onEmpty,
            enabled = !state.locked,
            shape = com.d365fo.mobileops.presentation.common.components.HeroFieldShape,
            border = androidx.compose.foundation.BorderStroke(1.5.dp, WarehouseStatusColors.amber),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Location is empty", fontWeight = FontWeight.Bold, color = WarehouseStatusColors.amber) }
    }
    // The locations to pick from are listed under the scan card, in the screen's list (9 Nov).
}

/** The journal's locations as Harbour rows - each with how many of its lines are counted. */
@Composable
private fun LocationRows(locations: List<LocationChoice>, onPick: (String) -> Unit, enabled: Boolean) {
    com.d365fo.mobileops.presentation.common.components.ListHeader(
        icon = Icons.Default.LocationOn,
        title = "Locations",
        count = locations.size,
        trailing = "${locations.count { it.total > 0 && it.counted == it.total }} counted",
    )
    com.d365fo.mobileops.presentation.common.components.ListCard {
        locations.forEachIndexed { i, choice ->
            if (i > 0) com.d365fo.mobileops.presentation.common.components.RowDivider()
            val done = choice.total > 0 && choice.counted == choice.total
            com.d365fo.mobileops.presentation.common.components.LineRow(
                title = choice.locationId,
                lines = listOf("${choice.counted} of ${choice.total} counted"),
                lead = {
                    com.d365fo.mobileops.presentation.common.components.LeadDot(
                        if (done) com.d365fo.mobileops.presentation.common.components.Lead.Done else com.d365fo.mobileops.presentation.common.components.Lead.Idle,
                        icon = if (done) Icons.Default.CheckCircle else Icons.Default.LocationOn,
                    )
                },
                trailing = { com.d365fo.mobileops.presentation.common.components.RowChevron() },
                onClick = { if (enabled) onPick(choice.locationId) },
            )
        }
    }
}

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
    // A Harbour row with an amber marker and tag - in the list like a line, marked as not on it.
    val variant = com.d365fo.mobileops.domain.model.ProductVariant(exception.configId, exception.sizeId, exception.colorId, exception.styleId)
    com.d365fo.mobileops.presentation.common.components.ListCard {
        com.d365fo.mobileops.presentation.common.components.LineRow(
            title = exception.itemId,
            status = {
                com.d365fo.mobileops.presentation.common.components.StatusTag(
                    if (exception.sendError != null) "Not sent" else "Not on journal",
                    if (exception.sendError != null) com.d365fo.mobileops.presentation.common.components.Lead.Bad else com.d365fo.mobileops.presentation.common.components.Lead.Warn,
                )
            },
            lines = listOf(
                listOfNotNull(
                    variant.label.takeIf { it.isNotBlank() },
                    exception.locationId?.let { "Loc $it" },
                    exception.batchId?.let { "Batch $it" },
                    exception.serialId?.let { "Serial $it" },
                ).joinToString(" · "),
            ),
            lead = {
                com.d365fo.mobileops.presentation.common.components.LeadDot(
                    com.d365fo.mobileops.presentation.common.components.Lead.Warn,
                    icon = Icons.Default.AddCircle,
                )
            },
            trailing = { com.d365fo.mobileops.presentation.common.components.QtyTrail(exception.quantity, null) },
            onClick = onClick,
        )
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
 * A triggered or opportunity check of one line: where, what, and one large quantity box that is
 * focused on arrival. Confirm saves it and ends the count; the screen then closes.
 */
@Composable
private fun FocusedCountCard(
    line: CountLine,
    originType: String,
    locked: Boolean,
    message: ScanMessage?,
    onConfirm: (String) -> Unit,
) {
    var quantity by androidx.compose.runtime.saveable.rememberSaveable(line.journalLineId) { mutableStateOf(line.countedQuantity.orEmpty()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(line.journalLineId) {
        delay(300)
        runCatching { focus.requestFocus() }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = WarehouseDimens.ScreenPadding, vertical = 16.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = (com.d365fo.mobileops.domain.model.originLabel(originType) ?: "Count").uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = WarehouseStatusColors.amber,
        )
        Text(
            text = line.locationId?.takeIf { it.isNotBlank() }?.let { "Location $it" } ?: "No location",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(line.itemId, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = WarehouseStatusColors.accent)
        val details = listOfNotNull(
            line.itemName.takeIf { it.isNotBlank() && !it.equals(line.itemId, ignoreCase = true) },
            line.variant.label.takeIf { it.isNotBlank() },
            line.batchId?.takeIf { it.isNotBlank() }?.let { "Batch $it" },
            line.serialId?.takeIf { it.isNotBlank() }?.let { "Serial $it" },
        )
        if (details.isNotEmpty()) {
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyLarge, color = WarehouseStatusColors.text3)
        }
        OutlinedTextField(
            value = quantity,
            onValueChange = { quantity = it.filter(Char::isDigit).take(12) },
            label = { Text("Quantity found") },
            singleLine = true,
            enabled = !locked,
            textStyle = MaterialTheme.typography.displaySmall.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.Bold),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (quantity.isNotEmpty()) onConfirm(quantity) }),
            shape = com.d365fo.mobileops.presentation.common.components.HeroFieldShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).focusRequester(focus),
        )
        message?.let {
            Text(
                text = it.text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (it.isError) MaterialTheme.colorScheme.error else WarehouseStatusColors.green,
            )
        }
        if (locked) {
            Text("Submitted - reopen to change.", style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.text3)
        } else {
            Button(
                onClick = { onConfirm(quantity) },
                enabled = quantity.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Confirm and end count", fontWeight = FontWeight.Bold) }
        }
    }
}
