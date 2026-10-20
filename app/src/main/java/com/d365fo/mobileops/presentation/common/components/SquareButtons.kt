package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.TextButton as MaterialTextButton
/** * A subtly darker version of the same colour, alpha unchanged - the bottom stop of * [SquareButton]'s fill gradient. Multiplies channels rather than blending toward black, so it * stays the same hue rather than muddying toward grey. */private fun Color.darken(amount: Float): Color =    copy(red = red * (1 - amount), green = green * (1 - amount), blue = blue * (1 - amount))


/**
 * Every button here is built for a warehouse worker: often gloved, often moving, in a noisy and
 * sometimes poorly lit aisle, glancing at the screen rather than reading it.
 *
 * - **At least [WarehouseDimens.MinTouchTarget] tall** (56dp, more at a larger display scale),
 *   not Material's 40dp - a gloved fingertip is wider than a bare one.
 * - **A bold 16sp label**, centred, never truncated - readable at arm's length.
 * - **A firm tap** (the keyboard-key haptic), so a gloved worker who can't feel the glass, in a
 *   space too loud to hear a click, still knows the press registered.
 * - **One tap per press**: a second tap within [DOUBLE_TAP_GUARD_MS] is ignored. Gloves and
 *   rugged screens often register one press as two, which would otherwise submit a receipt or
 *   confirm a count twice. The keypad and +/- steppers are their own controls and stay fast.
 */
private const val DOUBLE_TAP_GUARD_MS = 600L

@Composable
private fun workerClick(onClick: () -> Unit): () -> Unit {
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

@Composable
private fun workerLabel(content: @Composable RowScope.() -> Unit): @Composable RowScope.() -> Unit {
    val base = MaterialTheme.typography.labelLarge
    val style = base.copy(
        // labelLarge is 14sp; scaled from it so the worker's display-size setting still applies.
        fontSize = base.fontSize * (16f / 14f),
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    return { ProvideTextStyle(style) { content() } }
}

private val WorkerContentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

/**
 * Square drop-ins for Material3's `Button`/`OutlinedButton`/`TextButton`.
 *
 * `MaterialTheme(shapes = ...)` - see [com.d365fo.mobileops.presentation.common.theme.D365MobileTheme]
 * - does not reach these three. Confirmed by reading the library source directly
 * (`ButtonDefaults.shape` -> `FilledButtonTokens.ContainerShape` -> `ShapeKeyTokens.CornerFull`
 * -> a literal `CircleShape`, in `androidx.compose.material3.tokens.ShapeTokens`): a filled
 * button's default corner shape is a hardcoded constant, not a reference into the `Shapes` class
 * our theme configures, so setting every token on that class to zero - which does correctly
 * square every *other* default-shaped component - has no effect on a plain `Button`. Confirmed
 * the other direction too: live on device, a `Button` built from that theme still rendered as a
 * fully rounded pill.
 *
 * Every call site in this app imports `androidx.compose.material3.Button` and expects a square
 * one, so the fix is here rather than at each of the 35-odd call sites across eleven files:
 * import `SquareButton` under the alias `Button` (`import ...SquareButton as Button`) and every
 * existing call compiles unchanged, now square. `shape` is still a real parameter a call site can
 * override if a future screen genuinely wants a rounded one - this only changes the default.
 */
/**
 * Square, and filled with a subtle top-to-bottom gradient rather than [colors]' own flat
 * `containerColor` - real depth on the one button style that's always a primary call to action,
 * the same treatment a reference WMS app's own primary buttons use. Square corners and a
 * gradient fill are independent choices; keeping the corners is the identity this app already
 * committed to (see this file's own class doc above), adding the gradient is what was missing.
 *
 * Only applied while [enabled] - a disabled button keeps [colors]' own flat (dimmed)
 * `disabledContainerColor` unchanged, so "this can't be tapped" still reads as flat/muted rather
 * than gaining a gradient that would make it look interactive.
 *
 * Derived from whatever [colors] already carries, not a hardcoded blue - a call site that passes
 * a custom `containerColor` (an error-red confirm button, a success-green one) gets its own
 * gradient built from that same colour, not the accent's.
 */
@Composable
fun SquareButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(WarehouseDimens.CardCorner),
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = WorkerContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = MaterialButton(
    onClick = workerClick(onClick),
    modifier = if (enabled) {
        modifier.defaultMinSize(minHeight = WarehouseDimens.MinTouchTarget).background(
            brush = Brush.verticalGradient(
                listOf(colors.containerColor, colors.containerColor.darken(0.16f)),
            ),
            shape = shape,
        )
    } else {
        modifier.defaultMinSize(minHeight = WarehouseDimens.MinTouchTarget)
    },
    enabled = enabled,
    shape = shape,
    colors = if (enabled) colors.copy(containerColor = Color.Transparent) else colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = workerLabel(content),
)

@Composable
fun SquareOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(WarehouseDimens.CardCorner),
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    // 2dp in the accent, not Material's 1dp grey - an outline must still read as a button
    // under poor warehouse lighting.
    border: BorderStroke? = BorderStroke(
        2.dp,
        if (enabled) WarehouseStatusColors.accent else WarehouseStatusColors.accent.copy(alpha = 0.38f),
    ),
    contentPadding: PaddingValues = WorkerContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = MaterialOutlinedButton(
    onClick = workerClick(onClick),
    modifier = modifier.defaultMinSize(minHeight = WarehouseDimens.MinTouchTarget),
    enabled = enabled,
    shape = shape,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = workerLabel(content),
)

@Composable
fun SquareTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(WarehouseDimens.CardCorner),
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = WorkerContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = MaterialTextButton(
    onClick = workerClick(onClick),
    modifier = modifier.defaultMinSize(minHeight = WarehouseDimens.MinTouchTarget),
    enabled = enabled,
    shape = shape,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = workerLabel(content),
)

/**
 * Reject/dismiss half of a Cancel-or-OK pair, coloured red on request - deliberately not the
 * selected accent palette, unlike almost every other button in this app. A worker deciding
 * whether to discard a count or back out of a destructive confirmation reads "red = stop" the
 * same way regardless of which colour a supervisor picked for the rest of the app; letting the
 * palette choice quietly recolour this one too would be the one place a colour change could
 * change what a button *means*, not just how it looks.
 */
@Composable
fun CancelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = SquareButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    colors = ButtonDefaults.buttonColors(containerColor = WarehouseStatusColors.red),
    content = content,
)

/** Outline-style [CancelButton] - see its doc. Border and text are red rather than filled, for a
 *  cancel action next to a filled [ConfirmButton] where only one of the pair should read as the
 *  heavier, primary action. */
@Composable
fun CancelOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = SquareOutlinedButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    colors = ButtonDefaults.outlinedButtonColors(contentColor = WarehouseStatusColors.red),
    border = BorderStroke(2.dp, WarehouseStatusColors.red),
    content = content,
)

/** Confirm/OK half of a Cancel-or-OK pair, coloured green - see [CancelButton]'s doc for why
 *  this pair alone stays fixed rather than following the selected accent palette. */
@Composable
fun ConfirmButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = SquareButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    colors = ButtonDefaults.buttonColors(containerColor = WarehouseStatusColors.green),
    content = content,
)


/**
 * A dialog's actions as two equal, full-width buttons - the action left, Cancel right - in an
 * AlertDialog's confirmButton slot (leave dismissButton empty). The default slot buttons sit small
 * at the right edge, easy to miss with a glove; the counting quantity dialog already used this.
 */
@Composable
fun DialogButtonRow(
    dismiss: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)?,
    confirm: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        // The action first, Cancel on the right (feedback, 10 Oct).
        confirm()
        dismiss?.invoke(this)
    }
}
