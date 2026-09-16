package com.d365fo.mobileops.presentation.modules.dataenquiry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoCamera
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton as OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationInquiryScreen(
    onBack: () -> Unit,
    onManualEntryRequested: () -> Unit,
    viewModel: LocationInquiryViewModel = hiltViewModel(),
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
                title = { Text("Location inquiry") },
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
            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            OutlinedTextField(
                value = state.warehouseId,
                onValueChange = viewModel::onWarehouseChange,
                label = { Text("Warehouse") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            )

            // Read-only tap target - overlaid Box, not clickable() on the field itself. See the
            // identical note on OnHandInquiryScreen: a read-only field's own pointer input
            // swallows a tap before an outer clickable on the same chain ever sees it.
            Box {
                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Scan or enter location") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge,
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showCamera = true },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = "Scan with camera")
                }
            }

            state.error?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            state.result?.let { facts ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "${facts.warehouseId} · ${facts.locationId}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (facts.canReceiveInto) {
                                    Icons.Default.CheckCircle
                                } else {
                                    Icons.Default.Block
                                },
                                contentDescription = null,
                                tint = if (facts.canReceiveInto) {
                                    WarehouseStatusColors.green
                                } else {
                                    WarehouseStatusColors.red
                                },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(
                                text = if (facts.canReceiveInto) {
                                    "Open for put-away"
                                } else {
                                    facts.blockingCause ?: "Blocked for put-away"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
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
