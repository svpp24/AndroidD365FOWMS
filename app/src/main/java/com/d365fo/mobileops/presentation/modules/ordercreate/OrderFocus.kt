package com.d365fo.mobileops.presentation.modules.ordercreate

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
internal fun ColumnScope.LastAddedCenter(line: DraftLine, onTap: () -> Unit) {
    FocusCenter(
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
        ).joinToString(" · "),
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
) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    FocusScreen(title = title, onBack = onClose, counter = lines.size.toString(), remaining = footer) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(lines, key = { it.id }) { line ->
                OrderLineCard(line, showPrice = showPrice, currency = currency) { onLine(line) }
            }
        }
    }
}

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
