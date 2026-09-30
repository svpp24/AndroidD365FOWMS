package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * A progress bar that reads as one: a box with a visible border and track (8dp - clearly a bar, without dominating the screen), and a solid
 * green fill for the share done. Material's own `LinearProgressIndicator` is a thin line on a
 * pale track, which on a handheld screen looked like a divider rather than a bar. Square-cornered
 * like every other control in the app.
 *
 * Any non-zero progress shows at least a sliver of fill, so "1 of 231" is not indistinguishable
 * from "nothing yet".
 */
@Composable
fun CountProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
) {
    val fraction = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .border(1.dp, WarehouseStatusColors.border)
            .background(WarehouseStatusColors.surface2),
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(min = 4.dp)
                    .fillMaxWidth(fraction)
                    .background(WarehouseStatusColors.green),
            )
        }
    }
}
