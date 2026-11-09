package com.d365fo.mobileops.presentation.modules.ordercreate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.data.repository.DraftLine
import com.d365fo.mobileops.data.repository.OrderItem
import com.d365fo.mobileops.data.repository.OrderVariant
import com.d365fo.mobileops.data.repository.StockRow
import com.d365fo.mobileops.domain.order.amountText
import com.d365fo.mobileops.domain.order.qtyText
import com.d365fo.mobileops.presentation.common.components.FlowAction
import com.d365fo.mobileops.presentation.common.components.FlowActionBar
import com.d365fo.mobileops.presentation.common.components.FlowHeader
import com.d365fo.mobileops.presentation.common.components.ListRow
import com.d365fo.mobileops.presentation.common.components.ListRowValue
import com.d365fo.mobileops.presentation.common.components.NextCard
import com.d365fo.mobileops.presentation.common.components.SendTray
import com.d365fo.mobileops.presentation.common.components.StatusBand
import com.d365fo.mobileops.presentation.common.components.StatusKind
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.presentation.common.theme.WarehouseType

/**
 * The redesign's flow layout (brief §4) for the order-creation screens: [FlowHeader] →
 * [StatusBand] → the screen's body (a [NextCard], or a list of [ListRow]s when there is a real
 * choice) → [SendTray] → [FlowActionBar], on the comfort page. [footer] sits between the body
 * and the tray - Return's Hold to post, Transfer's Start.
 */
@Composable
internal fun OrderFlowLayout(
    title: String,
    progress: String?,
    onBack: () -> Unit,
    busy: Boolean,
    band: OrderBand,
    onUndo: (() -> Unit)?,
    lastSent: String?,
    onAction: (FlowAction) -> Unit,
    highlighted: FlowAction?,
    enabled: Set<FlowAction>,
    menuItems: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
    bandActions: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    body: @Composable BoxScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(WarehouseStatusColors.pageComfort)) {
        FlowHeader(title = title, onBack = onBack, progress = progress, menuItems = menuItems)
        // A lookup in progress: a thin line under the header, steady height so nothing jumps.
        Box(Modifier.fillMaxWidth().heightIn(min = 3.dp)) {
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = WarehouseDimens.ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusBand(
                kind = band.kind,
                title = band.title,
                line = band.line,
                onUndo = if (band.undoable) onUndo else null,
                undoKey = band.key,
                actions = bandActions,
            )
            Box(Modifier.weight(1f), content = body)
            footer?.invoke(this)
            SendTray(lastSent = lastSent)
        }
        FlowActionBar(onAction = onAction, highlighted = highlighted, enabled = enabled)
    }
}

/** A white card holding a list - the vendor list, a variant or stock choice, Lines (F2). */
@Composable
internal fun ListCard(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(WarehouseStatusColors.surface),
        content = content,
    )
}

internal fun LazyListScope.sectionLabel(text: String, key: Any? = null) {
    item(key = key) {
        Text(
            text,
            style = WarehouseType.label,
            color = WarehouseStatusColors.inkMuted,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        )
    }
}

internal fun LazyListScope.sectionNote(text: String) {
    item { Text(text, style = WarehouseType.body, color = WarehouseStatusColors.inkMuted, modifier = Modifier.padding(16.dp)) }
}

/** The one real choice a scan can need: which variant of the item. */
@Composable
internal fun VariantChoice(item: OrderItem, onPick: (OrderVariant) -> Unit) {
    ListCard {
        sectionLabel("${item.itemId} - which one?")
        items(item.variants) { variant ->
            ListRow(
                title = item.variantNames[variant]?.takeIf { it.isNotBlank() } ?: variant.label.ifBlank { item.itemId },
                subtitles = listOfNotNull(variant.label.takeIf { item.variantNames[variant] != null && it.isNotBlank() }),
                status = StatusKind.Warn,
                onClick = { onPick(variant) },
            )
            HorizontalDivider(color = WarehouseStatusColors.divider)
        }
    }
}

/** Several locations / batches / serials hold the item: which one the stock comes from. */
@Composable
internal fun StockChoice(item: OrderItem, rows: List<StockRow>, onPick: (StockRow) -> Unit) {
    ListCard {
        sectionLabel("${item.itemId} - take it from")
        items(rows.filter { it.available > 0 }) { row ->
            ListRow(
                title = row.label.ifBlank { "Any" },
                status = StatusKind.Warn,
                trailing = { ListRowValue(qtyText(row.available)) },
                onClick = { onPick(row) },
            )
            HorizontalDivider(color = WarehouseStatusColors.divider)
        }
    }
}

/**
 * Lines (F2): the order's lines, newest first, each removable; [onEdit] opens the keypad on one.
 * [footer] adds the screen's own rows (a total) after the lines.
 */
@Composable
internal fun DraftLinesView(
    lines: List<DraftLine>,
    showPrice: Boolean,
    currency: String,
    onEdit: (DraftLine) -> Unit,
    onRemove: (DraftLine) -> Unit,
    footer: (LazyListScope.() -> Unit)? = null,
) {
    ListCard {
        sectionLabel("On this order (${lines.size})")
        if (lines.isEmpty()) sectionNote("Nothing scanned yet")
        items(lines.reversed(), key = { it.id }) { line ->
            ListRow(
                title = line.itemId,
                codeTitle = true,
                subtitles = lineSubtitles(line, showPrice, currency),
                status = StatusKind.Ok,
                onClick = { onEdit(line) },
                trailing = {
                    ListRowValue(qtyText(line.qty) + line.unitId.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty())
                    IconButton(onClick = { onRemove(line) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove ${line.itemId}", tint = WarehouseStatusColors.inkMuted)
                    }
                },
            )
            HorizontalDivider(color = WarehouseStatusColors.divider)
        }
        footer?.invoke(this)
    }
}

internal fun lineSubtitles(line: DraftLine, showPrice: Boolean, currency: String): List<String> = listOfNotNull(
    line.itemName.takeIf { it.isNotBlank() && !it.equals(line.itemId, ignoreCase = true) },
    listOfNotNull(
        line.variant.label.takeIf { it.isNotBlank() },
        line.locationId.takeIf { it.isNotBlank() }?.let { "Location $it" },
        line.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
        line.serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" },
    ).joinToString(" · ").ifBlank { null },
    if (showPrice) priceText(line, currency) else null,
)

internal fun priceText(line: DraftLine, currency: String): String =
    line.lineAmount?.let { "${amountText(it)} $currency".trim() + if (line.priceOverride) " · price set here" else "" }
        ?: "Priced by D365FO"

/** The last line scanned, on the next-card: code, name, big quantity. Tapping the quantity opens the keypad. */
@Composable
internal fun LineCard(
    label: String,
    line: DraftLine,
    location: String?,
    locationLabel: String,
    onQuantityClick: (() -> Unit)?,
) {
    NextCard(
        label = label,
        code = line.itemId,
        description = listOfNotNull(
            line.itemName.takeIf { it.isNotBlank() && !it.equals(line.itemId, ignoreCase = true) },
            line.variant.label.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifBlank { null },
        location = location,
        locationLabel = locationLabel,
        quantity = qtyText(line.qty),
        quantityUnit = line.unitId.takeIf { it.isNotBlank() },
        onQuantityClick = onQuantityClick,
        modifier = Modifier.fillMaxSize(),
    )
}
