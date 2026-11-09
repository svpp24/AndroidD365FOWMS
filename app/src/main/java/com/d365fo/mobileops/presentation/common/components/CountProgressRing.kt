package com.d365fo.mobileops.presentation.common.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * Counting progress as a ring with the percentage inside (feedback, 14 Oct: "make the progress
 * bar a round one with percentage inside it"). Green once everything is counted.
 */
@Composable
fun CountProgressRing(progress: Float, percent: Int, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    val shown by animateFloatAsState(targetValue = progress.coerceIn(0f, 1f), label = "countProgress")
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { shown },
            modifier = Modifier.size(size),
            color = if (percent >= 100) WarehouseStatusColors.green else WarehouseStatusColors.accent,
            trackColor = WarehouseStatusColors.border.copy(alpha = 0.5f),
            strokeWidth = if (size < 56.dp) 4.dp else 6.dp,
            strokeCap = StrokeCap.Round,
        )
        // Sized to the ring, and a tick when done: "100%" ran over the ring at 44dp (feedback, 21 Oct).
        if (percent >= 100) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Default.Check,
                contentDescription = "All counted",
                tint = WarehouseStatusColors.green,
                modifier = Modifier.size(size * 0.5f),
            )
        } else {
            Text(
                text = "$percent%",
                fontSize = androidx.compose.ui.unit.TextUnit(size.value * 0.26f, androidx.compose.ui.unit.TextUnitType.Sp),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}
