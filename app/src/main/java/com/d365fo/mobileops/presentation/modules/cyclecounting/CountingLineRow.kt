package com.d365fo.mobileops.presentation.modules.cyclecounting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.data.local.dao.CountLine
import com.d365fo.mobileops.domain.model.locationTypeLabel
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * One line of the journal: status tick, item number + name + where, and the counted quantity - text only, no inputs, so a list of hundreds scrolls smoothly on a low-end handheld. Shared by View lines and the Count screen; [onClick] makes it tappable.
 *
 * Looks like an AppCard but is drawn as one Row with a background and border: a Material Card
 * adds a Surface, a CompositionLocal provider and a Column per row, and on a fling every newly
 * visible row pays for all of that - on large journals that was most of each frame.
 *
 * Nothing is truncated: a long name or location/variant line wraps. That is also cheaper than an
 * ellipsis, which measures the text a second time to fit the "…".
 */
@Composable
internal fun CountingLineRow(line: CountLine, onClick: (() -> Unit)? = null) {
    val shape = MaterialTheme.shapes.medium
    // The "where" text only changes with the line itself, not on every recomposition.
    val where = remember(line) { lineWhere(line) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape)
            .then(if (onClick != null) Modifier.clip(shape).clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val (icon, tint) = when {
            line.countedQuantity != null -> Icons.Default.CheckCircle to WarehouseStatusColors.green
            line.verifyOnly && line.confirmedAt != null -> Icons.Default.CheckCircle to WarehouseStatusColors.green
            line.markedCounted -> Icons.Default.Flag to WarehouseStatusColors.amber
            else -> Icons.Default.RadioButtonUnchecked to WarehouseStatusColors.text3
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = line.itemId,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            // The export has no item name; this is the name resolved from D365FO, and is
            // skipped while it's still just the item number again.
            line.itemName.takeIf { it.isNotBlank() && !it.equals(line.itemId, ignoreCase = true) }?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
            if (where.isNotEmpty()) {
                Text(text = where, style = MaterialTheme.typography.labelSmall, color = WarehouseStatusColors.text3)
            }
        }

        Text(
            text = line.countedQuantity ?: if (line.verifyOnly && line.confirmedAt != null) "Verified" else "-",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            color = if (line.isCounted) WarehouseStatusColors.green else WarehouseStatusColors.text3,
        )
    }
}

private fun lineWhere(line: CountLine): String = listOfNotNull(
    line.locationId?.takeIf { it.isNotBlank() }?.let { "Loc $it" },
    line.variant.label.takeIf { it.isNotBlank() },
    line.batchId?.takeIf { it.isNotBlank() }?.let { "Batch $it" },
    line.serialId?.takeIf { it.isNotBlank() }?.let { "Serial $it" },
    locationTypeLabel(line.locationType),
    line.locationOwner?.let { "Owner $it" },
    "Verify only".takeIf { line.verifyOnly },
).joinToString(" · ")
