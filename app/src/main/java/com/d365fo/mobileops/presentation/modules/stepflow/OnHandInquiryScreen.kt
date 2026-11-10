package com.d365fo.mobileops.presentation.modules.stepflow

import com.d365fo.mobileops.presentation.common.components.BarcodeFieldIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import com.d365fo.mobileops.presentation.common.focus.FocusCenter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
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
import com.d365fo.mobileops.presentation.common.components.heroFieldColors
import com.d365fo.mobileops.presentation.common.components.TextEntryScreen
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import java.math.BigDecimal

/**
 * On-hand in the Focus design (brief section 9): the item scanned, its available quantity large in
 * the available colour with on hand / reserved / ordered under it, and WHERE - each warehouse's
 * available - underneath. Scan or type the next item in the pill. Which warehouses are asked is
 * in the ⋮ menu (this warehouse only, or one chosen from the list, or all).
 */
@Composable
fun OnHandInquiryScreen(
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onManualEntryRequested: () -> Unit,
    viewModel: OnHandInquiryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

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

    com.d365fo.mobileops.presentation.common.focus.FocusScreen(
        title = "On-hand",
        onBack = onBack,
        counter = if (state.thisWarehouseOnly) state.homeWarehouseId else state.scopeLabel,
        band = onHandBand(state),
        scanPill = { com.d365fo.mobileops.presentation.common.focus.ScanPillField(enabled = !state.busy) },
        topBarTrailing = {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More", tint = com.d365fo.mobileops.presentation.common.theme.FocusColors.ink)
                }
                androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Camera scan") },
                        leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                        onClick = { menuOpen = false; showCamera = true },
                        modifier = Modifier.heightIn(min = 56.dp),
                    )
                    if (state.homeWarehouseId != null) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Text(
                                    if (state.thisWarehouseOnly) "Other warehouses" else "This warehouse only (${state.homeWarehouseId})",
                                )
                            },
                            leadingIcon = { Icon(Icons.Default.Warehouse, contentDescription = null) },
                            onClick = { menuOpen = false; viewModel.setThisWarehouseOnly(!state.thisWarehouseOnly) },
                            modifier = Modifier.heightIn(min = 56.dp),
                        )
                    }
                    if (!state.thisWarehouseOnly) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Choose warehouse") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            onClick = { menuOpen = false; viewModel.openWarehousePicker() },
                            modifier = Modifier.heightIn(min = 56.dp),
                        )
                    }
                }
            }
        },
    ) {
        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
        }
        OnHandBody(state)
    }
}

/** What the last scan found: an error, no stock, or nothing yet asked. */
internal fun onHandBand(state: OnHandInquiryState): com.d365fo.mobileops.presentation.common.focus.BandState? = when {
    state.error != null -> bandOf(com.d365fo.mobileops.presentation.common.focus.BandKind.Bad, state.error)
    // An item with no on-hand row is a real answer, and a different one from an item that does
    // not exist: saying so stops an operator scanning again to see if it was a bad read.
    state.itemNumber != null && state.rows.isEmpty() && !state.busy ->
        com.d365fo.mobileops.presentation.common.focus.BandState(com.d365fo.mobileops.presentation.common.focus.BandKind.Warn, "No stock on hand", state.itemNumber)
    else -> null
}

/** The centre (available, large) and WHERE: each warehouse's available. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.OnHandBody(state: OnHandInquiryState) {
    val item = state.itemNumber
    if (item == null) {
        FocusCenter(
            caption = "On-hand",
            code = if (state.thisWarehouseOnly) state.homeWarehouseId ?: "–" else state.scopeLabel,
            description = "How many, and where?",
        )
        return
    }
    fun sum(f: (OnHandLine) -> BigDecimal) = state.rows.fold(BigDecimal.ZERO) { acc, r -> acc + f(r) }
    fun plain(v: BigDecimal) = v.stripTrailingZeros().toPlainString()
    val many = state.rows.size > 1
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            com.d365fo.mobileops.presentation.common.focus.FocusCaption("Available")
            Text(
                item,
                style = com.d365fo.mobileops.presentation.common.theme.FocusType.code,
                color = com.d365fo.mobileops.presentation.common.theme.FocusColors.ink,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            state.productName?.takeIf { it.isNotBlank() && !it.equals(item, ignoreCase = true) }?.let {
                Text(
                    it,
                    style = com.d365fo.mobileops.presentation.common.theme.FocusType.description,
                    color = com.d365fo.mobileops.presentation.common.theme.FocusColors.inkSoft,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            if (state.rows.isNotEmpty()) {
                Text(
                    plain(sum { it.available }),
                    style = com.d365fo.mobileops.presentation.common.theme.FocusType.bigQuantity,
                    color = com.d365fo.mobileops.presentation.common.theme.FocusColors.availableQty,
                )
                Text(
                    listOf(
                        "on hand ${plain(sum { it.onHand })}",
                        "reserved ${plain(sum { it.reserved })}",
                        "ordered ${plain(sum { it.ordered })}",
                    ).joinToString(" · ") + if (many) " · ${state.rows.size} warehouses" else "",
                    style = com.d365fo.mobileops.presentation.common.theme.FocusType.unitLine,
                    color = com.d365fo.mobileops.presentation.common.theme.FocusColors.caption,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        if (state.rows.isNotEmpty()) {
            Text(
                "WHERE",
                style = com.d365fo.mobileops.presentation.common.theme.FocusType.caption,
                color = com.d365fo.mobileops.presentation.common.theme.FocusColors.caption,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp),
            )
            state.rows.forEach { row ->
                com.d365fo.mobileops.presentation.common.focus.FocusListRow(
                    title = listOfNotNull(row.warehouseId, row.siteId?.let { "Site $it" }).joinToString(" · ").ifBlank { "All warehouses" },
                    subtitle = "On hand ${plain(row.onHand)} · reserved ${plain(row.reserved)} · ordered ${plain(row.ordered)} · total available ${plain(row.totalAvailable)}",
                    count = plain(row.available),
                    countColor = com.d365fo.mobileops.presentation.common.theme.FocusColors.availableQty,
                    icon = Icons.Default.Warehouse,
                )
            }
        }
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

/** On-hand samples for the layout test: 0 nothing asked, 1 one warehouse, 2 three warehouses. */
@Composable
internal fun OnHandFocusSample(kind: Int) {
    fun line(wh: String, avail: Int) = OnHandLine("BSR-11397-0", "BSP", wh, BigDecimal(avail + 3), BigDecimal(avail), BigDecimal(3), BigDecimal(12), BigDecimal(avail + 12))
    val state = when (kind) {
        0 -> OnHandInquiryState(homeWarehouseId = "AAC")
        1 -> OnHandInquiryState(homeWarehouseId = "AAC", itemNumber = "BSR-11397-0", productName = "Panadol Extra 24s tablets, film-coated", rows = listOf(line("AAC", 15)))
        else -> OnHandInquiryState(
            homeWarehouseId = "AAC", thisWarehouseOnly = false, itemNumber = "BSR-11397-0", productName = "Panadol Extra 24s",
            rows = listOf(line("AAC", 15), line("ABB", 4), line("DXB-MAIN", 120)),
        )
    }
    com.d365fo.mobileops.presentation.common.focus.FocusScreen(
        title = "On-hand",
        onBack = {},
        counter = if (state.thisWarehouseOnly) state.homeWarehouseId else state.scopeLabel,
        band = onHandBand(state),
        scanPill = { com.d365fo.mobileops.presentation.common.focus.ScanPill(onTap = {}) },
    ) { OnHandBody(state) }
}
