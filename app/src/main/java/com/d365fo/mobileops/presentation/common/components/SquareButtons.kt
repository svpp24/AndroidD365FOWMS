package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.TextButton as MaterialTextButton

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
