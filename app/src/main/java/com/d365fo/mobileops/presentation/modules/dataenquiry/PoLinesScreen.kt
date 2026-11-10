package com.d365fo.mobileops.presentation.modules.dataenquiry

import com.d365fo.mobileops.presentation.common.components.BarcodeFieldIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.focus.FocusCenter
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.domain.model.PurchaseOrderLine
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.components.HeroEnterBadge
import com.d365fo.mobileops.presentation.common.components.HeroFieldShape
import com.d365fo.mobileops.presentation.common.components.heroFieldColors
import com.d365fo.mobileops.presentation.common.components.TextEntryScreen
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.util.Formatters

/**
 * Read-only: scan or type a PO number, see its lines. No confirm, no capture - see
 * [PoLinesViewModel] for why this is safe to build as a thin wrapper over the same lookup
 * Receiving already uses.
 */
@Composable
fun PoLinesScreen(
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onManualEntryRequested: () -> Unit,
    viewModel: PoLinesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    val purchId = state.purchId

    // Focus design: the order in the top bar, what the lookup found in the band, the open lines
    // as rows; scan or type the next PO (or an item of it) in the pill.
    com.d365fo.mobileops.presentation.common.focus.FocusScreen(
        title = "PO lines",
        onBack = onBack,
        // The PO number is in the band: as the counter it squeezed the title onto two lines.
        band = when {
            state.error != null -> com.d365fo.mobileops.presentation.modules.stepflow.bandOf(
                com.d365fo.mobileops.presentation.common.focus.BandKind.Bad, state.error.orEmpty(),
            )
            purchId == null -> null
            state.blockNotice != null -> com.d365fo.mobileops.presentation.modules.stepflow.bandOf(
                com.d365fo.mobileops.presentation.common.focus.BandKind.Warn, state.blockNotice.orEmpty(),
            )
            !state.linesInline && state.outstandingLineCount > 0 -> com.d365fo.mobileops.presentation.common.focus.BandState(
                com.d365fo.mobileops.presentation.common.focus.BandKind.Info,
                "${state.outstandingLineCount} open lines",
                state.itemFilter?.let { "Lines for $it" },
            )
            state.lines.isEmpty() && !state.busy -> com.d365fo.mobileops.presentation.common.focus.BandState(
                // "Open": a received or cancelled order (NOT_OPEN) has lines, just none left.
                com.d365fo.mobileops.presentation.common.focus.BandKind.Info, "No open lines", purchId,
            )
            else -> com.d365fo.mobileops.presentation.common.focus.BandState(
                com.d365fo.mobileops.presentation.common.focus.BandKind.Info, purchId, state.vendorAccount?.takeIf { it.isNotBlank() }?.let { "Vendor $it" },
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
        if (purchId == null) {
            FocusCenter(caption = "PO lines", code = "–", description = "Purchase order")
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.lines, key = { it.lineId }) { line -> PoLineRow(line) }
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
 * The dedicated typing screen for this inquiry - see the identical note on
 * [com.d365fo.mobileops.presentation.modules.stepflow.OnHandEntryScreen] fetching the same
 * ViewModel instance as the screen underneath.
 */
@Composable
fun PoLinesEntryScreen(
    viewModel: PoLinesViewModel,
    onBack: () -> Unit,
) {
    TextEntryScreen(
        title = "PO lines",
        hint = "Scan or enter PO number",
        onBack = onBack,
        onSubmit = { value ->
            viewModel.lookUp(value)
            onBack()
        },
    )
}

@Composable
private fun PoLineRow(line: PurchaseOrderLine) {
    com.d365fo.mobileops.presentation.common.focus.FocusListRow(
        title = line.itemId,
        titleIsCode = true,
        state = if (line.outstandingQuantity.signum() > 0) com.d365fo.mobileops.presentation.common.focus.RowState.Open else com.d365fo.mobileops.presentation.common.focus.RowState.Done,
        subtitle = listOfNotNull(
            line.itemName.takeIf { it.isNotBlank() },
            "Ordered ${Formatters.qty(line.orderedQuantity)} · received ${Formatters.qty(line.receivedQuantity)}",
            line.locationId?.let { "Location $it" },
        ).joinToString(" · "),
        count = "${Formatters.qty(line.outstandingQuantity)} ${line.purchaseUnit}".trim(),
    )
}
