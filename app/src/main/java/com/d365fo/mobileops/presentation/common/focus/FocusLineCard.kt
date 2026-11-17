package com.d365fo.mobileops.presentation.common.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusType

/**
 * One line on a Lines screen (user, 17 Nov 2026): the text uses the full width - code, name, then
 * each detail on its own line - and the − / quantity / + with the unit and the bin sit on a row
 * below it, so nothing is squeezed into wrapping. The quantity box opens the number pad when
 * [onQuantityTap] is set. [status] is a short word under the details ("Picked").
 */
@Composable
fun FocusLineCard(
    code: String,
    name: String?,
    details: List<String>,
    quantity: String,
    unit: String?,
    modifier: Modifier = Modifier,
    canMinus: Boolean = false,
    canPlus: Boolean = false,
    onMinus: (() -> Unit)? = null,
    onPlus: (() -> Unit)? = null,
    onQuantityTap: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    removeEnabled: Boolean = true,
    status: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = FocusDimens.side, vertical = 12.dp),
    ) {
        Text(code.breakAfterHyphens(), style = FocusType.rowTitle, fontWeight = FontWeight.SemiBold, color = FocusColors.ink)
        name?.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }?.let {
            Text(it, style = FocusType.rowDetail, color = FocusColors.inkSoft)
        }
        details.filter { it.isNotBlank() }.forEach {
            Text(it, style = FocusType.rowDetail, color = FocusColors.muted)
        }
        status?.let { Text(it, style = FocusType.rowDetail, color = FocusColors.okText) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            if (onMinus != null) {
                StepButton(Icons.Default.Remove, "Less", enabled = canMinus, onClick = onMinus)
            }
            val box = Modifier
                .widthIn(min = 64.dp)
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(FocusColors.surface)
                .border(1.5.dp, if (onQuantityTap != null) FocusColors.navy else FocusColors.divider, RoundedCornerShape(8.dp))
                .then(if (onQuantityTap != null) Modifier.clickable(role = Role.Button, onClickLabel = "Type the quantity", onClick = onQuantityTap) else Modifier)
                .padding(horizontal = 12.dp, vertical = 10.dp)
            Text(quantity, style = FocusType.rowTitle, color = FocusColors.navy, textAlign = TextAlign.Center, modifier = box)
            if (onPlus != null) {
                StepButton(Icons.Default.Add, "More", enabled = canPlus, onClick = onPlus)
            }
            unit?.takeIf { it.isNotBlank() }?.let { Text(it, style = FocusType.rowDetail, color = FocusColors.inkSoft) }
            Spacer(Modifier.weight(1f))
            if (onRemove != null) {
                IconButton(onClick = onRemove, enabled = removeEnabled, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove line", tint = FocusColors.muted)
                }
            }
        }
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(FocusColors.divider))
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Icon(
        icon,
        contentDescription = label,
        tint = if (enabled) FocusColors.navy else FocusColors.divider,
        modifier = Modifier
            .size(44.dp)
            .clip(shape)
            .background(FocusColors.surface)
            .border(1.dp, FocusColors.divider, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(10.dp),
    )
}

/**
 * − / quantity / + on a list row (the items list, 17 Nov 2026). The number box opens the pad;
 * [highlighted] marks an item already on the order.
 */
@Composable
fun MiniStepper(
    quantity: String,
    canMinus: Boolean,
    canPlus: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onTap: () -> Unit,
    highlighted: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StepButton(Icons.Default.Remove, "Less", enabled = canMinus, onClick = onMinus)
        Text(
            quantity,
            style = FocusType.rowDetail,
            fontWeight = FontWeight.Bold,
            color = if (highlighted) FocusColors.navy else FocusColors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(min = 52.dp)
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (highlighted) FocusColors.taskBg else FocusColors.surface)
                .border(1.dp, if (highlighted) FocusColors.navy else FocusColors.divider, RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClickLabel = "Type the quantity", onClick = onTap)
                .padding(horizontal = 8.dp, vertical = 12.dp),
        )
        StepButton(Icons.Default.Add, "More", enabled = canPlus, onClick = onPlus)
    }
}
