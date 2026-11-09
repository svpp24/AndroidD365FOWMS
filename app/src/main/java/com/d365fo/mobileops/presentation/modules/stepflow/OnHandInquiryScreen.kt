package com.d365fo.mobileops.presentation.modules.stepflow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.domain.repository.OnHandLine
import com.d365fo.mobileops.domain.repository.WarehouseOption
import com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton
import com.d365fo.mobileops.presentation.common.components.DialogButtonRow
import com.d365fo.mobileops.presentation.common.components.FlowAction
import com.d365fo.mobileops.presentation.common.components.FlowHeader
import com.d365fo.mobileops.presentation.common.components.FlowKeyHandler
import com.d365fo.mobileops.presentation.common.components.ListRow
import com.d365fo.mobileops.presentation.common.components.ListRowValue
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton
import com.d365fo.mobileops.presentation.common.components.StatusBand
import com.d365fo.mobileops.presentation.common.components.StatusKind
import com.d365fo.mobileops.presentation.common.components.TextEntryScreen
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.presentation.common.theme.WarehouseType

/**
 * Scan an item, see what is on hand. Nothing is fetched until something is scanned.
 *
 * The old design preloaded an on-hand cache so this screen could answer instantly offline. That
 * cache was wrong the moment anyone else moved stock, and on-hand is the one figure an operator
 * will act on immediately — walking to a bin because the handheld said there were twelve. A
 * live number that takes a second beats an instant number that might be yesterday's.
 *
 * 9 Nov redesign (brief §4, phase 8): reached from the red scan button on any tab, or by scanning
 * an item on Home. Scans come through ScanRouter; the screen is a [FlowHeader], a [StatusBand],
 * the item with *available* big and green, on hand and reserved under it, then a [ListRow] per
 * warehouse. F1 goes back.
 */
@Composable
fun OnHandInquiryScreen(
    onBack: () -> Unit,
    onManualEntryRequested: () -> Unit,
    viewModel: OnHandInquiryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }

    DisposableEffect(viewModel) {
        viewModel.scannerOnScreen(true)
        onDispose { viewModel.scannerOnScreen(false) }
    }
    FlowKeyHandler { if (it == FlowAction.Back) onBack() }

    Scaffold(
        containerColor = WarehouseStatusColors.pageComfort,
        topBar = { FlowHeader(title = "On-hand", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = WarehouseDimens.ScreenPadding, end = WarehouseDimens.ScreenPadding, top = 12.dp, bottom = 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "band") { OnHandBand(state) }

            item(key = "input") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SquareOutlinedButton(
                        onClick = onManualEntryRequested,
                        modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) {
                        Icon(Icons.Filled.Keyboard, contentDescription = null)
                        Text("Type it", modifier = Modifier.padding(start = 8.dp))
                    }
                    SquareOutlinedButton(
                        onClick = { showCamera = true },
                        modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                        Text("Camera", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            val item = state.itemNumber
            if (item != null && state.error == null) {
                item(key = "result") {
                    OnHandResult(itemNumber = item, productName = state.productName, rows = state.rows, scope = state.scopeLabel)
                }
                if (state.rows.isNotEmpty()) {
                    item(key = "rows-title") {
                        Text("By warehouse", style = WarehouseType.label, color = WarehouseStatusColors.inkMuted)
                    }
                    item(key = "rows") {
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(WarehouseStatusColors.surface),
                        ) {
                            state.rows.forEachIndexed { i, row ->
                                if (i > 0) HorizontalDivider(color = WarehouseStatusColors.divider)
                                OnHandRow(row)
                            }
                        }
                    }
                }
            }

            // The filter, under the answer: the switch is changed now and then, the answer every scan.
            item(key = "filter") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Label left, switch right - as "Count by location" and Settings' switches.
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = state.homeWarehouseId
                                ?.let { "This warehouse only ($it)" }
                                ?: "No warehouse assigned",
                            style = WarehouseType.body,
                            color = WarehouseStatusColors.ink,
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
                }
            }
        }
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

/** What happened last: ready, looking up, not found, or a scan this screen doesn't take. */
@Composable
private fun OnHandBand(state: OnHandInquiryState) {
    val notice = state.notice
    val error = state.error
    when {
        state.busy -> StatusBand(StatusKind.Info, "Looking up…", line = "Asking D365FO for the stock")
        error != null -> StatusBand(StatusKind.Bad, "Can't show stock", line = error)
        notice != null -> StatusBand(StatusKind.Warn, notice.title, line = notice.line)
        state.itemNumber != null -> StatusBand(StatusKind.Idle, "Ready", line = "Scan the next item")
        else -> StatusBand(StatusKind.Idle, "Ready", line = "Scan an item to see its stock")
    }
}

/** The item, with what can be used now big and green, and on hand / reserved under it. */
@Composable
private fun OnHandResult(itemNumber: String, productName: String?, rows: List<OnHandLine>, scope: String) {
    val totals = onHandTotals(rows)
    val any = totals.available.signum() > 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(WarehouseStatusColors.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(itemNumber, style = WarehouseType.code, color = WarehouseStatusColors.ink)
        productName?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = WarehouseType.body, color = WarehouseStatusColors.ink)
        }
        Spacer(Modifier.size(8.dp))
        // Available first: it is the number that decides whether the operator can pick, and it
        // is not the number D365FO puts first. Icon and word, never colour alone.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (any) Icons.Filled.CheckCircle else Icons.Filled.RemoveCircle,
                contentDescription = null,
                tint = if (any) WarehouseStatusColors.okEdge else WarehouseStatusColors.badEdge,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    rows.isEmpty() -> "No stock on hand"
                    any -> "Available"
                    else -> "None available"
                },
                style = WarehouseType.label,
                color = if (any) WarehouseStatusColors.okText else WarehouseStatusColors.badText,
            )
        }
        Text(
            formatOnHandQty(totals.available),
            style = WarehouseType.quantity,
            color = if (any) WarehouseStatusColors.okText else WarehouseStatusColors.badText,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Figure("On hand", totals.onHand)
            Figure("Reserved", totals.reserved)
        }
        Text(scope, style = WarehouseType.label, color = WarehouseStatusColors.inkMuted)
    }
}

@Composable
private fun Figure(label: String, value: java.math.BigDecimal) {
    Column {
        Text(label, style = WarehouseType.label, color = WarehouseStatusColors.inkMuted)
        Text(formatOnHandQty(value), style = WarehouseType.codeSmall, color = WarehouseStatusColors.ink)
    }
}

/** One warehouse: available on the right, the rest underneath. */
@Composable
private fun OnHandRow(row: OnHandLine) {
    val available = row.available.signum() > 0
    ListRow(
        title = row.warehouseId ?: "All warehouses",
        codeTitle = row.warehouseId != null,
        status = if (available) StatusKind.Ok else StatusKind.Idle,
        subtitles = listOfNotNull(
            row.siteId?.let { "Site $it" },
            (if (available) "Available" else "None available") +
                " · on hand ${formatOnHandQty(row.onHand)} · reserved ${formatOnHandQty(row.reserved)}",
            row.ordered.takeIf { it.signum() != 0 }?.let { "Ordered ${formatOnHandQty(it)}" },
        ),
        trailing = { ListRowValue(formatOnHandQty(row.available)) },
    )
}

/**
 * The dedicated typing screen for this inquiry - see the identical
 * [com.d365fo.mobileops.presentation.modules.dataenquiry.LocationInquiryScreen]'s note on
 * fetching the same ViewModel instance as the screen underneath. Registered on the scan router
 * too, so a scan made while typing still lands.
 */
@Composable
fun OnHandEntryScreen(
    viewModel: OnHandInquiryViewModel,
    onBack: () -> Unit,
) {
    DisposableEffect(viewModel) {
        viewModel.scannerOnScreen(true)
        onDispose { viewModel.scannerOnScreen(false) }
    }
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
            DialogButtonRow(
                dismiss = null,
                confirm = { CancelOutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
            )
        },
    )
}
