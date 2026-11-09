package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.data.security.DisplayScale
import com.d365fo.mobileops.presentation.common.theme.D365MobileTheme
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * The frame every redesign component's `@Preview` draws in: the real theme, on the comfort page
 * colour, with 16dp around it. [large] is the Settings 130% text size (brief Phase 2 check: every
 * preview must also hold up at 130% without truncation) - through [DisplayScale], the app's own
 * setting, rather than the preview's `fontScale`, so it scales exactly what a worker's setting
 * scales (touch targets included), not only text.
 *
 * `internal`, not private: the on-device layout test (androidTest `ComponentLayoutTest`) renders
 * the same samples through it.
 *
 * @param padded false for full-width chrome (the bottom bar), which sits edge to edge on screen
 *   rather than inside the page's 16dp margin - testing it 32dp narrower than real is a false fail.
 */
@Composable
internal fun PreviewSurface(large: Boolean = false, padded: Boolean = true, content: @Composable () -> Unit) {
    D365MobileTheme(scale = if (large) DisplayScale.EXTRA_LARGE else DisplayScale.STANDARD) {
        Box(Modifier.background(WarehouseStatusColors.pageComfort).padding(if (padded) 16.dp else 0.dp)) {
            content()
        }
    }
}
