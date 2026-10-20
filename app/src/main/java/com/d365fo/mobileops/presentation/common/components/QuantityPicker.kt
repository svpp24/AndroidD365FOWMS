package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * The app's one quantity picker (feedback, 10 Oct: "quantity picker should be same across app").
 * Receive and Ship, PO / TO creation, Purchase return and Counting all use it: "Quantity" as the
 * title, what is being counted under it, the number, the keypad, then "all outstanding" and the
 * unit when they apply, and the action and Cancel side by side.
 */
@Composable
fun QuantityPicker(
    /** What the quantity is for - "DM001 · Configuration: C2 · Pieces". */
    subtitle: String?,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    /** A second line - "4 available", "1 Piece still to ship". */
    hint: String? = null,
    initial: String = "",
    /** The first key replaces [initial] instead of adding to it (an existing count). */
    replaceInitial: Boolean = false,
    allowDecimal: Boolean = true,
    /** Why the entry cannot be confirmed, or null when it can. Blank entry is never confirmable. */
    validate: (String) -> String? = { null },
    quickFillLabel: String? = null,
    quickFillValue: String? = null,
    /** Under the keypad - the unit choice. */
    extras: (@Composable () -> Unit)? = null,
    removeLabel: String? = null,
    onRemove: (() -> Unit)? = null,
    /** A refusal from D365FO for the last confirm, shown in red until the entry changes. */
    error: String? = null,
    dismissLabel: String = "Cancel",
) {
    var entry by remember { mutableStateOf(initial) }
    var replaceNext by remember { mutableStateOf(replaceInitial && initial.isNotEmpty()) }
    fun key(edit: (String) -> String) {
        val base = if (replaceNext) "" else entry
        replaceNext = false
        entry = edit(base).take(12)
    }
    val problem = entry.takeIf { it.isNotBlank() }?.let(validate) ?: error
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quantity") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                hint?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.text3)
                }
                Text(
                    entry.ifBlank { "0" },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (entry.isBlank()) WarehouseStatusColors.text3 else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                problem?.let { Text(it, color = WarehouseStatusColors.red, style = MaterialTheme.typography.bodyMedium) }
                NumericKeypad(
                    enabled = true,
                    onDigit = { d -> key { it + d } },
                    onDecimal = { if (allowDecimal) key { if (it.contains('.')) it else it.ifBlank { "0" } + "." } },
                    onBackspace = { key { it.dropLast(1) } },
                )
                if (quickFillLabel != null && quickFillValue != null) {
                    SquareOutlinedButton(
                        onClick = { onConfirm(quickFillValue) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) { Text(quickFillLabel) }
                }
                extras?.invoke()
                if (removeLabel != null && onRemove != null) {
                    SquareTextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                        Text(removeLabel, color = WarehouseStatusColors.red)
                    }
                }
            }
        },
        confirmButton = {
            DialogButtonRow(
                dismiss = {
                    CancelOutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text(dismissLabel) }
                },
                confirm = {
                    ConfirmButton(
                        onClick = { onConfirm(entry.trim()) },
                        enabled = entry.isNotBlank() && entry.trim().trimEnd('.').isNotEmpty() && (problem == null || problem == error),
                        modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) { Text(confirmLabel) }
                },
            )
        },
    )
}
