package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.TextButton as MaterialTextButton
/** * A subtly darker version of the same colour, alpha unchanged - the bottom stop of * [SquareButton]'s fill gradient. Multiplies channels rather than blending toward black, so it * stays the same hue rather than muddying toward grey. */private fun Color.darken(amount: Float): Color =    copy(red = red * (1 - amount), green = green * (1 - amount), blue = blue * (1 - amount))


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
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = MaterialButton(
    onClick = onClick,
    modifier = if (enabled) {
        modifier.background(
            brush = Brush.verticalGradient(
                listOf(colors.containerColor, colors.containerColor.darken(0.16f)),
            ),
            shape = shape,
        )
    } else {
        modifier
    },
    enabled = enabled,
    shape = shape,
    colors = if (enabled) colors.copy(containerColor = Color.Transparent) else colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
)

@Composable
fun SquareOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(WarehouseDimens.CardCorner),
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = MaterialOutlinedButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = shape,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
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
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = MaterialTextButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = shape,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
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
    border = BorderStroke(1.dp, WarehouseStatusColors.red),
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

/** Outline-style [ConfirmButton] - see [CancelOutlinedButton]'s doc for when the outline style
 *  fits (paired with another filled button so only one reads as primary). */
@Composable
fun ConfirmOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = SquareOutlinedButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    colors = ButtonDefaults.outlinedButtonColors(contentColor = WarehouseStatusColors.green),
    border = BorderStroke(1.dp, WarehouseStatusColors.green),
    content = content,
)
