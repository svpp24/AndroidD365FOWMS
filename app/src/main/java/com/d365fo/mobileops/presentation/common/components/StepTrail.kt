package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * The steps of a flow by name - done ones ticked, the current one highlighted, the rest grey -
 * instead of "Step 2 of 5", whose total changes as the item decides which steps apply.
 */
@Composable
fun StepTrail(names: List<String>, current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        names.forEachIndexed { index, name ->
            if (index > 0) {
                Text("\u203A", style = MaterialTheme.typography.labelMedium, color = WarehouseStatusColors.text3)
            }
            when {
                index < current -> Text(
                    "\u2713 $name",
                    style = MaterialTheme.typography.labelMedium,
                    color = WarehouseStatusColors.green,
                )
                index == current -> Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = WarehouseStatusColors.accent,
                )
                else -> Text(name, style = MaterialTheme.typography.labelMedium, color = WarehouseStatusColors.text3)
            }
        }
    }
}
