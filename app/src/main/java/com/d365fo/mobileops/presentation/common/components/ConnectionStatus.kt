package com.d365fo.mobileops.presentation.common.components

import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import androidx.compose.foundation.layout.heightIn
import com.d365fo.mobileops.presentation.common.tour.tourTarget
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.data.remote.interceptor.OfflineReadNotice
import com.d365fo.mobileops.data.sync.SyncStatus
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.util.Formatters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The connection state for [AppTopBar]'s connection icon. Provided once, around the signed-in
 * app (see AppRoot). Null where nothing provides it, and then the icon isn't drawn.
 */
val LocalSyncStatus = compositionLocalOf<SyncStatus?> { null }

/**
 * Green cloud while D365FO answers, amber crossed-out cloud while it doesn't. Tapping it opens
 * [ConnectionDialog] with what that means for the worker's work.
 */
@Composable
fun ConnectionIcon(status: SyncStatus) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.tourTarget("connection")) {
        if (status.fullyOnline) {
            Icon(Icons.Default.CloudDone, contentDescription = "Online", tint = WarehouseStatusColors.green)
        } else {
            OfflineCloud(contentDescription = "Offline")
        }
    }
    if (open) ConnectionDialog(status = status, onDismiss = { open = false })
}

/**
 * What the connection state means for the worker's work: shown once on its own when the
 * connection drops (see AppNavGraph), and again whenever the top bar's icon is tapped.
 */
@Composable
fun ConnectionDialog(status: SyncStatus, onDismiss: () -> Unit) {
    val savedAt by OfflineReadNotice.savedAt.collectAsState()
    val (title, message) = when {
        !status.online -> "You're offline" to
            "This device has no internet connection. You can keep working: your work is saved on this " +
            "device and sent to D365FO when the connection is back."
        !status.d365Reachable -> "D365FO can't be reached" to
            "The internet is working, but D365FO isn't answering. You can keep working: your work is saved " +
            "on this device and sent when D365FO answers again."
        else -> "You're online" to "D365FO is answering. Your work is sent as soon as you finish it."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            if (status.fullyOnline) {
                Icon(Icons.Default.CloudDone, contentDescription = null, tint = WarehouseStatusColors.green)
            } else {
                OfflineCloud(contentDescription = null)
            }
        },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, style = MaterialTheme.typography.bodyLarge)
                val at = savedAt
                if (!status.fullyOnline && at != null) {
                    Text(
                        "Order details are from " + savedLabel(at) + ". D365FO checks them again when your work is sent.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (status.pendingCount > 0) {
                    Text(
                        "${status.pendingCount} " + (if (status.pendingCount == 1) "item is" else "items are") +
                            " waiting to be sent.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                dismiss = null,
                confirm = { com.d365fo.mobileops.presentation.common.components.ConfirmButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("OK") } },
            )
        },
    )
}

private fun savedLabel(epochMs: Long): String {
    val day = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    return (if (day == LocalDate.now()) "" else Formatters.shortDate(epochMs) + " ") + Formatters.clockTime(epochMs)
}

/**
 * A solid amber cloud with a slash cut through it. Material's own CloudOff is line art, which
 * reads as a grey smudge at top-bar size; this matches the solid CloudDone shown when online.
 */
@Composable
private fun OfflineCloud(contentDescription: String?) {
    Box(modifier = Modifier.size(24.dp)) {
        Icon(
            Icons.Default.Cloud,
            contentDescription = contentDescription,
            tint = WarehouseStatusColors.amber,
            modifier = Modifier.matchParentSize(),
        )
        Canvas(modifier = Modifier.matchParentSize()) {
            val unit = size.width / 24f
            val from = Offset(3f * unit, 3f * unit)
            val to = Offset(21f * unit, 21f * unit)
            // The gap first, in the bar's white, then the slash itself.
            drawLine(Color.White, from, to, strokeWidth = 4.5f * unit, cap = StrokeCap.Round)
            drawLine(WarehouseStatusColors.amber, from, to, strokeWidth = 2f * unit, cap = StrokeCap.Round)
        }
    }
}
