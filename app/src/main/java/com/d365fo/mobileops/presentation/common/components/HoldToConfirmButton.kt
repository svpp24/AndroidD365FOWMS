package com.d365fo.mobileops.presentation.common.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.presentation.common.theme.WarehouseType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the button has to be held (brief §2). */
const val HOLD_TO_CONFIRM_MS = 1_200

/** How long the "keep holding" hint stays after a plain tap. */
private const val HOLD_HINT_MS = 2_500L

/**
 * The one confirm left in the redesign (brief §3): only for what Undo can't take back - End
 * count, Post return, ending a transfer early. Replaces an "Are you sure?" dialog with a press
 * that can't happen by accident: the orange fills left to right over [HOLD_TO_CONFIRM_MS] while
 * held, letting go early empties it again, and only a full bar fires [onConfirm], with a firm
 * haptic so a gloved hand feels it went.
 *
 * A plain tap does nothing but show the hint under the button ("Keep holding until the bar is
 * full"), so the worker learns the gesture instead of wondering why nothing happened.
 *
 * TalkBack: double-tap-and-hold is awkward with a screen reader, so the accessibility click
 * confirms straight away. The label itself says it is final ("Hold to post return").
 *
 * 64dp ([WarehouseDimens.StepperButton], which grows with the text size). White on
 * [WarehouseStatusColors.action] is about 3.1:1, so the label is 20sp bold - WCAG's "large text",
 * where 3:1 is the floor.
 */
@Composable
fun HoldToConfirmButton(
    label: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hint: String = "Keep holding until the bar is full",
) {
    val view = LocalView.current
    val latestConfirm by rememberUpdatedState(onConfirm)
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // Bumped on every plain tap; the hint shows for HOLD_HINT_MS after the latest one.
    var hintTap by remember { mutableIntStateOf(0) }
    var hintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(hintTap) {
        if (hintTap == 0) return@LaunchedEffect
        hintVisible = true
        delay(HOLD_HINT_MS)
        hintVisible = false
    }

    val shape = RoundedCornerShape(14.dp)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = WarehouseDimens.StepperButton)
                // Before the background, so a disabled button's orange fades too, not only its label.
                .alpha(if (enabled) 1f else 0.45f)
                .clip(shape)
                .background(WarehouseStatusColors.action)
                .semantics {
                    role = Role.Button
                    stateDescription = "Hold to confirm"
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
                            // Fires the moment the bar is full, while still held - the haptic is
                            // the signal to let go, not something felt after lifting the finger.
                            val fill = scope.launch {
                                progress.animateTo(
                                    1f,
                                    tween(durationMillis = (HOLD_TO_CONFIRM_MS * (1f - progress.value)).toInt(), easing = LinearEasing),
                                )
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                latestConfirm()
                                progress.snapTo(0f)
                            }
                            val released = tryAwaitRelease()
                            if (fill.isActive) {
                                val reached = progress.value
                                fill.cancel()
                                // A plain tap rather than an early let-go: say how it works.
                                if (released && reached < 0.2f) hintTap++
                                scope.launch { progress.animateTo(0f, tween(150)) }
                            }
                        },
                    )
                },
        ) {
            // The fill, drawn behind the label. matchParentSize, not fillMaxHeight: it must take the
            // button's height from the label, never stretch the button to the screen's.
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.value)
                        .background(WarehouseStatusColors.actionDark),
                )
            }
            Text(
                label,
                style = WarehouseType.title,
                fontWeight = FontWeight.Bold,
                color = WarehouseStatusColors.surface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        if (hintVisible) {
            Text(
                hint,
                style = WarehouseType.label,
                color = WarehouseStatusColors.inkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview(name = "HoldToConfirmButton", widthDp = 360, showBackground = true)
@Composable
private fun HoldToConfirmButtonPreview() = PreviewSurface { HoldToConfirmSamples() }

@Preview(name = "HoldToConfirmButton - 130%", widthDp = 360, showBackground = true)
@Composable
private fun HoldToConfirmButtonPreviewLarge() = PreviewSurface(large = true) { HoldToConfirmSamples() }

@Composable
internal fun HoldToConfirmSamples() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HoldToConfirmButton(label = "Hold to end count", onConfirm = {})
        HoldToConfirmButton(label = "Hold to post return", onConfirm = {})
        HoldToConfirmButton(label = "Hold to end this transfer early", onConfirm = {}, enabled = false)
    }
}
