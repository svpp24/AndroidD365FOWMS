package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
 *
 * Wraps onto a second line rather than scrolling sideways: a batch-and-serial item has seven
 * steps, and a scrolled trail hid the last of them off the edge of the screen. Each separator
 * stays with the step after it, so a line never ends on a dangling "›".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StepTrail(names: List<String>, current: Int, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        names.forEachIndexed { index, name ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.CenterVertically),
            ) {
                if (index > 0) {
                    Text("›", style = MaterialTheme.typography.labelMedium, color = WarehouseStatusColors.text3)
                }
                when {
                    index < current -> Text(
                        "✓ $name",
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
}
