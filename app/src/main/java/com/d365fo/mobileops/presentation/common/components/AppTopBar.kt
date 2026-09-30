package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import androidx.compose.material3.TopAppBar as MaterialTopAppBar

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
) {
    Column(modifier = modifier) {
        MaterialTopAppBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            expandedHeight = expandedHeight,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.White,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = WarehouseStatusColors.accent,
                actionIconContentColor = WarehouseStatusColors.accent,
            ),
        )
        HorizontalDivider(color = WarehouseStatusColors.border.copy(alpha = 0.5f))
    }
}
