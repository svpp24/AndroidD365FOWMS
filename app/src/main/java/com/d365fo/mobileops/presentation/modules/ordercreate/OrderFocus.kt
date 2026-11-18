package com.d365fo.mobileops.presentation.modules.ordercreate

import androidx.compose.material.icons.outlined.QrCodeScanner
import com.d365fo.mobileops.presentation.common.focus.FocusTask
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.data.repository.DraftLine
import com.d365fo.mobileops.domain.order.amountText
import androidx.compose.foundation.background
import com.d365fo.mobileops.domain.order.qtyText
import com.d365fo.mobileops.presentation.common.focus.FocusCenter
import com.d365fo.mobileops.presentation.common.focus.FocusScreen
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusType

/**
 * The Focus parts the three create screens share (brief sections 6 and 9): the tools menu on the
 * top bar, the "last added" centre, the order's lines on demand, and a list caption.
 */
internal data class OrderMenuItem(val label: String, val icon: ImageVector, val onClick: () -> Unit, val enabled: Boolean = true)

/** The ⋮ on the top bar: camera, change vendor / route / reason, discard - the tools a flow keeps. */
@Composable
internal fun OrderToolsMenu(items: List<OrderMenuItem>) {
    if (items.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = FocusColors.ink)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    leadingIcon = { Icon(item.icon, contentDescription = null) },
                    enabled = item.enabled,
                    onClick = { open = false; item.onClick() },
                    modifier = Modifier.heightIn(min = 56.dp),
                )
            }
        }
    }
}

/**
 * The line the last scan added to or created - lines are appended, and a second scan of the same
 * item adds to its line, so it is whichever line is new or whose quantity changed since.
 */
@Composable
internal fun rememberLastAdded(lines: List<DraftLine>): DraftLine? {
    val seen = remember { object { var prev: List<DraftLine>? = null; var lastId: Any? = null } }
    val prev = seen.prev
    if (prev != null && lines != prev) {
        lines.firstOrNull { l -> prev.none { it.id == l.id && it.qty == l.qty } }?.let { seen.lastId = it.id }
    }
    seen.prev = lines
    return lines.firstOrNull { it.id == seen.lastId } ?: lines.lastOrNull()
}

/** The centre once lines exist: the last line added, its quantity tappable to change it. */
@Composable
internal fun ColumnScope.LastAddedCenter(line: DraftLine, onTap: () -> Unit, size: String? = null) {
    FocusCenter(
        // "Scan an item", as every other flow (user, 18 Nov 2026).
        task = FocusTask("Scan an item", null, androidx.compose.material.icons.Icons.Outlined.QrCodeScanner, done = true),
        caption = "Last added",
        code = line.itemId,
        description = listOfNotNull(
            line.itemName.takeIf { it.isNotBlank() && !it.equals(line.itemId, ignoreCase = true) },
            line.variant.label.takeIf { it.isNotBlank() },
            line.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
            line.serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" },
        ).joinToString(" · ").ifEmpty { null },
        quantity = qtyText(line.qty),
        quantityColor = if (line.available?.let { line.qty > it } == true) FocusColors.warnIcon else FocusColors.navy,
        unitLine = listOfNotNull(
            line.unitId.takeIf { it.isNotBlank() },
            line.locationId.takeIf { it.isNotBlank() }?.let { "From $it" },
            line.available?.let { "${qtyText(it)} available" },
        ).joinToString(" · ") +
            // How big the order is, on the card - not floating under it (user, 18 Nov 2026).
            (size?.let { "\n$it" } ?: ""),
        onQuantityTap = onTap,
    )
}

/** "3 lines · 12 units" - how big the order is, never what to add. */
internal fun orderSize(lines: List<DraftLine>, extra: String? = null): String? =
    if (lines.isEmpty()) null else listOfNotNull(linesText(lines.size), lines.sumOf { it.qty }.let { if (it == 1.0) "1 unit" else "${qtyText(it)} units" }, extra).joinToString(" · ")

/** The order's lines on their own screen (brief rule 10); tapping one opens its quantity. */
@Composable
internal fun OrderLinesScreen(
    title: String,
    lines: List<DraftLine>,
    showPrice: Boolean,
    currency: String,
    footer: String?,
    onLine: (DraftLine) -> Unit,
    onClose: () -> Unit,
    onRemove: ((DraftLine) -> Unit)? = null,
    /** − / + on each line (user, 17 Nov 2026); − on 1 asks "Remove this line?" first. */
    onChangeQty: ((DraftLine, Double) -> Unit)? = null,
    /** A return cannot send more than the stock there: + stops at it. */
    capAtAvailable: Boolean = false,
) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    var confirming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<DraftLine?>(null) }
    confirming?.let { line ->
        RemoveLineDialog(line, onRemove = { confirming = null; onRemove?.invoke(line) }, onKeep = { confirming = null })
    }
    FocusScreen(title = title, onBack = onClose, counter = lines.size.toString(), remaining = footer) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            item(key = "task") {
                com.d365fo.mobileops.presentation.common.focus.ListTaskBox("Check the lines", com.d365fo.mobileops.presentation.common.focus.TaskIcons.Lines)
            }
            items(lines, key = { it.id }) { line ->
                if (onChangeQty == null) {
                    OrderLineCard(
                        line,
                        showPrice = showPrice,
                        currency = currency,
                        onRemove = onRemove?.let { { confirming = line } },
                    ) { onLine(line) }
                } else {
                    val over = line.available?.let { line.qty > it } == true
                    com.d365fo.mobileops.presentation.common.focus.FocusLineCard(
                        code = line.itemId,
                        name = line.itemName,
                        details = listOfNotNull(
                            line.variant.label.takeIf { it.isNotBlank() },
                            listOfNotNull(
                                line.locationId.takeIf { it.isNotBlank() },
                                line.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                                line.serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" },
                                line.available?.let { "${qtyText(it)} available" },
                            ).joinToString(" · ").takeIf { it.isNotBlank() },
                            "More than on hand".takeIf { over },
                            orderLinePrice(line, showPrice, currency),
                        ),
                        quantity = qtyText(line.qty),
                        unit = line.unitId,
                        canMinus = true,
                        canPlus = line.serialId.isBlank() && !(capAtAvailable && line.available != null && line.qty + 1 > line.available),
                        onMinus = { if (line.qty - 1 <= 0.0) confirming = line else onChangeQty(line, line.qty - 1) },
                        onPlus = { onChangeQty(line, line.qty + 1) },
                        onQuantityTap = { onLine(line) },
                        onRemove = onRemove?.let { { confirming = line } },
                    )
                }
            }
        }
    }
}

/** "2.49 USD · line 29.88 · Trade agreement" - a PO line's price, or null. */
internal fun orderLinePrice(line: DraftLine, showPrice: Boolean, currency: String): String? = when {
    !showPrice -> null
    line.price != null -> buildString {
        append("${amountText(line.price)} $currency".trim())
        line.discPct?.takeIf { it > 0 }?.let { append(" · ${qtyText(it)}% off") }
        line.lineAmount?.let { append(" · line ${amountText(it)}") }
        append(" · " + if (line.priceOverride) "Price entered here" else line.priceSource ?: "D365FO price")
    }
    else -> "Priced by D365FO when the order is sent"
}

/**
 * The task box once an order is saved (user, 17 Nov 2026) - instead of the green "saved" bar:
 * created (green), created but not confirmed (amber), waiting to send, or not created.
 */
internal fun savedTask(order: com.d365fo.mobileops.data.local.entity.CreatedOrderEntity, noun: String): com.d365fo.mobileops.presentation.common.focus.FocusTask {
    val i = androidx.compose.material.icons.Icons.Outlined
    val ok = com.d365fo.mobileops.presentation.common.focus.TaskIcons.Done
    return when {
        order.status == com.d365fo.mobileops.data.local.entity.CreatedOrderStatus.CREATED &&
            order.message?.startsWith("Created; not confirmed") == true ->
            com.d365fo.mobileops.presentation.common.focus.FocusTask("Created, not confirmed", icon = com.d365fo.mobileops.presentation.common.focus.TaskIcons.Notice, warn = true)
        order.status == com.d365fo.mobileops.data.local.entity.CreatedOrderStatus.CREATED ->
            com.d365fo.mobileops.presentation.common.focus.FocusTask("$noun created", icon = ok, done = true)
        order.status == com.d365fo.mobileops.data.local.entity.CreatedOrderStatus.WAITING ->
            com.d365fo.mobileops.presentation.common.focus.FocusTask("$noun saved - waiting to send", icon = com.d365fo.mobileops.presentation.common.focus.TaskIcons.Notice, warn = true)
        else -> com.d365fo.mobileops.presentation.common.focus.FocusTask("$noun not created", icon = com.d365fo.mobileops.presentation.common.focus.TaskIcons.Notice, warn = true)
    }
}

/**
 * "Choose the stock" as a page, not a pop-up (user, 17 Nov 2026): the task box, the item, then
 * where it is with the batch and how much is there. Back is Cancel.
 */
@Composable
internal fun StockPickerPage(
    title: String,
    item: com.d365fo.mobileops.data.repository.OrderItem,
    rows: List<com.d365fo.mobileops.data.repository.StockRow>,
    onPick: (com.d365fo.mobileops.data.repository.StockRow?) -> Unit,
    onDismiss: () -> Unit,
    allowAny: Boolean = true,
) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    FocusScreen(title = title, onBack = onDismiss) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            item(key = "task") { com.d365fo.mobileops.presentation.common.focus.ListTaskBox("Choose the stock", com.d365fo.mobileops.presentation.common.focus.TaskIcons.Pick) }
            item(key = "item") {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .clip(com.d365fo.mobileops.presentation.common.theme.FocusShapes.band)
                        .background(FocusColors.surface)
                        .border(1.dp, FocusColors.divider, com.d365fo.mobileops.presentation.common.theme.FocusShapes.band)
                        .padding(12.dp),
                ) {
                    Text(item.itemId, style = FocusType.rowCount, color = FocusColors.ink)
                    item.itemName.takeIf { it.isNotBlank() && !it.equals(item.itemId, true) }?.let {
                        Text(it, style = FocusType.rowDetail, color = FocusColors.inkSoft, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
            item(key = "caption") { ListCaption("Where it is", "Available") }
            items(rows, key = { it.locationId + "|" + it.batchId + "|" + it.serialId }) { row ->
                com.d365fo.mobileops.presentation.common.focus.FocusListRow(
                    title = row.locationId.ifBlank { "Any location" },
                    subtitle = listOfNotNull(
                        row.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                        row.serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" },
                        row.expiry?.let { "Expires " + it.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)) },
                    ).joinToString(" · ").ifBlank { null },
                    count = qtyText(row.available),
                    countColor = FocusColors.ink,
                    chevron = true,
                    onClick = { onPick(row) },
                )
            }
            if (allowAny && !item.serialActive) {
                item(key = "any") {
                    com.d365fo.mobileops.presentation.common.focus.FocusListRow(
                        title = "Any batch",
                        subtitle = "The shipment picks it",
                        chevron = true,
                        onClick = { onPick(null) },
                    )
                }
            }
        }
    }
}


/** How long a "saved" band stays on the Create screens (user, 15 Nov 2026). */
internal const val SAVED_BAND_MS = 4_000L

/** An uppercase caption over a list on a start step ("YOUR ORDERS", "VENDORS"). */
@Composable
internal fun ListCaption(text: String, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp),
    ) {
        Text(text.uppercase(), style = FocusType.caption, color = FocusColors.caption, modifier = Modifier.weight(1f))
        if (trailing != null) {
            if (onTrailing != null) {
                com.d365fo.mobileops.presentation.common.components.SquareTextButton(
                    onClick = onTrailing,
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text(trailing) }
            } else {
                Text(trailing, style = FocusType.rowDetail, color = FocusColors.muted, modifier = Modifier.padding(end = 12.dp))
            }
        }
    }
}

/** The busy line under the top bar while D365FO is asked something. */
@Composable
internal fun BusyLine(busy: Boolean) {
    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
}

/** Create samples for the layout test: 0 PO adding, 1 PO lines with prices, 2 saved orders, 3 return missing a reason. */
@Composable
internal fun OrderCreateFocusSample(kind: Int) {
    val line = DraftLine(
        id = "1", itemId = "BSR-11397-0", itemName = "Panadol Extra 24s tablets, film-coated", qty = 12.0, unitId = "Pack",
        price = 18.5, discPct = 5.0, lineAmount = 210.9, priceSource = "Trade agreement",
    )
    val line2 = DraftLine(id = "2", itemId = "HST001", itemName = "HS test tracked item", qty = 1.0, unitId = "Each", batchId = "HSB-T1-001", serialId = "SN-T1-0001", locationId = "BIN-A1", available = 1.0)
    when (kind) {
        1 -> OrderLinesScreen("PO lines", listOf(line, line2), showPrice = true, currency = "AED", footer = "Total 210.90 AED", onLine = {}, onClose = {})
        2 -> FocusScreen(title = "Create PO", onBack = {}, band = com.d365fo.mobileops.presentation.common.focus.BandState(com.d365fo.mobileops.presentation.common.focus.BandKind.Ok, "Order saved", "BSP-PO-000161800")) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                item { ListCaption("Your orders", "Newest") }
                items(
                    listOf(
                        com.d365fo.mobileops.data.local.entity.CreatedOrderEntity(
                            id = "a", kind = com.d365fo.mobileops.data.local.entity.OrderKind.PURCHASE,
                            status = com.d365fo.mobileops.data.local.entity.CreatedOrderStatus.CREATED, documentNumber = "BSP-PO-000161800",
                            title = "360 IOMC FZE", lineCount = 2, total = 210.9, currencyCode = "AED",
                            message = "Created; not confirmed - needs approval", userName = "002", createdAt = 0L, updatedAt = 0L,
                        ),
                        com.d365fo.mobileops.data.local.entity.CreatedOrderEntity(
                            id = "b", kind = com.d365fo.mobileops.data.local.entity.OrderKind.PURCHASE,
                            status = com.d365fo.mobileops.data.local.entity.CreatedOrderStatus.FAILED,
                            title = "Gulf Pharma Distributors LLC", lineCount = 1, message = "Vendor is on hold for all transactions.",
                            userName = "002", createdAt = 0L, updatedAt = 0L,
                        ),
                    ),
                    key = { it.id },
                ) { CreatedOrderCard(it, actionLabel = "Confirm it now".takeIf { _ -> it.id == "a" }, onAction = {}) }
            }
        }
        else -> FocusScreen(
            title = if (kind == 3) "Return to vendor" else "Create PO",
            onBack = {},
            counter = "2 lines",
            band = if (kind == 3) com.d365fo.mobileops.presentation.common.focus.BandState(com.d365fo.mobileops.presentation.common.focus.BandKind.Warn, "No return reason", "nothing posted")
            else com.d365fo.mobileops.presentation.common.focus.BandState(com.d365fo.mobileops.presentation.common.focus.BandKind.Ok, "Added", "BSR-11397-0 · 12 Pack"),
            remaining = orderSize(listOf(line, line2), "Total 210.90 AED"),
            scanPill = { com.d365fo.mobileops.presentation.common.focus.ScanPill(onTap = {}) },
            actions = {
                com.d365fo.mobileops.presentation.common.focus.FocusButton("Lines", onClick = {}, modifier = Modifier.weight(1f))
                com.d365fo.mobileops.presentation.common.focus.HoldToConfirmButton(
                    if (kind == 3) "Hold to post return" else "Hold to create", onConfirm = {}, modifier = Modifier.weight(1f),
                )
            },
        ) {
            LastAddedCenter(if (kind == 3) line2 else line, onTap = {})
        }
    }
}
