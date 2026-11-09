package com.d365fo.mobileops.presentation.common.components

import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Shown before a connection change would wipe work that hasn't reached D365FO. Keeping it is
 * the default; deleting it takes a deliberate second tap.
 */
@Composable
fun UnsentWorkDialog(description: String, onKeep: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("Work not yet in D365FO") },
        text = {
            Text(
                "$description is still unsent and will be lost. Send it first.",
            )
        },
        confirmButton = {
            com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = onKeep, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Keep my work") } },
                confirm = { com.d365fo.mobileops.presentation.common.components.CancelButton(onClick = onDelete, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Delete it and change") } },
            )
        },
    )
}
