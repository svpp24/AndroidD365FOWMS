package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
    /** What to do, as the dialog's title (user, 17 Nov 2026: "Enter quantity" on every pad). */
    title: String = "Enter quantity",
) {
    var entry by remember { mutableStateOf(initial) }
    var replaceNext by remember { mutableStateOf(replaceInitial && initial.isNotEmpty()) }
    fun key(edit: (String) -> String) {
        val base = if (replaceNext) "" else entry
        replaceNext = false
        entry = edit(base).take(12)
    }
    val problem = entry.takeIf { it.isNotBlank() }?.let(validate) ?: error
    // Harbour: the item code in the accent as the title, what is left as a pill, the number in
    // an accent box on the right, grey keys, and the action as the gradient button.
    val parts = subtitle?.takeIf { it.isNotBlank() }?.split(" · ").orEmpty()
    // With a hardware keyboard or wedge the dialog opens in key mode, where Compose gives focus to
    // its first control - the "1" key lit up, and a stray Enter would have typed a 1. Focus is
    // parked on the number box instead: it shows nothing and does nothing (23 Nov 2026).
    val park = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = androidx.compose.ui.graphics.Color.White,
        title = {
            Text(
                title,
                fontWeight = FontWeight.ExtraBold,
                color = WarehouseStatusColors.accent,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                // Inside the dialog's own content, so the number box is there to take the focus.
                LaunchedEffect(Unit) { runCatching { park.requestFocus() } }
                // The item code, then what else the quantity is for (name, batch, unit) under it.
                parts.firstOrNull()?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = WarehouseStatusColors.text1)
                }
                parts.drop(1).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = WarehouseStatusColors.text2)
                }
                hint?.takeIf { it.isNotBlank() }?.let { ContextPill(null, it) }
                Text(
                    entry.ifBlank { "0" },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (entry.isBlank()) WarehouseStatusColors.text3 else WarehouseStatusColors.accent,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(park)
                        .focusable()
                        .border(2.dp, WarehouseStatusColors.accent, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
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
                    // A red outline button, not plain red text (user, 16 Nov 2026).
                    CancelOutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget)) {
                        Text(removeLabel)
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
                    GradientButton(
                        onClick = { onConfirm(entry.trim()) },
                        enabled = entry.isNotBlank() && entry.trim().trimEnd('.').isNotEmpty() && (problem == null || problem == error),
                        modifier = Modifier.weight(1f),
                    ) { Text(confirmLabel) }
                },
            )
        },
    )
}
