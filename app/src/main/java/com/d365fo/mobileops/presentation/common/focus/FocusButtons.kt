package com.d365fo.mobileops.presentation.common.focus

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.components.BarcodeGlyph
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusShapes
import com.d365fo.mobileops.presentation.common.theme.FocusType
import kotlinx.coroutines.launch

/** A second tap within this long is ignored: gloves and rugged screens often register one press as two. */
private const val DOUBLE_TAP_GUARD_MS = 600L

/** One press, one action, with the firm key tap - the same guard as the app's other buttons. */
@Composable
internal fun guardedClick(onClick: () -> Unit): () -> Unit {
    val view = LocalView.current
    val latest by rememberUpdatedState(onClick)
    var lastTap by remember { mutableLongStateOf(0L) }
    return remember(view) {
        {
            val now = SystemClock.uptimeMillis()
            if (now - lastTap >= DOUBLE_TAP_GUARD_MS) {
                lastTap = now
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                latest()
            }
        }
    }
}

/**
 * The Focus button: 64dp, 16dp corners, 18sp bold. [primary] is the orange action (decisions on
 * the right); otherwise white with navy text (the safe option, on the left). [contentColor]
 * recolours a secondary button's text and icon - Sign out is red.
 */
@Composable
fun FocusButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    contentColor: Color = if (primary) FocusColors.surface else FocusColors.navy,
) {
    val bg = if (primary) FocusColors.action else FocusColors.surface
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = FocusDimens.button)
            .alpha(if (enabled) 1f else 0.45f)
            .then(if (primary) Modifier else Modifier.shadow(2.dp, FocusShapes.button))
            .clip(FocusShapes.button)
            .background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = guardedClick(onClick))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = contentColor, modifier = Modifier.size(22.dp)) }
        Text(text, style = FocusType.button, color = contentColor, textAlign = TextAlign.Center)
    }
}

/** How long [HoldToConfirmButton] has to be held. */
const val HOLD_TO_CONFIRM_MS = 1_000

/**
 * Hold to confirm - only for what cannot be undone: End count and Post return (brief section 4.8).
 * Orange, a darker fill sweeps across while held, letting go early empties it, and only a full
 * second fires [onConfirm] - with a firm haptic. No dialog. "Hold for 1 second" sits under it.
 * TalkBack's double-tap confirms straight away (a hold is awkward with a screen reader).
 */
@Composable
fun HoldToConfirmButton(
    label: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val view = LocalView.current
    val latestConfirm by rememberUpdatedState(onConfirm)
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val fill = lerp(FocusColors.action, FocusColors.ink, 0.25f)
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FocusDimens.button)
                .alpha(if (enabled) 1f else 0.45f)
                .clip(FocusShapes.button)
                .background(FocusColors.action)
                .semantics {
                    role = Role.Button
                    stateDescription = "Hold for 1 second"
                    if (!enabled) disabled()
                    onClick(label) {
                        if (enabled) latestConfirm()
                        enabled
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            val run = scope.launch {
                                progress.animateTo(
                                    1f,
                                    tween((HOLD_TO_CONFIRM_MS * (1f - progress.value)).toInt(), easing = LinearEasing),
                                )
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                latestConfirm()
                                progress.snapTo(0f)
                            }
                            tryAwaitRelease()
                            if (run.isActive) {
                                run.cancel()
                                scope.launch { progress.animateTo(0f, tween(150)) }
                            }
                        },
                    )
                },
        ) {
            // The fill behind the label - matchParentSize, so it never stretches the button.
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.value)
                        .background(fill),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.TouchApp, contentDescription = null, tint = FocusColors.surface, modifier = Modifier.size(22.dp))
                Text(label, style = FocusType.button, color = FocusColors.surface, textAlign = TextAlign.Center)
            }
        }
        Text(
            "Hold for 1 second",
            style = FocusType.holdHint,
            color = FocusColors.caption,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * "Scan or type": the one place to type a code on a flow screen. A barcode icon, the label and a
 * keyboard icon; tapping it opens the keyboard (wired in [ScanPillField]). [onPanel] is the white
 * version on Home's navy On-hand panel.
 */
@Composable
fun ScanPill(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Scan or type",
    onPanel: Boolean = false,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FocusDimens.scanPill)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(FocusShapes.pill)
            .background(if (onPanel) FocusColors.surface else FocusColors.divider)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Type a code", onClick = guardedClick(onTap))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(
            BarcodeGlyph,
            contentDescription = null,
            tint = if (onPanel) FocusColors.navy else FocusColors.muted,
            modifier = Modifier.size(26.dp),
        )
        Text(label, style = FocusType.pill, color = FocusColors.muted, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.Keyboard, contentDescription = null, tint = FocusColors.navy, modifier = Modifier.size(24.dp))
    }
}

/** The typing look of the scan pill: white, a 3dp orange border, the text being typed in ink. */
@Composable
internal fun Modifier.typingPill(): Modifier = this
    .heightIn(min = FocusDimens.scanPill)
    .clip(FocusShapes.pill)
    .background(FocusColors.surface)
    .border(3.dp, FocusColors.action, FocusShapes.pill)
