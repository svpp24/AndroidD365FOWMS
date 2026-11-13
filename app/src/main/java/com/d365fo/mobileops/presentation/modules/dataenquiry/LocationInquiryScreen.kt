package com.d365fo.mobileops.presentation.modules.dataenquiry

import com.d365fo.mobileops.presentation.common.components.BarcodeFieldIcon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoCamera
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton as OutlinedButton
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.d365fo.mobileops.presentation.common.focus.FocusCenter
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.components.HeroEnterBadge
import com.d365fo.mobileops.presentation.common.components.HeroFieldShape
import com.d365fo.mobileops.presentation.common.components.heroFieldColors
import com.d365fo.mobileops.presentation.common.components.TextEntryScreen
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * One scan: does this location exist in this warehouse, and can it be received into.
 *
 * Read-only - there is nothing here that changes a location's state, just the same check the
 * receiving flow already makes at the put-away step, surfaced on its own for a supervisor who
 * wants the answer without walking through a whole receipt.
 */
@Composable
fun LocationInquiryScreen(
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onManualEntryRequested: () -> Unit,
    viewModel: LocationInquiryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    val facts = state.result

    // Focus design: the answer in the band (open or blocked for put-away), the location in the
    // centre, the warehouse it is checked in under it; scan or type the next location in the pill.
    com.d365fo.mobileops.presentation.common.focus.FocusScreen(
        title = "Location inquiry",
        onBack = onBack,
        counter = state.warehouseId.takeIf { it.isNotBlank() },
        band = when {
            state.error != null -> com.d365fo.mobileops.presentation.modules.stepflow.bandOf(
                com.d365fo.mobileops.presentation.common.focus.BandKind.Bad, state.error.orEmpty(),
            )
            facts == null -> null
            facts.canReceiveInto -> com.d365fo.mobileops.presentation.common.focus.BandState(
                com.d365fo.mobileops.presentation.common.focus.BandKind.Ok, "Open for put-away", "${facts.warehouseId} · ${facts.locationId}",
            )
            else -> com.d365fo.mobileops.presentation.common.focus.BandState(
                com.d365fo.mobileops.presentation.common.focus.BandKind.Bad,
                facts.blockingCause ?: "Blocked for put-away",
                "${facts.warehouseId} · ${facts.locationId}",
            )
        },
        scanPill = { com.d365fo.mobileops.presentation.common.focus.ScanPillField(enabled = !state.busy) },
        topBarTrailing = {
            IconButton(onClick = { showCamera = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.PhotoCamera, contentDescription = "Camera scan", tint = com.d365fo.mobileops.presentation.common.theme.FocusColors.ink)
            }
        },
    ) {
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
        if (facts == null) {
            FocusCenter(
                caption = "Location",
                code = "–",
                description = null,
            )
            // The warehouse the location is checked in - editable, as before.
            OutlinedTextField(
                value = state.warehouseId,
                onValueChange = viewModel::onWarehouseChange,
                label = { Text("Warehouse") },
                singleLine = true,
                shape = com.d365fo.mobileops.presentation.common.theme.FocusShapes.button,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).heightIn(min = 60.dp),
            )
        } else {
            // Back climbs the category tree before it leaves the screen.
            androidx.activity.compose.BackHandler(enabled = state.categoryPath.isNotEmpty()) { viewModel.categoryUp() }
            LocationStockList(state, onCategory = viewModel::openCategory, modifier = Modifier.weight(1f))
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
 * The dedicated typing screen for this inquiry - see the identical note on
 * [com.d365fo.mobileops.presentation.modules.stepflow.OnHandEntryScreen] fetching the same
 * ViewModel instance as the screen underneath.
 */
@Composable
fun LocationEntryScreen(
    viewModel: LocationInquiryViewModel,
    onBack: () -> Unit,
) {
    TextEntryScreen(
        title = "Location inquiry",
        hint = "Scan or enter location",
        onBack = onBack,
        onSubmit = { value ->
            viewModel.onLocationEntered(value)
            onBack()
        },
    )
}

/**
 * What is on the scanned location as a category tree (user, 13 Nov: like Items by category) - the
 * categories holding its items with how many, then the items of the open category with quantities.
 */
@Composable
private fun LocationStockList(state: LocationInquiryState, onCategory: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = com.d365fo.mobileops.presentation.common.theme.FocusColors
    val type = com.d365fo.mobileops.presentation.common.theme.FocusType
    val (categories, items) = remember(state.stock, state.categoryPath) { locationLevel(state.stock, state.categoryPath) }
    androidx.compose.foundation.lazy.LazyColumn(modifier = modifier.fillMaxWidth()) {
        item(key = "head") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(state.result?.locationId.orEmpty(), style = type.rowCode, color = colors.ink)
                val count = state.stock.size
                Text(
                    when {
                        state.stockLoading -> "Asking D365FO what is here"
                        state.stockError != null -> "Couldn't list the items: ${state.stockError}"
                        count == 0 -> "Nothing on this location"
                        count == 1 -> "1 item"
                        else -> "$count items"
                    },
                    style = type.rowDetail,
                    color = if (state.stockError != null) colors.badText else colors.muted,
                )
                if (state.categoryPath.isNotEmpty()) {
                    Text(
                        if (state.categoryPath == NO_CATEGORY) "No category" else displayCategoryPath(state.categoryPath),
                        style = type.bandDetail,
                        color = colors.navy,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        if (categories.isNotEmpty() && items.isNotEmpty()) {
            item(key = "cap-cat") {
                com.d365fo.mobileops.presentation.common.focus.FocusCaption("Categories", Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
            }
        }
        categories.forEach { node ->
            item(key = "c:" + node.path) {
                com.d365fo.mobileops.presentation.common.focus.FocusListRow(
                    title = node.name,
                    count = node.itemCount.toString(),
                    chevron = true,
                    onClick = { onCategory(node.path) },
                )
            }
        }
        if (categories.isNotEmpty() && items.isNotEmpty()) {
            item(key = "cap-items") {
                com.d365fo.mobileops.presentation.common.focus.FocusCaption("Items", Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
            }
        }
        items.forEach { row ->
            item(key = "i:" + row.itemId) {
                com.d365fo.mobileops.presentation.common.focus.FocusListRow(
                    title = row.itemName.ifBlank { row.itemId },
                    subtitle = listOfNotNull(
                        row.itemId.takeIf { row.itemName.isNotBlank() },
                        row.reserved.takeIf { it != 0.0 }?.let { "${plainQty(it)} reserved" },
                    ).joinToString(" · ").ifEmpty { null },
                    count = "${plainQty(row.onHand)} ${row.unit}".trim(),
                )
            }
        }
    }
}

private fun plainQty(value: Double): String =
    java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
