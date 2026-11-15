package com.d365fo.mobileops.presentation.common.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.FocusColors

/** How the app lays out on this screen (user, 16 Nov 2026). */
enum class TabletMode { Phone, Landscape, Portrait }

/**
 * A tablet is a screen at least [WIDE_FROM_DP] wide in both directions. In landscape it shows two
 * panes side by side; in portrait they are stacked. Phones (in either orientation) keep the
 * one-pane layout.
 */
@Composable
fun tabletMode(): TabletMode {
    val c = LocalConfiguration.current
    return when {
        c.smallestScreenWidthDp < WIDE_FROM_DP -> TabletMode.Phone
        c.screenWidthDp > c.screenHeightDp -> TabletMode.Landscape
        else -> TabletMode.Portrait
    }
}

/** Two panes side by side: a tablet in landscape. */
@Composable
fun isWideLayout(): Boolean = tabletMode() == TabletMode.Landscape

const val WIDE_FROM_DP = 600

/** The share of the width the left pane takes. */
const val LEFT_PANE_SHARE = 0.4f

/** Two panes with a hairline between them: [left] (a list, a menu) and [right] (what is open). */
@Composable
fun TwoPane(
    modifier: Modifier = Modifier,
    leftShare: Float = LEFT_PANE_SHARE,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier = modifier.fillMaxSize().background(FocusColors.page)) {
        Column(Modifier.weight(leftShare).fillMaxHeight(), content = left)
        Box(Modifier.width(1.dp).fillMaxHeight().background(FocusColors.divider))
        Column(Modifier.weight(1f - leftShare).fillMaxHeight(), content = right)
    }
}

/** One line in a flow's left pane: the item code, its name, how many. */
data class SideLine(val code: String, val name: String, val count: String)

/** A flow's lines for the left pane: a caption, then a row per line (newest first). */
@Composable
fun SideLines(caption: String, lines: List<SideLine>, empty: String) {
    FocusCaption(caption, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp))
    androidx.compose.material3.HorizontalDivider(color = FocusColors.divider)
    if (lines.isEmpty()) {
        androidx.compose.material3.Text(
            empty,
            style = com.d365fo.mobileops.presentation.common.theme.FocusType.rowDetail,
            color = FocusColors.muted,
            modifier = Modifier.padding(20.dp),
        )
        return
    }
    androidx.compose.foundation.lazy.LazyColumn {
        items(lines.size) { i ->
            val line = lines[i]
            FocusListRow(title = line.code, titleIsCode = true, state = RowState.Done, subtitle = line.name, count = line.count)
        }
    }
}
