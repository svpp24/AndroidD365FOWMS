package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.presentation.common.theme.WarehouseType

/**
 * The four actions every flow screen has, in the order of the handheld's F1-F4 keys (redesign
 * brief §5). The key mapping itself lives in the activity's key handling (a later phase); this is
 * only what the buttons show.
 */
enum class FlowAction(val label: String, val key: String, val icon: ImageVector) {
    Back("Back", "F1", Icons.AutoMirrored.Filled.ArrowBack),
    Lines("Lines", "F2", Icons.AutoMirrored.Filled.List),
    Undo("Undo", "F3", Icons.AutoMirrored.Filled.Undo),
    Keypad("Keypad", "F4", Icons.Filled.Dialpad),
}

/**
 * The fixed bottom bar of every flow screen, in place of [com.d365fo.mobileops.presentation
 * .common.AppBottomBar] there (redesign brief §2): **Back · Lines · Undo · Keypad**, four equal
 * buttons, each an icon, a label and its hardware key.
 *
 * At least 64dp, and taller when a larger text size needs it - the labels wrap rather than being
 * cut. [highlighted] is the one action the screen suggests right now (Keypad after a scan whose
 * quantity may need changing, say): orange text on a light orange tint. A disabled action stays
 * in its place, faded, so the four buttons never move under a gloved thumb.
 */
@Composable
fun FlowActionBar(
    onAction: (FlowAction) -> Unit,
    modifier: Modifier = Modifier,
    highlighted: FlowAction? = null,
    enabled: Set<FlowAction> = FlowAction.entries.toSet(),
) {
    Column(modifier = modifier.fillMaxWidth().background(WarehouseStatusColors.surface).navigationBarsPadding()) {
        HorizontalDivider(color = WarehouseStatusColors.divider)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .heightIn(min = 64.dp)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FlowAction.entries.forEach { action ->
                ActionButton(
                    action = action,
                    highlighted = action == highlighted,
                    enabled = action in enabled,
                    onClick = { onAction(action) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    action: FlowAction,
    highlighted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The darker orange for text, not [WarehouseStatusColors.action] itself: #EA6A12 on the
    // light orange tint is about 2.8:1, under the brief's own 4.5:1 floor; this is about 5:1.
    val color = if (highlighted) WarehouseStatusColors.actionDark else WarehouseStatusColors.ink
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlighted) WarehouseStatusColors.actionDim else WarehouseStatusColors.surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "${action.label}, key ${action.key}" }
            .alpha(if (enabled) 1f else 0.38f)
            .padding(vertical = 4.dp),
    ) {
        Icon(action.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Text(action.label, style = WarehouseType.label, color = color, textAlign = TextAlign.Center)
        Text(action.key, style = WarehouseType.keyHint, color = WarehouseStatusColors.inkMuted, textAlign = TextAlign.Center)
    }
}

@Preview(name = "FlowActionBar", widthDp = 360, showBackground = true)
@Composable
private fun FlowActionBarPreview() = PreviewSurface { FlowActionBarSamples() }

@Preview(name = "FlowActionBar - 130%", widthDp = 360, showBackground = true)
@Composable
private fun FlowActionBarPreviewLarge() = PreviewSurface(large = true) { FlowActionBarSamples() }

@Composable
internal fun FlowActionBarSamples() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowActionBar(onAction = {})
        FlowActionBar(onAction = {}, highlighted = FlowAction.Keypad, enabled = setOf(FlowAction.Back, FlowAction.Lines, FlowAction.Keypad))
    }
}
