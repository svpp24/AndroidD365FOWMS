package com.d365fo.mobileops.presentation.modules.cyclecounting

import com.d365fo.mobileops.presentation.common.components.BarcodeFieldIcon
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.data.local.dao.CountLine
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar
import com.d365fo.mobileops.presentation.common.components.CountProgressBar
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

    // Cursor in the scan box on arrival, and again every time the popup closes. The short delay
    // lets the closing dialog give the window's focus back first.
    LaunchedEffect(Unit) {
        delay(300)
        runCatching { scanFocus.requestFocus() }
    }
    LaunchedEffect(state.refocusNonce) {
        if (state.refocusNonce > 0) {
            delay(250)
            runCatching { scanFocus.requestFocus() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // Single line + ellipsis, not left to wrap - the journal name's own description
                // (e.g. "Inventory Counting Journal") isn't length-bounded, and an unconstrained
                // title here overflowed the top bar the same way View lines' once did.
                title = { Text(state.journalDescription, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${state.counted} of ${state.totalLines} counted",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${state.percent}%",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (state.totalLines > 0) {
                    CountProgressBar(progress = state.counted.toFloat() / state.totalLines)
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

                if (state.locationModeAvailable) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Count by location", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = state.byLocation,
                            onCheckedChange = viewModel::setByLocation,
                            enabled = !state.locked,
                        )
                    }
                }

                if (state.byLocation) {
                    LocationBar(
                        state = state,
                        onEmpty = viewModel::requestLocationEmpty,
                        onChange = viewModel::clearLocation,
                        onPick = viewModel::pickLocation,
                    )
                }

                // The scan box. A hardware scan needs no button at all.
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth().focusRequester(scanFocus),
                    singleLine = true,
                    enabled = !state.locked,
                    placeholder = {
                        Text(
                            if (state.byLocation && state.selectedLocation == null) "Scan a location"
                            else "Scan or type item / barcode",
                        )
                    },
                    leadingIcon = { BarcodeFieldIcon() },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        } else {
                            IconButton(onClick = { showCamera = true }, enabled = !state.locked) {
                                Icon(Icons.Default.PhotoCamera, contentDescription = "Scan with camera")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.submitTyped() }),
                )

                state.message?.let { message ->
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (message.isError) MaterialTheme.colorScheme.error else WarehouseStatusColors.green,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = viewModel::dismissMessage),
                    )
                    message.unexpectedItemId?.let { itemId ->
                        OutlinedButton(
                            onClick = { viewModel.openUnexpected(itemId) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
                        ) { Text("Not on this journal - record it") }
                    }
                }

                if (state.exceptionsEnabled && !state.locked) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = when (val n = state.exceptions.size) {
                                0 -> "No items recorded as not on this journal"
                                1 -> "1 item not on this journal"
                                else -> "$n items not on this journal"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = WarehouseStatusColors.text3,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.openUnexpected() }) { Text("Add") }
                    }
                    state.exceptions.forEach { exception ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = listOfNotNull(
                                    exception.itemId,
                                    "×${exception.quantity}",
                                    exception.locationId?.let { "Loc $it" },
                                    exception.sendError?.let { "not sent" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (exception.sendError != null) WarehouseStatusColors.amber else WarehouseStatusColors.text3,
                                modifier = Modifier.weight(1f),
                            )
                            if (exception.sentAt == null) {
                                IconButton(onClick = { viewModel.removeUnexpected(exception.clientId) }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Remove ${exception.itemId}")
                                }
                            }
                        }
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

            if (state.lines.isEmpty()) {
                Text(
                    text = if (state.totalLines == 0) "This journal has no lines." else "No items match.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(WarehouseDimens.ScreenPadding),
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    horizontal = WarehouseDimens.ScreenPadding,
                    vertical = 6.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
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
            confirmButton = { ConfirmButton(onClick = { viewModel.confirmVerified(line.journalLineId, true) }) { Text("Confirm present") } },
            dismissButton = {
                CancelOutlinedButton(
                    onClick = {
                        if (line.confirmedAt != null) viewModel.confirmVerified(line.journalLineId, false) else viewModel.cancelVerify()
                    },
                ) { Text(if (line.confirmedAt != null) "Withdraw confirmation" else "Cancel") }
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
            confirmButton = { ConfirmButton(onClick = viewModel::confirmLocationEmpty) { Text("Yes, it's empty") } },
            dismissButton = { CancelOutlinedButton(onClick = viewModel::cancelLocationEmpty) { Text("Cancel") } },
        )
    }

    state.unexpected?.let { prompt ->
        UnexpectedItemDialog(
            prompt = prompt,
            onSave = viewModel::saveUnexpected,
            onCancel = viewModel::cancelUnexpected,
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
    // Selected, so typing replaces an existing count rather than appending to it.
    var value by remember(line.journalLineId) {
        mutableStateOf(TextFieldValue(prompt.initial, TextRange(0, prompt.initial.length)))
    }
    val quantityFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { quantityFocus.requestFocus() } }

    Dialog(onDismissRequest = onCancel) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = line.itemId,
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    )
                    line.itemName.takeIf { it.isNotBlank() && it != line.itemId }?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyMedium)
                    }
                    whereLine(line)?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.text3)
                    }
                }

                OutlinedTextField(
                    value = value,
                    onValueChange = { input ->
                        value = input.copy(text = input.text.filter { it.isDigit() }.take(12))
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(quantityFocus),
                    singleLine = true,
                    label = { Text("Quantity") },
                    textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onOk(value.text) }),
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CancelOutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) { Text("Cancel") }
                    ConfirmButton(
                        onClick = { onOk(value.text) },
                        modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) { Text("OK") }
                }
            }
        }
    }
}
/** The scanned item is on several lines: pick the location / batch to count. */
@Composable
private fun ChoiceDialog(lines: List<CountLine>, onPick: (String) -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("${lines.first().itemId} is on ${lines.size} lines") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pick the location or batch to count.", style = MaterialTheme.typography.bodyMedium)
                lines.forEach { line ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onPick(line.journalLineId) }) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = whereLine(line) ?: "Line ${line.lineNumber}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = line.countedQuantity ?: "-",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            CancelOutlinedButton(
                onClick = onCancel,
                modifier = Modifier.heightIn(min = WarehouseDimens.MinTouchTarget),
            ) { Text("Cancel") }
        },
    )
}

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
                    fontFamily = FontFamily.Monospace,
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
                            fontFamily = FontFamily.Monospace,
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
    onSave: (item: String, location: String, quantity: String, batch: String, serial: String, note: String) -> Unit,
    onCancel: () -> Unit,
) {
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
            ConfirmButton(onClick = { onSave(item, location, quantity, batch, serial, note) }) { Text("Record") }
        },
        dismissButton = { CancelOutlinedButton(onClick = onCancel) { Text("Cancel") } },
    )
}
