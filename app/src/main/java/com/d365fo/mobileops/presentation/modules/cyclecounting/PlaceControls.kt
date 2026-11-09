package com.d365fo.mobileops.presentation.modules.cyclecounting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton
import com.d365fo.mobileops.presentation.common.components.SquareButton
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * Where counting moves between places, the same in every count type (user, 8 Nov): one "Change
 * location / bin" button opens [ChangePlaceDialog]; the bin and item steps each have a Back button
 * one step up ([StepBackButton]).
 */
fun changePlaceLabel(binsUsed: Boolean, canChangeLocation: Boolean): String = when {
    binsUsed && canChangeLocation -> "Change location / bin"
    binsUsed -> "Change bin"
    else -> "Change location"
}

/** Shows where counting is now - the location and the bin - and lets the counter change either. */
@Composable
fun ChangePlaceDialog(
    location: String?,
    bin: String?,
    binsUsed: Boolean,
    canChangeLocation: Boolean,
    onChangeLocation: () -> Unit,
    onChangeBin: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Counting here") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PlaceRow("Location", location?.takeIf { it.isNotBlank() } ?: "-")
                if (binsUsed) PlaceRow("Bin", bin?.takeIf { it.isNotBlank() } ?: "-")
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (binsUsed && !bin.isNullOrBlank()) {
                    SquareButton(
                        onClick = onChangeBin,
                        modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) { Text("Change bin") }
                }
                if (canChangeLocation) {
                    SquareOutlinedButton(
                        onClick = onChangeLocation,
                        modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) { Text("Change location") }
                }
                CancelOutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
                ) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun PlaceRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = WarehouseStatusColors.text3, modifier = Modifier.weight(0.4f))
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = WarehouseStatusColors.accent,
            modifier = Modifier.weight(0.6f),
        )
    }
}

/** "Back to location" on the bin step, "Back to bin" on the item step. */
@Composable
fun StepBackButton(label: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    SquareOutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
