package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.domain.model.ItemUnit
import java.math.BigDecimal

/**
 * The units a quantity can be entered in, from the item's conversion setup. Picking one makes
 * the number typed next mean that unit ("2" with BOX = 24 PCS); the line's own unit first.
 * Wraps onto further lines rather than scrolling sideways, so no unit is hidden off-screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnitChips(
    units: List<ItemUnit>,
    lineUnit: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val ordered = units.sortedWith(compareBy({ !it.unitId.equals(lineUnit, true) }, { it.factor }))
    fun label(unit: ItemUnit) = if (unit.unitId.equals(lineUnit, ignoreCase = true)) unit.unitId
        else "${unit.unitId} (${unit.factor.stripTrailingZeros().let { f ->
            if (f < BigDecimal.ONE) f.setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros() else f
        }.toPlainString()} $lineUnit)"
    // Three rows of six chips under the quantity was "clumsy" (feedback 15d74b60, BSR-47274-0):
    // more than a few units are a dropdown, the same field as TO creation's warehouses.
    if (ordered.size > MAX_UNIT_CHIPS) {
        DropdownField(
            options = ordered.map { DropdownOption(it.unitId, label(it)) },
            selectedId = selected,
            placeholder = "Unit",
            prefix = "Unit: ",
            onPick = onSelect,
        )
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ordered.forEach { unit ->
            FilterChip(
                selected = unit.unitId.equals(selected, ignoreCase = true),
                onClick = { onSelect(unit.unitId) },
                label = { Text(label(unit)) },
            )
        }
    }
}

/** Up to this many units are chips; more are a [DropdownField]. */
private const val MAX_UNIT_CHIPS = 3
