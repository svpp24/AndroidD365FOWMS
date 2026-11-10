package com.d365fo.mobileops.presentation.common.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusShapes
import com.d365fo.mobileops.presentation.common.theme.FocusType

/**
 * Every flow screen of the Focus design (brief section 6), top to bottom: [FlowTopBar], an
 * optional [ProgressLine], the [ResultBand], the screen's [content] (normally a [FocusCenter], or a
 * list on a Lines screen) filling what is left, then the [RemainingLine], the [ScanPill] and the
 * [ActionRow].
 *
 * [overlay] is drawn over everything - the [QuantityPadSheet]. While it shows, the screen behind is
 * dimmed, as in the mock-ups.
 */
@Composable
fun FocusScreen(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    counter: String? = null,
    /** 0..1 when the document has a known total; null hides the line. */
    progress: Float? = null,
    band: BandState? = null,
    remaining: String? = null,
    scanPill: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    /** After the counter on the top bar - a flow's tools menu. */
    topBarTrailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FocusColors.page),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (overlay != null) 0.3f else 1f),
        ) {
            FlowTopBar(title = title, onBack = onBack, counter = counter, trailing = topBarTrailing)
            progress?.let { ProgressLine(it) }
            band?.let { ResultBand(it, modifier = Modifier.padding(top = 16.dp)) }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                content = content,
            )
            remaining?.let { RemainingLine(it) }
            scanPill?.let {
                Box(Modifier.padding(start = FocusDimens.side, end = FocusDimens.side, top = 8.dp)) { it() }
            }
            if (actions != null) ActionRow(content = actions) else Spacer(Modifier.height(20.dp))
        }
        overlay?.invoke(this)
    }
}

/**
 * The flow top bar: back arrow (48dp target, 56dp tall bar), the title and a right-aligned
 * counter ("1 / 2", "BIN-A1", "7 / 18"). A long title wraps; the bar grows.
 */
@Composable
fun FlowTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    counter: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FocusDimens.topBar)
            .padding(horizontal = 8.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = FocusColors.ink,
                    modifier = Modifier.size(26.dp),
                )
            }
        } else {
            Spacer(Modifier.size(12.dp))
        }
        Text(
            title.breakAfterHyphens(),
            style = FocusType.topBarTitle,
            color = FocusColors.ink,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
                .semantics { heading() },
        )
        counter?.let {
            Text(
                it,
                style = FocusType.topBarCounter,
                color = FocusColors.ink,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = 8.dp, end = 12.dp),
            )
        }
        trailing?.invoke(this)
    }
}

/** 6dp, rounded, navy on the divider colour - only when the document has a known total. */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    val fraction = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .padding(horizontal = FocusDimens.side)
            .fillMaxWidth()
            .height(6.dp)
            .clip(FocusShapes.pill)
            .background(FocusColors.divider),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(FocusShapes.pill)
                    .background(FocusColors.navy),
            )
        }
    }
}

/** "1 line left", "11 bins left", "1 bin sent" - never names an item or a bin. */
@Composable
fun RemainingLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = FocusType.remaining,
        color = FocusColors.muted,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FocusDimens.side)
            .padding(bottom = 4.dp),
    )
}

/** Up to two 64dp buttons, 16dp apart, 20dp from the edges - safe option left, action right. */
@Composable
fun ActionRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FocusDimens.side, vertical = 16.dp),
        content = content,
    )
}
