package com.d365fo.mobileops.presentation.modules.cyclecounting

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.d365fo.mobileops.data.local.entity.JournalLineEntity
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar
import com.d365fo.mobileops.presentation.common.components.CountProgressBar
import com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton
import com.d365fo.mobileops.presentation.common.components.ConfirmButton
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

                // The scan box. A hardware scan needs no button at all.
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth().focusRequester(scanFocus),
                    singleLine = true,
                    enabled = !state.locked,
                    placeholder = { Text("Scan or type item / barcode") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
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
private fun ChoiceDialog(lines: List<JournalLineEntity>, onPick: (String) -> Unit, onCancel: () -> Unit) {
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
private fun whereLine(line: JournalLineEntity): String? =
    listOfNotNull(
        line.locationId?.takeIf { it.isNotBlank() }?.let { "Loc $it" },
        line.batchId?.takeIf { it.isNotBlank() }?.let { "Batch $it" },
        line.serialId?.takeIf { it.isNotBlank() }?.let { "Serial $it" },
    ).joinToString(" · ").ifEmpty { null }
