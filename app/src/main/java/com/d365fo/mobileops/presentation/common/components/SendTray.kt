package com.d365fo.mobileops.presentation.common.components

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.data.sync.SyncStatus
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.presentation.common.theme.WarehouseType
import com.d365fo.mobileops.presentation.common.theme.breakableCode

/**
 * What the send tray says - worked out from [SyncStatus] by [sendTrayState], kept separate from
 * the composable so the wording can be unit-tested without a screen.
 */
data class SendTrayState(
    val kind: Kind,
    val title: String,
    val line: String,
) {
    enum class Kind { Sent, Sending, Waiting, Held, Failed }
}

/**
 * The tray's wording for a [SyncStatus], most urgent first: something failed, then posting is
 * switched off, then waiting for the network or D365FO, then sending, then all sent.
 *
 * @param lastSent the flow's own last result, e.g. "GRN BSP-000000053" - shown on the sent line
 *   so the worker sees *what* went, not only that the queue is empty.
 */
fun sendTrayState(status: SyncStatus, lastSent: String? = null): SendTrayState {
    val pending = status.pendingCount
    fun items(n: Int) = if (n == 1) "1 item" else "$n items"
    return when {
        status.failedCount > 0 -> SendTrayState(
            SendTrayState.Kind.Failed,
            "${items(status.failedCount)} not sent",
            "Open Activity to fix " + if (status.failedCount == 1) "it" else "them",
        )
        pending > 0 && !status.postingEnabled -> SendTrayState(
            SendTrayState.Kind.Held,
            "${items(pending)} kept on this device",
            "Data posting is off in Settings",
        )
        pending > 0 && !status.online -> SendTrayState(
            SendTrayState.Kind.Waiting,
            "${items(pending)} waiting to send",
            "You're offline - it sends when you're back",
        )
        pending > 0 && !status.d365Reachable -> SendTrayState(
            SendTrayState.Kind.Waiting,
            "${items(pending)} waiting to send",
            "D365FO can't be reached - it sends when it answers",
        )
        pending > 0 -> SendTrayState(
            SendTrayState.Kind.Sending,
            "Sending ${items(pending)}",
            "Keep scanning - nothing to wait for",
        )
        else -> SendTrayState(
            SendTrayState.Kind.Sent,
            "All sent",
            lastSent ?: "Nothing waiting to send",
        )
    }
}

private fun SendTrayState.Kind.icon(): ImageVector = when (this) {
    SendTrayState.Kind.Sent -> Icons.Filled.CheckCircle
    SendTrayState.Kind.Sending -> Icons.Filled.CloudUpload
    SendTrayState.Kind.Waiting -> Icons.Filled.CloudOff
    SendTrayState.Kind.Held -> Icons.Filled.PauseCircle
    SendTrayState.Kind.Failed -> Icons.Filled.Error
}

private fun SendTrayState.Kind.tint(): Color = when (this) {
    SendTrayState.Kind.Sent -> WarehouseStatusColors.okEdge
    SendTrayState.Kind.Sending -> WarehouseStatusColors.infoEdge
    SendTrayState.Kind.Waiting, SendTrayState.Kind.Held -> WarehouseStatusColors.warnEdge
    SendTrayState.Kind.Failed -> WarehouseStatusColors.badEdge
}

/**
 * What is waiting to send, sending, or sent (redesign brief §2) - the bar above
 * [FlowActionBar] on every flow screen. Posting happens in the background when the work ends, so
 * this is where the worker sees that it went: an icon and two lines, with a green border once
 * everything is sent.
 *
 * Reads [LocalSyncStatus] by default, which AppRoot provides from `SyncStatusProvider` - the same
 * source as the top bar's cloud icon, so the two can never disagree. Draws nothing where no status
 * is provided. A static icon rather than a spinner while sending: the brief keeps flow screens
 * free of movement, and the line already says it is sending.
 *
 * @param onClick usually opens Activity, where a failed item is fixed.
 */
@Composable
fun SendTray(
    modifier: Modifier = Modifier,
    status: SyncStatus? = LocalSyncStatus.current,
    lastSent: String? = null,
    onClick: (() -> Unit)? = null,
) {
    if (status == null) return
    val state = sendTrayState(status, lastSent)
    val tint = state.kind.tint()
    val shape = RoundedCornerShape(14.dp)
    val sent = state.kind == SendTrayState.Kind.Sent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(WarehouseStatusColors.surface)
            .border(if (sent) 2.dp else 1.dp, if (sent) WarehouseStatusColors.okEdge else WarehouseStatusColors.divider, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Icon(state.kind.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(state.title.breakableCode(), style = WarehouseType.line, color = WarehouseStatusColors.ink)
            Text(state.line.breakableCode(), style = WarehouseType.label, color = WarehouseStatusColors.inkMuted)
        }
    }
}

@Preview(name = "SendTray", widthDp = 360, showBackground = true)
@Composable
private fun SendTrayPreview() = PreviewSurface { SendTraySamples() }

@Preview(name = "SendTray - 130%", widthDp = 360, showBackground = true)
@Composable
private fun SendTrayPreviewLarge() = PreviewSurface(large = true) { SendTraySamples() }

@Composable
internal fun SendTraySamples() {
    val base = SyncStatus(online = true, d365Reachable = true, pendingCount = 0, failedCount = 0, lastSyncAt = null, oldestQueuedAt = null)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SendTray(status = base, lastSent = "GRN BSP-000000053 posted")
        SendTray(status = base.copy(pendingCount = 2))
        SendTray(status = base.copy(pendingCount = 3, online = false))
        SendTray(status = base.copy(pendingCount = 1, postingEnabled = false))
        SendTray(status = base.copy(failedCount = 1), onClick = {})
    }
}
