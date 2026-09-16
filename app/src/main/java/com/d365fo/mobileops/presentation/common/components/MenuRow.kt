package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * One flat menu row: icon, title, an optional info button, an optional chevron - the standard
 * WMS app's own Main Menu rhythm, matched deliberately rather than approximated. No status
 * badge: a row that cannot be opened yet says so by being greyed out and untappable
 * ([available] = false), the same way the reference app never labels a menu item as
 * unavailable, it simply is.
 *
 * Extracted from [com.d365fo.mobileops.presentation.common.HomeScreen]'s original `ModuleRow`,
 * which was private and coupled to that screen's own `ModuleTile` type. This is the same visual
 * rhythm generalised so any flat menu — Home's module list, Data enquiry's lookup list — can
 * share one row implementation and one divider convention instead of each screen reinventing it.
 */
@Composable
fun MenuRow(
    icon: ImageVector,
    title: String,
    available: Boolean,
    onClick: () -> Unit,
    onInfo: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val accentColor = if (available) WarehouseStatusColors.accent else WarehouseStatusColors.border

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WarehouseDimens.MinTouchTarget)
            .clickable(enabled = available, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = accentColor,
        )

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = if (available) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 14.dp, end = 8.dp),
        )

        if (onInfo != null) {
            IconButton(onClick = onInfo, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "About $title",
                    tint = WarehouseStatusColors.text3,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        if (available) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = WarehouseStatusColors.text3,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
