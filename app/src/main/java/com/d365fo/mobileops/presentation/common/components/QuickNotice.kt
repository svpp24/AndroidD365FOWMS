package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import kotlinx.coroutines.delay

/** A short confirmation shown over the screen - see [QuickNotice]. [id] tells two equal texts apart. */
data class QuickNoticeMessage(
    val id: Long,
    val text: String,
    /**
     * Guidance ("… is the batch - enter the quantity first"), not a confirmation: amber with an
     * info icon, and up long enough to read a sentence - a one-second green tick suited "done",
     * not instructions (TO-37, 10 Oct).
     */
    val hint: Boolean = false,
)

/**
 * A green "done" notice floating near the top of the screen that goes away by itself after
 * [durationMs] (feedback 70a0c099, 8 Oct 2026: a line received without error is confirmed with a
 * notice that disappears in about a second). It takes no input and moves nothing on the screen,
 * so the next scan can follow straight away.
 */
@Composable
fun QuickNotice(message: QuickNoticeMessage?, onShown: () -> Unit, durationMs: Long = 1_000) {
    if (message == null) return
    LaunchedEffect(message.id) {
        delay(if (message.hint) HINT_DURATION_MS else durationMs)
        onShown()
    }
    Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, 140)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .background(if (message.hint) WarehouseStatusColors.amber else WarehouseStatusColors.green, RoundedCornerShape(24.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Icon(if (message.hint) Icons.Default.Info else Icons.Default.CheckCircle, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(10.dp))
            Text(
                message.text,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private const val HINT_DURATION_MS = 3_500L
