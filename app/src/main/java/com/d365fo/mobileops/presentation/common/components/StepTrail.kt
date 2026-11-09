package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * The steps of a flow as a row of icon tabs under the top bar (Harbour design, 9 Nov): done
 * steps green, the current one in the accent with an underline, the rest grey. Names stay - the
 * total changes as the item decides which steps apply, so this never says "Step 2 of 5".
 *
 * A long name wraps inside its tab rather than being cut off.
 */
@Composable
fun StepTrail(
    names: List<String>,
    current: Int,
    modifier: Modifier = Modifier,
    /** The parent's side padding - the tabs run edge to edge under the bar regardless. */
    bleed: androidx.compose.ui.unit.Dp = 16.dp,
    /** Tapping a done step goes back to it; null keeps the tabs display-only. */
    onStepClick: ((Int) -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .layout { measurable, constraints ->
                val extra = (bleed * 2).roundToPx()
                val wide = constraints.copy(
                    minWidth = constraints.maxWidth + extra,
                    maxWidth = constraints.maxWidth + extra,
                )
                val p = measurable.measure(wide)
                layout(constraints.maxWidth, p.height) { p.place(-bleed.roundToPx(), 0) }
            }
            .background(Color.White),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            names.forEachIndexed { index, name ->
                val color = when {
                    index < current -> WarehouseStatusColors.green
                    index == current -> WarehouseStatusColors.accent
                    else -> WarehouseStatusColors.text3
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (onStepClick != null && index < current) {
                                Modifier.clickable { onStepClick(index) }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.padding(top = 7.dp, bottom = 6.dp, start = 2.dp, end = 2.dp),
                    ) {
                        Icon(stepIcon(name), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        Text(
                            name,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (index == current) FontWeight.ExtraBold else FontWeight.Bold,
                            color = color,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(if (index == current) WarehouseStatusColors.accent else Color.Transparent),
                    )
                }
            }
        }
        HorizontalDivider(color = WarehouseStatusColors.overlay)
    }
}

/** An icon for a step by its name - the flows name their steps, not their icons. */
private fun stepIcon(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        n.startsWith("po") || n == "details" || n == "reason" || n == "order" -> Icons.Filled.Description
        n.contains("transfer") -> Icons.Filled.SwapHoriz
        n.contains("vendor") -> Icons.Filled.Store
        n.contains("route") -> Icons.Filled.Route
        n.contains("location") -> Icons.Filled.LocationOn
        n == "bin" -> Icons.Filled.Inventory2
        n.contains("item") -> BarcodeGlyph
        n.contains("qty") || n.contains("quantity") -> Icons.Filled.Numbers
        n.contains("batch") -> Icons.Filled.Tag
        n.contains("serial") -> Icons.Filled.QrCode2
        n.contains("expiry") || n.contains("date") -> Icons.Filled.CalendarMonth
        n.contains("ship") -> Icons.Filled.LocalShipping
        else -> Icons.AutoMirrored.Filled.FactCheck
    }
}
