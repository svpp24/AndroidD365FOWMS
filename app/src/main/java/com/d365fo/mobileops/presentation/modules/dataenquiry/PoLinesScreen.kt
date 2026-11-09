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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.domain.model.PurchaseOrderLine
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.components.HeroEnterBadge
import com.d365fo.mobileops.presentation.common.components.HeroFieldShape
import com.d365fo.mobileops.presentation.common.components.PromptHeroCard
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoLinesScreen(
    onBack: () -> Unit,
    onManualEntryRequested: () -> Unit,
    viewModel: PoLinesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Read-only field now (see below) - no keyboardController fight needed any more.
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PO lines") },
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
                .padding(horizontal = WarehouseDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // A fixed slot, so the bar never moves the scan card below it.
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(4.dp)) { if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }

            // Read-only tap target - overlaid Box, not clickable() on the field itself. See the
            // identical note on OnHandInquiryScreen: a read-only field's own pointer input
            // swallows a tap before an outer clickable on the same chain ever sees it.
            PromptHeroCard(prompt = "Scan or enter PO number") {
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


            state.error?.let { message ->
                ErrorBanner(message = message)
            }

            state.purchId?.let { purchId ->
                Text(
                    text = state.vendorAccount?.takeIf { it.isNotBlank() }
                        ?.let { "$purchId — $it" }
                        ?: purchId,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            state.blockNotice?.takeIf { state.purchId != null }?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            WarehouseStatusColors.amberDim,
                            RoundedCornerShape(WarehouseDimens.CardCorner),
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }

            if (state.purchId != null && !state.linesInline && state.outstandingLineCount > 0) {
                Text(
                    text = when {
                        state.itemFilter != null ->
                            "Lines for ${state.itemFilter}"
                        // Not necessarily a large order: an older AOS withholds a blocked order's
                        // lines whatever its size (see PoLinesState.linesInline).
                        state.blockNotice != null ->
                            "${state.outstandingLineCount} open lines - scan an item"
                        else ->
                            "${state.outstandingLineCount} open lines - scan an item"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (state.lines.isEmpty() && state.purchId != null && !state.busy) {
                Text(
                    // "Open": a received or cancelled order (NOT_OPEN) has lines, just none left.
                    text = "This order has no open lines.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.lines, key = { it.lineId }) { line ->
                    PoLineRow(line)
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = line.itemName.takeIf { it.isNotBlank() }
                    ?.let { "${line.itemId} — $it" }
                    ?: line.itemId,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Ordered ${Formatters.qty(line.orderedQuantity)} " +
                    "· Received ${Formatters.qty(line.receivedQuantity)} " +
                    "· Outstanding ${Formatters.qty(line.outstandingQuantity)} " +
                    line.purchaseUnit,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            line.locationId?.let {
                Text(
                    text = "Location $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarehouseStatusColors.text3,
                )
            }
        }
    }
}
