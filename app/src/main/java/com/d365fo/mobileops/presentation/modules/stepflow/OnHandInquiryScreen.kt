package com.d365fo.mobileops.presentation.modules.stepflow

import com.d365fo.mobileops.presentation.common.components.BarcodeFieldIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import com.d365fo.mobileops.presentation.common.components.SquareTextButton as TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.domain.repository.OnHandLine
import com.d365fo.mobileops.domain.repository.WarehouseOption
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.components.HeroEnterBadge
import com.d365fo.mobileops.presentation.common.components.HeroFieldShape
import com.d365fo.mobileops.presentation.common.components.PromptHeroCard
import com.d365fo.mobileops.presentation.common.components.heroFieldColors
import com.d365fo.mobileops.presentation.common.components.TextEntryScreen
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import java.math.BigDecimal

/**
 * Scan an item, see what is on hand. Nothing is fetched until something is scanned.
 *
 * The old design preloaded an on-hand cache so this screen could answer instantly offline. That
 * cache was wrong the moment anyone else moved stock, and on-hand is the one figure an operator
 * will act on immediately — walking to a bin because the handheld said there were twelve. A
 * live number that takes a second beats an instant number that might be yesterday's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnHandInquiryScreen(
    onBack: () -> Unit,
    onManualEntryRequested: () -> Unit,
    viewModel: OnHandInquiryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Read-only field now (see below) - no keyboardController fight needed any more. The old
    // double-hide-with-delay here was working around this exact screen's IME auto-show
    // consistently beating a single hide() when reached via the bottom tab's restoreState/
    // launchSingleTop transition (unlike every pushed screen, where one hide() was enough); a
    // readOnly field makes that race moot everywhere, this screen included.
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("On-hand") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            // A fixed slot, so the bar never moves the scan card below it.
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(4.dp)) { if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }

            // Read-only tap target, not an editable field - a scan lands straight in the
            // ViewModel (see HidScannerBridge), and typing now happens on a dedicated screen
            // reached by tapping this one, matching every other scan-first field in this app.
            //
            // Overlaid Box, not clickable() on the field itself - a read-only field's own
            // pointer input (for cursor placement) swallows a tap before an outer clickable on
            // the same modifier chain ever sees it - confirmed live.
            PromptHeroCard(
                prompt = "Scan item",
                pills = listOfNotNull(
                    state.homeWarehouseId?.takeIf { state.thisWarehouseOnly }?.let { Icons.Default.Warehouse to "Store $it only" },
                ),
            ) {
            Box {
                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge,
                    shape = HeroFieldShape,
                    colors = heroFieldColors(),
                    leadingIcon = { BarcodeFieldIcon() },
                    trailingIcon = { HeroEnterBadge() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .focusRequester(focusRequester),
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(onClick = onManualEntryRequested),
                )
            }
            // Inside the card, as on the creation screens and Counting.
            com.d365fo.mobileops.presentation.common.components.HeroCameraButton(onClick = { showCamera = true })
            }


            // Label left, switch right - as "Count by location" and Settings' switches.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = state.homeWarehouseId
                        ?.let { "This warehouse only ($it)" }
                        ?: "No warehouse assigned",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.thisWarehouseOnly,
                    onCheckedChange = viewModel::setThisWarehouseOnly,
                    enabled = state.homeWarehouseId != null,
                )
            }

            // With the filter off, the operator picks the warehouse rather than reading a
            // hundred rows to find one. "All warehouses" stays available because "where is
            // this stock?" is a real question the enquiry is for.
            if (!state.thisWarehouseOnly) {
                // The app's dropdown look; the list itself is a searchable dialog (many warehouses).
                com.d365fo.mobileops.presentation.common.components.DropdownTrigger(
                    text = if (state.loadingWarehouses) null else "Warehouse: ${state.scopeLabel}",
                    placeholder = "Loading warehouses…",
                    onClick = viewModel::openWarehousePicker,
                )
            }

            if (state.pickerOpen) {
                WarehousePickerDialog(
                    warehouses = state.warehouses,
                    loading = state.loadingWarehouses,
                    selected = state.pickedWarehouseId,
                    onPick = viewModel::pickWarehouse,
                    onDismiss = viewModel::dismissWarehousePicker,
                )
            }

            state.error?.let { message ->
                ErrorBanner(message = message)
            }

            state.itemNumber?.let { item ->
                Column {
                    Text(
                        text = item,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors.accent,
                    )
                    state.productName?.takeIf { it.isNotBlank() && !it.equals(item, ignoreCase = true) }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors.text2)
                    }
                }
            }

            if (state.rows.isEmpty() && state.itemNumber != null && !state.busy) {
                // An item with no on-hand row is a real answer, and a different one from an
                // item that does not exist. Saying "none" rather than showing an empty list
                // is what stops an operator scanning again to see if it was a bad read.
                Text(
                    text = "No stock on hand.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.rows, key = { "${it.siteId}:${it.warehouseId}" }) { row ->
                    OnHandRow(row)
                }
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
}

/**
 * The dedicated typing screen for this inquiry - see the identical
 * [com.d365fo.mobileops.presentation.modules.dataenquiry.LocationInquiryScreen]'s note on
 * fetching the same ViewModel instance as the screen underneath.
 */
@Composable
fun OnHandEntryScreen(
    viewModel: OnHandInquiryViewModel,
    onBack: () -> Unit,
) {
    TextEntryScreen(
        title = "On-hand",
        hint = "Scan item or barcode",
        onBack = onBack,
        onSubmit = { value ->
            viewModel.lookUp(value)
            onBack()
        },
    )
}

@Composable
private fun OnHandRow(row: OnHandLine) {
    // Harbour: a white card, the warehouse as its heading, and the figures as a 2x2 grid of
    // tiles - Available first and in green, the number that decides whether it can be picked.
    val c = com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Color.White, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(
            text = listOfNotNull(
                row.siteId?.let { "Site $it" },
                row.warehouseId?.let { "Whs $it" },
            ).joinToString(" · ").ifBlank { "All warehouses" },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold,
            color = c.accent,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuantityTile("Available", row.available, c.green, Modifier.weight(1f))
            QuantityTile("On hand", row.onHand, c.accent, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuantityTile("Reserved", row.reserved, c.accent, Modifier.weight(1f))
            QuantityTile("Ordered", row.ordered, c.accent, Modifier.weight(1f))
        }
        Text(
            "Total available ${row.totalAvailable.stripTrailingZeros().toPlainString()}",
            style = MaterialTheme.typography.labelLarge,
            color = c.text3,
        )
    }
}

@Composable
private fun QuantityTile(label: String, value: BigDecimal, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors.surface2, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors.text3)
        Text(
            value.stripTrailingZeros().toPlainString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = color,
        )
    }
}

/**
 * Picks one warehouse, or all of them.
 *
 * Filtered as you type because the estate runs to well over a hundred warehouses: a plain list
 * is a scroll long enough that an operator gives up and reads the unfiltered results instead,
 * which is the thing this control exists to avoid. Matching covers the code and the name, since
 * a worker may know either.
 */
@Composable
private fun WarehousePickerDialog(
    warehouses: List<WarehouseOption>,
    loading: Boolean,
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    val shown = remember(query, warehouses) {
        val q = query.trim()
        if (q.isEmpty()) {
            warehouses
        } else {
            warehouses.filter {
                it.warehouseId.contains(q, ignoreCase = true) ||
                    it.warehouseName.contains(q, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Warehouse") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Text(
                    text = "All warehouses",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected == null) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onPick(null) }
                        .padding(vertical = 12.dp),
                )
                HorizontalDivider()

                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.warehouseId }) { warehouse ->
                        Text(
                            text = warehouse.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (warehouse.warehouseId == selected) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                // 48dp: this is tapped in gloves.
                                .heightIn(min = 48.dp)
                                .clickable { onPick(warehouse.warehouseId) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }

                if (!loading && shown.isEmpty()) {
                    Text(
                        text = if (warehouses.isEmpty()) {
                            "No warehouses could be loaded."
                        } else {
                            "No warehouse matches \"$query\"."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                dismiss = null,
                confirm = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
            )
        },
    )
}
