package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens

/**
 * The one small badge that carries most of the theme's status language: a dim colour-tinted
 * background, a full-strength border and text in the same colour, uppercase mono. Every status
 * badge across the app - module availability, queue state, chip filters - is built from this
 * rather than each screen inventing its own, so they read as one system.
 *
 * Square, like every other shape in this theme - see [WarehouseDimens.CardCorner]. This was a
 * fully rounded 20.dp pill until a request to make shapes app-wide square specifically for
 * rendering cost; it is hand-drawn with `background`/`border` rather than through Material's own
 * `Shapes` slots, so it needed its own fix rather than picking one up automatically.
 */
@Composable
fun StatusChip(
    text: String,
    color: Color,
    dimColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(dimColor, RoundedCornerShape(WarehouseDimens.CardCorner))
            .border(1.dp, color, RoundedCornerShape(WarehouseDimens.CardCorner)),
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            // A chip is one short label: kept on one line, never broken mid-word ("PROG / RESS").
            // Where space is tight the layout around it moves the whole chip instead.
            softWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
