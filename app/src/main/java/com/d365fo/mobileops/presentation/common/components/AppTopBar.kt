package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * Every screen's top bar: white, dark title, accent-coloured back/action icons and a hairline
 * underneath - restyled from the earlier solid accent-dark fill on request, to match the
 * reference layout (a light header with a coloured back arrow and title, coloured content
 * sitting below it rather than the header itself carrying the colour).
 *
 * A drop-in for `androidx.compose.material3.TopAppBar` rather than a change at each of the
 * dozen call sites across this app: every one of them already used the same four parameters
 * (`title`, `expandedHeight`, `navigationIcon`, `actions`), so - same fix as
 * [SquareButton]/[SquareOutlinedButton]/[SquareTextButton] already apply to plain Material3
 * buttons - importing this under the alias `import ...AppTopBar as TopAppBar` makes every
 * existing call site pick up the new look unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    expandedHeight: Dp = Dp.Unspecified,
    /** Shown right of the title, before the connection icon - Home's warehouse code. */
    status: @Composable RowScope.() -> Unit = {},
    /** Home's Sign out, in the ⋮ menu with Send feedback (feedback, 14 Oct). */
    onSignOut: (() -> Unit)? = null,
    /** The screen's own items in the ⋮ menu, above Send feedback - see [TopBarMenu]. */
    menuItems: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
) {
    Column(modifier = modifier.background(Color.White)) {
        // Not Material3's TopAppBar: that one has a fixed height, so a title that wraps was cut
        // off. [expandedHeight] is the bar's minimum here, and a long title grows it instead of
        // being truncated. Spacing follows Material's: 4dp at both ends, the title 16dp from the
        // start, or 4dp after a navigation icon's 48dp button.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                .heightIn(min = if (expandedHeight == Dp.Unspecified) 64.dp else expandedHeight)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides WarehouseStatusColors.accent) {
                NavigationSlot(navigationIcon)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                    .semantics { heading() },
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                    LocalTextStyle provides MaterialTheme.typography.titleLarge,
                ) {
                    title()
                }
            }
            CompositionLocalProvider(LocalContentColor provides WarehouseStatusColors.accent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    status()
                    // Online/offline at a glance on every screen - see ConnectionIcon.
                    LocalSyncStatus.current?.let { ConnectionIcon(it) }
                    actions()
                    // Send feedback (with this screen's screenshot) and Sign out - see TopBarMenu.
                    TopBarMenu(onSignOut, menuItems)
                }
            }
        }
        HorizontalDivider(color = WarehouseStatusColors.border.copy(alpha = 0.5f))
    }
}

/** The navigation icon, or 8dp of space when there is none - so the title sits 16dp in. */
@Composable
private fun NavigationSlot(content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = placeables.maxOfOrNull { it.width }?.takeIf { it > 0 } ?: 8.dp.roundToPx()
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.forEach { it.placeRelative(0, (height - it.height) / 2) }
        }
    }
}
