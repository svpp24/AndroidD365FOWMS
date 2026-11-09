package com.d365fo.mobileops.presentation.common.components

import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Asked before Start over throws away lines that are scanned but not posted. Keeping them is the
 * default - one stray tap on the close icon must never cost a pallet's worth of scanning.
 */
@Composable
fun DiscardScannedDialog(lines: Int, orderId: String?, onKeep: () -> Unit, onDiscard: () -> Unit) {
    val what = "$lines scanned " + if (lines == 1) "line" else "lines"
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("Discard $what?") },
        text = {
            Text(
                (orderId?.let { "$it: " } ?: "") + "not posted. Keep them to continue later.",
            )
        },
        confirmButton = {
            com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = onKeep, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Keep scanning") } },
                confirm = { com.d365fo.mobileops.presentation.common.components.CancelButton(onClick = onDiscard, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Discard") } },
            )
        },
    )
}
