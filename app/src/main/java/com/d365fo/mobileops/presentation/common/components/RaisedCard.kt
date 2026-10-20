package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import androidx.compose.material3.Card as MaterialCard

/**
 * The card/tile edge: a plain 1.5dp border in the app's border colour. A "3D" version (drop
 * shadow, bevelled gradient edge, thicker bottom lip) was tried and reverted on request - just
 * the border remains. Kept as one shared modifier so cards and menu tiles stay in step.
 */
@Composable
fun Modifier.raised(@Suppress("UNUSED_PARAMETER") shape: Shape): Modifier =
    // No edge any more: white cards on the grey page, like the reference design (20 Oct restyle).
    this

/**
 * applied - same alias-import trick as [SquareButton]: files `import ...AppCard as Card` and
 *  every existing call site picks it up unchanged. Material's own elevation is forced
 * to zero so cards stay flat.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    colors: CardColors = CardDefaults.cardColors(),
    @Suppress("UNUSED_PARAMETER") elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) = MaterialCard(
    modifier = modifier.raised(shape),
    shape = shape,
    colors = colors,
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border = border,
    content = content,
)

@Composable
fun AppCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.medium,
    colors: CardColors = CardDefaults.cardColors(),
    @Suppress("UNUSED_PARAMETER") elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) = MaterialCard(
    onClick = onClick,
    modifier = modifier.raised(shape),
    enabled = enabled,
    shape = shape,
    colors = colors,
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border = border,
    interactionSource = interactionSource,
    content = content,
)
