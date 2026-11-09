package com.d365fo.mobileops.presentation.modules.ordercreate

import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.d365fo.mobileops.presentation.common.components.SquareTextButton as TextButton
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.data.local.entity.CreatedOrderEntity
import com.d365fo.mobileops.data.local.entity.CreatedOrderStatus
import com.d365fo.mobileops.data.repository.DraftLine
import com.d365fo.mobileops.data.repository.OrderItem
import com.d365fo.mobileops.data.repository.OrderVariant
import com.d365fo.mobileops.data.repository.StockRow
import com.d365fo.mobileops.domain.order.QuantityRules
import com.d365fo.mobileops.domain.order.amountText
import com.d365fo.mobileops.domain.order.checkQuantity
import com.d365fo.mobileops.domain.order.qtyText
import com.d365fo.mobileops.presentation.common.components.AppCard
import com.d365fo.mobileops.presentation.common.components.NumericKeypad
import com.d365fo.mobileops.presentation.common.components.SquareButton
import com.d365fo.mobileops.presentation.common.components.StatusChip
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.util.Formatters

/** One line of an order being built - a Harbour row. [showPrice]: purchase orders only. */
@Composable
fun OrderLineCard(line: DraftLine, showPrice: Boolean, currency: String, onClick: () -> Unit) {
    val dims = listOf(
        line.variant.label,
        line.locationId.takeIf { it.isNotBlank() }?.let { "Location $it" },
        line.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
        line.serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" },
    ).filter { !it.isNullOrBlank() }.joinToString(" · ")
    com.d365fo.mobileops.presentation.common.components.ListCard {
        com.d365fo.mobileops.presentation.common.components.LineRow(
            title = line.itemId,
            description = line.itemName,
            lines = listOfNotNull(
                dims.takeIf { it.isNotBlank() },
                line.available?.let { "${qtyText(it)} available " + if (line.locationId.isNotBlank()) "at the location" else "at From" },
            ),
            lead = {
                com.d365fo.mobileops.presentation.common.components.LeadDot(
                    com.d365fo.mobileops.presentation.common.components.Lead.Done,
                    icon = androidx.compose.material.icons.Icons.Filled.Check,
                )
            },
            trailing = {
                com.d365fo.mobileops.presentation.common.components.QtyTrail(qtyText(line.qty), line.unitId)
            },
            onClick = onClick,
            below = {
                // Allowed for an item that may go negative - but said, not left for the reader to
                // compare two numbers (50 ordered with 3 there showed only "3 available at From").
                line.available?.let {
                    if (line.qty > it) {
                        Text("More than on hand", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (showPrice) {
                    if (line.price != null) {
                        Text(
                            buildString {
                                append("${amountText(line.price)} $currency".trim())
                                line.discPct?.takeIf { it > 0 }?.let { append(" · ${qtyText(it)}% off") }
                                line.lineAmount?.let { append(" · line ${amountText(it)}") }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            if (line.priceOverride) "Price entered here" else line.priceSource ?: "D365FO price",
                            style = MaterialTheme.typography.bodySmall,
                            color = WarehouseStatusColors.text3,
                        )
                    } else {
                        Text("Priced by D365FO when the order is sent", style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.amber)
                    }
                }
            },
        )
    }
}

/** An order made on this device and where it stands in D365FO - a Harbour row with its status tag. */
@Composable
fun CreatedOrderCard(
    order: CreatedOrderEntity,
    onClick: (() -> Unit)? = null,
    /** A follow-up for a created order, e.g. "Ship it" - shown only once D365FO has created it. */
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    /** What D365FO says happened since ("Shipped"), shown instead of "Created" - see ToCreateScreen. */
    laterStatus: String? = null,
) {
    // Made in D365FO, but a later step did not go through - a resend finishes it.
    val unfinished = when {
        order.status != CreatedOrderStatus.CREATED -> null
        order.message?.startsWith("Confirmed; packing slip not posted") == true -> "Not posted"
        order.message?.startsWith("Created; not confirmed") == true -> "Not confirmed"
        else -> null
    }
    val online = com.d365fo.mobileops.presentation.common.components.LocalSyncStatus.current?.fullyOnline != false
    val (tag, kind) = when {
        unfinished != null -> unfinished to com.d365fo.mobileops.presentation.common.components.Lead.Warn
        order.status == CreatedOrderStatus.CREATED -> when {
            order.message?.startsWith("Posted") == true -> "Posted"
            order.kind == com.d365fo.mobileops.data.local.entity.OrderKind.CONFIRMATION ||
                order.message?.startsWith("Confirmation ") == true -> "Confirmed"
            else -> laterStatus ?: "Created"
        } to com.d365fo.mobileops.presentation.common.components.Lead.Done
        order.status == CreatedOrderStatus.WAITING ->
            (if (online) "Sending" else "Waiting") to com.d365fo.mobileops.presentation.common.components.Lead.Warn
        else -> "Refused" to com.d365fo.mobileops.presentation.common.components.Lead.Bad
    }
    val icon = when (kind) {
        com.d365fo.mobileops.presentation.common.components.Lead.Done -> androidx.compose.material.icons.Icons.Filled.Check
        com.d365fo.mobileops.presentation.common.components.Lead.Bad -> androidx.compose.material.icons.Icons.Filled.Warning
        else -> androidx.compose.material.icons.Icons.Filled.Schedule
    }
    com.d365fo.mobileops.presentation.common.components.ListCard {
        com.d365fo.mobileops.presentation.common.components.LineRow(
            title = order.documentNumber ?: "Not created yet",
            status = { com.d365fo.mobileops.presentation.common.components.StatusTag(tag, kind) },
            description = order.title,
            lines = listOf(
                buildString {
                    append("${order.lineCount} " + if (order.lineCount == 1) "line" else "lines")
                    order.total?.let { append(" · ${amountText(it)} ${order.currencyCode.orEmpty()}".trimEnd()) }
                    append(" · ${Formatters.clockTime(order.createdAt)}")
                },
            ),
            lead = { com.d365fo.mobileops.presentation.common.components.LeadDot(kind, icon = icon) },
            onClick = onClick,
            below = {
                when (order.status) {
                    // Online it is already on its way - creating and confirming takes D365FO up to
                    // a minute or two - so "as soon as it can be reached" read as if it were offline.
                    CreatedOrderStatus.WAITING -> Text(
                        if (online) "Sent - takes a minute or two." else "Saved - sent when online.",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarehouseStatusColors.text3,
                    )
                    CreatedOrderStatus.FAILED -> Text(
                        order.message ?: "D365FO refused the order.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WarehouseStatusColors.red,
                    )
                    CreatedOrderStatus.CREATED -> order.message?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (unfinished != null) WarehouseStatusColors.amber else WarehouseStatusColors.text3,
                        )
                    }
                }
                if (order.status == CreatedOrderStatus.CREATED && actionLabel != null && onAction != null) {
                    com.d365fo.mobileops.presentation.common.components.SquareTextButton(onClick = onAction) { Text(actionLabel) }
                }
            },
        )
    }
}

/** Typing an item number or barcode - opened by a tap, so the keyboard is wanted here. */
@Composable
fun CodeEntryDialog(title: String, label: String, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) onSubmit(value.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
                confirm = { com.d365fo.mobileops.presentation.common.components.ConfirmButton(onClick = { if (value.isNotBlank()) onSubmit(value.trim()) }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("OK") } },
            )
        },
    )
}

/** Choosing the size / colour / style of an item - only its released variants. */
@Composable
fun VariantPickerDialog(item: OrderItem, onPick: (OrderVariant) -> Unit, onDismiss: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    val shown = remember(filter, item) {
        val tokens = filter.trim().split(' ').filter { it.isNotBlank() }
        // Matched against the values only, from their start: "L" is size L - not the "l" in
        // "Colour" or "Style" now that the label names each one (it matched every variant).
        if (tokens.isEmpty()) item.variants else item.variants.filter { v ->
            // And the words of the variant's name, so it can be found by what it is called.
            val values = (listOf(v.configId, v.sizeId, v.colorId, v.styleId) +
                item.variantNames[v].orEmpty().split(' ')).filter { it.isNotBlank() }
            tokens.all { token -> values.any { it.startsWith(token, ignoreCase = true) } }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        // Which dimension is being chosen, in words (feedback: "need to know what variant am
        // choosing like is it color, size, config or style").
        title = {
            val dims = listOfNotNull(
                "configuration".takeIf { item.configActive },
                "size".takeIf { item.sizeActive },
                "colour".takeIf { item.colorActive },
                "style".takeIf { item.styleActive },
            )
            val what = when (dims.size) {
                0 -> "variant"
                1 -> dims[0]
                else -> dims.dropLast(1).joinToString(", ") + " and " + dims.last()
            }
            // No item number in the title (feedback) - the item is named just under it.
            Text("Choose the $what")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // No item line under the title (feedback: "dont display dm001") - the worker has
                // just scanned it.
                if (item.variants.size > 6) TapToTypeField(filter, { filter = it }, "Filter - e.g. Red M")
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shown, key = { it.label }) { variant ->
                        AppCard(onClick = { onPick(variant) }, modifier = Modifier.fillMaxWidth()) {
                            // The variant's name first, its dimensions under it (feedback: no variant name).
                            val name = item.variantNames[variant]
                            // Bare values ("C1", "L · Black"): the title already says which dimension
                            // (feedback: "Configuration:" is not needed in the values).
                            val values = listOf(variant.configId, variant.sizeId, variant.colorId, variant.styleId)
                                .filter { it.isNotBlank() }.joinToString(" · ")
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(name ?: values.ifBlank { "(no variant)" }, style = MaterialTheme.typography.titleMedium)
                                if (name != null && values.isNotBlank()) {
                                    Text(values, style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.text3)
                                }
                            }
                        }
                    }
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

/** Choosing which batch or serial to transfer, from what is available at From. */
@Composable
fun StockPickerDialog(
    item: OrderItem,
    rows: List<StockRow>,
    onPick: (StockRow?) -> Unit,
    onDismiss: () -> Unit,
    caption: String = "Available at the From warehouse.",
    /** "Any batch" - leave the choice to the shipment; not for a return, which names its stock. */
    allowAny: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // No item number in the title, no information line (feedback) - as the variant picker.
        title = { Text("Choose the stock") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(rows, key = { it.locationId + "|" + it.batchId + "|" + it.serialId }) { row ->
                        AppCard(onClick = { onPick(row) }, modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(14.dp).fillMaxWidth()) {
                                Text(
                                    row.label.ifBlank { "Any" },
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(qtyText(row.available), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                if (allowAny && !item.serialActive) {
                    TextButton(onClick = { onPick(null) }) { Text("Any batch - the shipment picks it") }
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

/** A quantity (or price) on the app's own keypad - no system keyboard. */
@Composable
fun NumberDialog(
    title: String,
    subtitle: String?,
    initial: Double?,
    rules: QuantityRules?,
    confirmLabel: String,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)? = null,
    extraLabel: String? = null,
    onExtra: (() -> Unit)? = null,
) {
    // The shared picker (QuantityPicker) - the same on every screen.
    com.d365fo.mobileops.presentation.common.components.QuantityPicker(
        subtitle = subtitle,
        hint = rules?.hint?.takeIf { it.isNotBlank() },
        initial = initial?.let { qtyText(it) }.orEmpty(),
        confirmLabel = confirmLabel,
        validate = { text ->
            val value = text.toDoubleOrNull()
            when {
                value == null -> "\"$text\" is not a number"
                rules != null -> checkQuantity(value, rules)
                value <= 0 -> "Enter an amount above zero"
                else -> null
            }
        },
        onConfirm = { text -> text.toDoubleOrNull()?.let(onConfirm) },
        onDismiss = onDismiss,
        extras = if (extraLabel != null && onExtra != null) ({
            SquareTextButtonCompat(extraLabel, onExtra)
        }) else null,
        removeLabel = "Remove this line".takeIf { onRemove != null },
        onRemove = onRemove,
    )
}

@Composable
private fun SquareTextButtonCompat(label: String, onClick: () -> Unit) {
    com.d365fo.mobileops.presentation.common.components.SquareTextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

/**
 * A text field that stays read-only until tapped - this device's keyboard opens itself on any
 * focus change, so a field a dialog focuses on opening would cover the list under it.
 */
@Composable
fun TapToTypeField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    var typing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    LaunchedEffect(interaction) {
        interaction.interactions.collect {
            if (it is PressInteraction.Release && !typing) {
                typing = true
                keyboard?.show()
            }
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        readOnly = !typing,
        interactionSource = interaction,
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(
                com.d365fo.mobileops.presentation.common.components.LineIcons.SearchThin,
                contentDescription = null,
                tint = com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors.text3,
            )
        },
        shape = com.d365fo.mobileops.presentation.common.components.HeroFieldShape,
        colors = com.d365fo.mobileops.presentation.common.components.heroFieldColors(),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The scan target inside the red prompt card: the same white field as Receiving (a scan never
 * lands in it - the tap opens typing), and a camera button under it.
 */
@Composable
fun OrderScanField(onType: () -> Unit, onCamera: () -> Unit, enabled: Boolean = true) {
    Box {
        androidx.compose.material3.OutlinedTextField(
            value = "",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = enabled,
            placeholder = { Text("Scan, or tap to type", style = MaterialTheme.typography.titleMedium) },
            shape = com.d365fo.mobileops.presentation.common.components.HeroFieldShape,
            colors = com.d365fo.mobileops.presentation.common.components.heroFieldColors(),
            leadingIcon = { com.d365fo.mobileops.presentation.common.components.BarcodeFieldIcon() },
            trailingIcon = { com.d365fo.mobileops.presentation.common.components.HeroEnterBadge() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        )
        // A read-only field swallows taps itself - catch them on top (see ReceivingFlowScreen).
        Box(modifier = Modifier.matchParentSize().clickable(enabled = enabled, onClick = onType))
    }
    com.d365fo.mobileops.presentation.common.components.HeroCameraButton(onClick = onCamera, enabled = enabled)
}

fun linesText(count: Int): String = if (count == 1) "1 line" else "$count lines"
