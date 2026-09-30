package com.d365fo.mobileops.presentation.modules.cyclecounting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.data.local.entity.JournalLineEntity
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * One line of the journal: status tick, item number + name + where, and the counted quantity - text only, no inputs, so a list of hundreds scrolls smoothly on a low-end handheld. Shared by View lines and the Count screen; [onClick] makes it tappable.
 */
@Composable
internal fun CountingLineRow(line: JournalLineEntity, onClick: (() -> Unit)? = null) {
    Card(modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val (icon, tint) = when {
                line.countedQuantity != null -> Icons.Default.CheckCircle to WarehouseStatusColors.green
                line.markedCounted -> Icons.Default.Flag to WarehouseStatusColors.amber
                else -> Icons.Default.RadioButtonUnchecked to WarehouseStatusColors.text3
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = line.itemId,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                // The export has no item name; this is the name resolved from D365FO, and is
                // skipped while it's still just the item number again.
                line.itemName.takeIf { it.isNotBlank() && it != line.itemId }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val where = listOfNotNull(
                    line.locationId?.takeIf { it.isNotBlank() }?.let { "Loc $it" },
                    line.batchId?.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                    line.serialId?.takeIf { it.isNotBlank() }?.let { "Serial $it" },
                ).joinToString(" · ")
                if (where.isNotEmpty()) {
                    Text(
                        text = where,
                        style = MaterialTheme.typography.labelSmall,
                        color = WarehouseStatusColors.text3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Text(
                text = line.countedQuantity ?: "-",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                color = if (line.countedQuantity != null) WarehouseStatusColors.green else WarehouseStatusColors.text3,
            )
        }
    }
}
