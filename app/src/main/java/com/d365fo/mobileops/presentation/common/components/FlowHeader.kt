package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.presentation.common.theme.WarehouseType
import com.d365fo.mobileops.presentation.common.theme.breakableCode

/**
 * The top bar of every flow screen (redesign brief §2): back arrow, title, optional progress
 * ("1 of 2") and the ⋮ menu. Replaces [AppTopBar] *and* the old step trail there - the progress text and
 * the status band under it say where the worker is, so the step trail goes.
 *
 * At least 52dp, white, a hairline under it. The back arrow's touch target is
 * [WarehouseDimens.MinTouchTarget], not the brief's 44dp: the brief's own ground rule (every
 * interactive element at least 56dp) wins, so the bar is 56dp at 100%. A long title wraps and the
 * bar grows - never cut off.
 *
 * No connection icon here, unlike [AppTopBar]: on a flow screen [SendTray] says what has and
 * hasn't been sent, which is what the cloud icon only hinted at.
 *
 * @param menuItems the screen's own ⋮ items, above Send feedback - see [TopBarMenu].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    progress: String? = null,
    menuItems: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
) {
    Column(modifier = modifier.background(WarehouseStatusColors.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                .heightIn(min = 52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(WarehouseDimens.MinTouchTarget)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = WarehouseStatusColors.accent,
                )
            }
            Text(
                title.breakableCode(),
                style = WarehouseType.title,
                color = WarehouseStatusColors.ink,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .semantics { heading() },
            )
            if (progress != null) {
                Text(
                    progress,
                    style = WarehouseType.label,
                    color = WarehouseStatusColors.inkMuted,
                    // A short "1 of 2" - kept whole rather than broken across lines.
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            CompositionLocalProvider(LocalContentColor provides WarehouseStatusColors.accent) {
                TopBarMenu(onSignOut = null, items = menuItems)
            }
        }
        HorizontalDivider(color = WarehouseStatusColors.divider)
    }
}

@Preview(name = "FlowHeader", widthDp = 360, showBackground = true)
@Composable
private fun FlowHeaderPreview() = PreviewSurface { FlowHeaderSamples() }

@Preview(name = "FlowHeader - 130%", widthDp = 360, showBackground = true)
@Composable
private fun FlowHeaderPreviewLarge() = PreviewSurface(large = true) { FlowHeaderSamples() }

@Composable
internal fun FlowHeaderSamples() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowHeader(title = "Receive", onBack = {}, progress = "1 of 2", menuItems = { close ->
            DropdownMenuItem(text = { Text("Lines") }, onClick = close)
        })
        FlowHeader(title = "Transfer - Receive BSP-TO-000000043 from ABB", onBack = {}, progress = "12 of 140")
    }
}
