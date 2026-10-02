package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.domain.model.ItemUnit
import java.math.BigDecimal

/**
 * The units a quantity can be entered in, from the item's conversion setup. Picking one makes
 * the number typed next mean that unit ("2" with BOX = 24 PCS); the line's own unit first.
 */
@Composable
fun UnitChips(
    units: List<ItemUnit>,
    lineUnit: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val ordered = units.sortedWith(compareBy({ !it.unitId.equals(lineUnit, true) }, { it.factor }))
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = androidx.compose.ui.Modifier.horizontalScroll(rememberScrollState()),
    ) {
        ordered.forEach { unit ->
            val isLine = unit.unitId.equals(lineUnit, ignoreCase = true)
            FilterChip(
                selected = unit.unitId.equals(selected, ignoreCase = true),
                onClick = { onSelect(unit.unitId) },
                label = {
                    Text(
                        if (isLine) unit.unitId
                        else "${unit.unitId} (${unit.factor.stripTrailingZeros().let { f ->
                            if (f < BigDecimal.ONE) f.setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros() else f
                        }.toPlainString()} $lineUnit)",
                    )
                },
            )
        }
    }
}
