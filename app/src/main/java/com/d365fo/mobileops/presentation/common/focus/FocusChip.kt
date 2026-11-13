package com.d365fo.mobileops.presentation.common.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusShapes
import com.d365fo.mobileops.presentation.common.theme.FocusType

/**
 * A filter or choice chip in the Focus look (user, 13 Nov: Material's chips did not match the
 * app): navy with white text when chosen, white with a light border otherwise, pill shaped,
 * 48 dp tall. The label wraps rather than being cut.
 */
@Composable
fun FocusChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(FocusShapes.pill)
            .background(if (selected) FocusColors.navy else FocusColors.surface)
            .border(1.dp, if (selected) FocusColors.navy else FocusColors.divider, FocusShapes.pill)
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, style = FocusType.unitLine, color = if (selected) FocusColors.surface else FocusColors.ink)
    }
}
