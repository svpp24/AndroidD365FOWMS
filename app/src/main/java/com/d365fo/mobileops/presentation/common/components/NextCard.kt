package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.presentation.common.theme.WarehouseType
import com.d365fo.mobileops.presentation.common.theme.breakableCode

/**
 * What to scan next, filling the space between the status band and the send tray (redesign brief
 * §2): a small label ("Next · line 1 of 2"), the item code big in [com.d365fo.mobileops
 * .presentation.common.theme.CodeFont], its description, and a bottom row with the location on
 * the left and the quantity, biggest of all, on the right.
 *
 * Sized by the caller: pass `Modifier.weight(1f)` from the flow screen's column to make it fill
 * the remaining height. The content is spread top to bottom, so on a tall card the code sits at
 * the top and location/quantity at the bottom, where the thumb is. Every text wraps - a long
 * description or a 20-character bin code makes the card taller, never cut off.
 *
 * @param onQuantityClick opens the keypad (the same as F4) - tapping the number is the obvious
 *   way to change it. Null when the quantity can't be changed here.
 */
@Composable
fun NextCard(
    label: String,
    code: String,
    /** Null when there is no quantity yet - the card shows the code and location only. */
    quantity: String?,
    modifier: Modifier = Modifier,
    description: String? = null,
    location: String? = null,
    locationLabel: String = "Location",
    /** Shown under the quantity, e.g. "pcs" or "of 12". */
    quantityUnit: String? = null,
    onQuantityClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(WarehouseStatusColors.surface)
            .border(1.dp, WarehouseStatusColors.divider, shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = WarehouseType.label, color = WarehouseStatusColors.inkMuted)
            Text(code.breakableCode(), style = WarehouseType.code, color = WarehouseStatusColors.ink)
            if (description != null) {
                Text(description, style = WarehouseType.body, color = WarehouseStatusColors.ink)
            }
        }
        LocationQuantityRow(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            location = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (location != null) {
                        Text(locationLabel, style = WarehouseType.label, color = WarehouseStatusColors.inkMuted)
                        Text(location.breakableCode(), style = WarehouseType.codeSmall, color = WarehouseStatusColors.ink)
                    }
                }
            },
        ) {
            if (quantity != null) Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .widthIn(min = WarehouseDimens.MinTouchTarget)
                    .heightIn(min = WarehouseDimens.MinTouchTarget)
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (onQuantityClick != null) {
                            Modifier.clickable(role = Role.Button, onClickLabel = "Change the quantity", onClick = onQuantityClick)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 6.dp),
            ) {
                Text(quantity, style = WarehouseType.quantity, color = WarehouseStatusColors.ink, textAlign = TextAlign.End)
                if (quantityUnit != null) {
                    Text(quantityUnit, style = WarehouseType.label, color = WarehouseStatusColors.inkMuted, textAlign = TextAlign.End)
                }
            }
        }
    }
}

/**
 * Location on the left, quantity on the right, bottoms aligned - or, when the two don't fit side
 * by side on one line each, the location across the full width with the quantity right-aligned
 * under it. A plain Row squeezed a long bin code into a sliver beside a big quantity and broke it
 * mid-word (ComponentLayoutTest at 130%, 9 Nov); stacking keeps every code whole.
 */
@Composable
private fun LocationQuantityRow(
    location: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    quantity: @Composable () -> Unit,
) {
    Layout(contents = listOf(location, quantity), modifier = modifier) { (locs, qtys), constraints ->
        val gap = 12.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val locMeasurable = locs.single()
        // No quantity on the card: the location row on its own.
        val qtyMeasurable = qtys.singleOrNull() ?: run {
            val loc = locMeasurable.measure(loose)
            return@Layout layout(constraints.maxWidth, loc.height) { loc.placeRelative(0, 0) }
        }
        val qty = qtyMeasurable.measure(loose)
        val sideBySide = locMeasurable.maxIntrinsicWidth(Constraints.Infinity) + gap + qty.width <= constraints.maxWidth
        val loc = locMeasurable.measure(
            if (sideBySide) loose.copy(maxWidth = constraints.maxWidth - qty.width - gap) else loose,
        )
        val width = constraints.maxWidth
        if (sideBySide) {
            val height = maxOf(loc.height, qty.height)
            layout(width, height) {
                loc.placeRelative(0, height - loc.height)
                qty.placeRelative(width - qty.width, height - qty.height)
            }
        } else {
            layout(width, loc.height + gap + qty.height) {
                loc.placeRelative(0, 0)
                qty.placeRelative(width - qty.width, loc.height + gap)
            }
        }
    }
}

@Preview(name = "NextCard", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun NextCardPreview() = PreviewSurface { NextCardSamples() }

@Preview(name = "NextCard - 130%", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun NextCardPreviewLarge() = PreviewSurface(large = true) { NextCardSamples() }

@Composable
internal fun NextCardSamples() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NextCard(
            label = "Next · line 1 of 2",
            code = "BSR-11397-0",
            description = "Item description, pack of 21",
            location = "A-01-02",
            quantity = "5",
            quantityUnit = "pcs",
            onQuantityClick = {},
        )
        NextCard(
            label = "Next · line 12 of 140",
            code = "BSR-10113-0-VAR-02-BLUE",
            description = "A long item description that has to wrap onto a second and third line without being cut",
            location = "RECEIVING-DOCK-03-BIN-0042",
            locationLabel = "Bin",
            quantity = "1,250",
            quantityUnit = "of 2,400",
        )
    }
}
