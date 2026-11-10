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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Inventory2
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
 * A [com.d365fo.mobileops.presentation.common.focus.FocusListRow] (Focus design): no card, a
 * divider under each row - light enough for a list of hundreds on a low-end handheld.
 *
 * Nothing is truncated: a long name or location/variant line wraps. That is also cheaper than an
 * ellipsis, which measures the text a second time to fit the "…".
 */
@Composable
internal fun CountingLineRow(line: CountLine, onClick: (() -> Unit)? = null) {
    // The "where" text only changes with the line itself, not on every recomposition.
    val where = remember(line) { lineWhere(line) }
    val verified = line.verifyOnly && line.confirmedAt != null
    com.d365fo.mobileops.presentation.common.focus.FocusListRow(
        title = line.itemId,
        titleIsCode = true,
        state = when {
            line.countedQuantity != null || verified -> com.d365fo.mobileops.presentation.common.focus.RowState.Done
            line.markedCounted -> com.d365fo.mobileops.presentation.common.focus.RowState.Warn
            else -> com.d365fo.mobileops.presentation.common.focus.RowState.Open
        },
        icon = Icons.Default.Flag.takeIf { line.markedCounted && line.countedQuantity == null && !verified },
        // The export has no item name; this is the name resolved from D365FO, and is skipped
        // while it's still just the item number again.
        subtitle = listOfNotNull(
            line.itemName.takeIf { it.isNotBlank() && !it.equals(line.itemId, ignoreCase = true) },
            where.ifEmpty { null },
        ).joinToString(" · ").ifEmpty { null },
        count = line.countedQuantity ?: if (verified) "Verified" else "–",
        countColor = if (line.isCounted) com.d365fo.mobileops.presentation.common.theme.FocusColors.navy
        else com.d365fo.mobileops.presentation.common.theme.FocusColors.muted,
        onClick = onClick,
    )
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
