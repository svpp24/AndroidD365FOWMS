package com.d365fo.mobileops.presentation.common.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.presentation.common.theme.WarehouseType
import com.d365fo.mobileops.presentation.common.theme.breakableCode
import kotlinx.coroutines.delay

/**
 * The five states a scan can leave a flow screen in (redesign brief §2). Each one carries its
 * colours *and* an icon, so the state never depends on colour alone - a worker with red-green
 * colour blindness, or a screen washed out under dock lighting, still reads a tick, a warning
 * triangle or a cross. The band's title carries the word.
 *
 * Shared by [StatusBand] and [ListRow]'s leading status circle, so a "wrong item" looks the same
 * on the scan screen and in the line list.
 */
enum class StatusKind(
    val band: Color,
    val edge: Color,
    val text: Color,
    val icon: ImageVector,
    /** Spoken by TalkBack before the title - the word for the colour. */
    val spoken: String,
) {
    /** Waiting for the first scan. */
    Idle(WarehouseStatusColors.idleBand, WarehouseStatusColors.idleEdge, WarehouseStatusColors.ink, Icons.Outlined.QrCodeScanner, "Ready"),
    /** Document found, or plain information. */
    Info(WarehouseStatusColors.infoBand, WarehouseStatusColors.infoEdge, WarehouseStatusColors.infoText, Icons.Filled.Info, "Info"),
    /** The scan was accepted. */
    Ok(WarehouseStatusColors.okBand, WarehouseStatusColors.okEdge, WarehouseStatusColors.okText, Icons.Filled.CheckCircle, "Done"),
    /** Needs a choice before the flow can go on. */
    Warn(WarehouseStatusColors.warnBand, WarehouseStatusColors.warnEdge, WarehouseStatusColors.warnText, Icons.Filled.Warning, "Warning"),
    /** Wrong item or another error - nothing was added. */
    Bad(WarehouseStatusColors.badBand, WarehouseStatusColors.badEdge, WarehouseStatusColors.badText, Icons.Filled.Error, "Error"),
}

/** How long Undo stays on an [StatusKind.Ok] band after the scan it would undo. */
const val UNDO_WINDOW_MS = 5_000L

/** The 150ms cross-fade between kinds - the one movement the band is allowed (brief §2). */
private const val CROSS_FADE_MS = 150

/** The band's coloured left edge (brief §2). */
private val EDGE_WIDTH = 10.dp

/**
 * The result of the last scan, at the top of every flow screen - replaces [QuickNotice] there.
 *
 * Steady by design (brief ground rules): no flashing, no shake, no pop-in. When the kind changes
 * the colours cross-fade over 150ms and that is all; the sound and vibration that go with an
 * error are the caller's job, not this composable's. Nothing is truncated - a long title or line
 * wraps and the band grows.
 *
 * @param undoKey changes with every accepted scan - an [StatusKind.Ok] band with [onUndo] shows
 *   Undo for [UNDO_WINDOW_MS] after each new key, then hides it. Pass the scan's own id.
 * @param actions buttons for a [StatusKind.Warn] band's real choice, e.g. "Skip line" /
 *   "Ship without it". Shown under the text and wrap onto more rows if they need to.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StatusBand(
    kind: StatusKind,
    title: String,
    modifier: Modifier = Modifier,
    line: String? = null,
    onUndo: (() -> Unit)? = null,
    undoKey: Any? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val band by animateColorAsState(kind.band, tween(CROSS_FADE_MS), label = "band")
    val edge by animateColorAsState(kind.edge, tween(CROSS_FADE_MS), label = "edge")
    val text by animateColorAsState(kind.text, tween(CROSS_FADE_MS), label = "text")

    var undoVisible by remember { mutableStateOf(false) }
    LaunchedEffect(kind, undoKey, onUndo != null) {
        undoVisible = kind == StatusKind.Ok && onUndo != null
        if (undoVisible) {
            delay(UNDO_WINDOW_MS)
            undoVisible = false
        }
    }

    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(band)
            // The coloured edge is drawn, not laid out: a full-height child would need an
            // intrinsic-height pass, which under-measured a wrapping second line and clipped it
            // (caught by ComponentLayoutTest, 9 Nov). Drawn, the band simply takes the text's height.
            .drawBehind { drawRect(edge, size = Size(EDGE_WIDTH.toPx(), size.height)) }
            // Polite: read out each new result without cutting off whatever TalkBack is saying.
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = EDGE_WIDTH + 14.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    kind.icon,
                    contentDescription = kind.spoken,
                    tint = edge,
                    modifier = Modifier.padding(top = 2.dp).size(26.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(title.breakableCode(), style = WarehouseType.bandTitle, color = text, modifier = Modifier.weight(1f))
            }
            if (line != null) {
                Text(line.breakableCode(), style = WarehouseType.line, color = text, modifier = Modifier.padding(start = 36.dp))
            }
            if (undoVisible && onUndo != null) {
                UndoLink(color = text, onClick = onUndo, modifier = Modifier.padding(start = 24.dp))
            }
            if (actions != null) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) { actions() }
            }
        }
    }
}

/** Underlined "Undo", with a full gloved-fingertip touch target around the short word. */
@Composable
private fun UndoLink(color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .heightIn(min = WarehouseDimens.MinTouchTarget)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = "Undo the last scan", onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        Text(
            "Undo",
            style = WarehouseType.line.copy(textDecoration = TextDecoration.Underline),
            color = color,
        )
    }
}

@Preview(name = "StatusBand - all kinds", widthDp = 360, showBackground = true, backgroundColor = 0xFFF1F5F9)
@Composable
private fun StatusBandPreview() = PreviewSurface { StatusBandSamples() }

@Preview(name = "StatusBand - 130%", widthDp = 360, showBackground = true, backgroundColor = 0xFFF1F5F9)
@Composable
private fun StatusBandPreviewLarge() = PreviewSurface(large = true) { StatusBandSamples() }

@Composable
internal fun StatusBandSamples() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusBand(StatusKind.Idle, "Ready", line = "Scan a purchase order to start")
        StatusBand(StatusKind.Info, "BSP-PO-000161763", line = "Vendor 27455 · 2 lines to receive")
        StatusBand(StatusKind.Ok, "Received 5 pcs", line = "BSR-11397-0 · batch 24A17", onUndo = {}, undoKey = 1)
        StatusBand(
            StatusKind.Warn,
            "Not enough stock",
            line = "Only 3 of 5 at location A-01-02",
            actions = {
                SquareOutlinedButton(onClick = {}) { Text("Skip line") }
                SquareButton(onClick = {}) { Text("Ship without it") }
            },
        )
        StatusBand(StatusKind.Bad, "Wrong item", line = "BSR-10113-0 is not on this order - nothing was added")
    }
}
