package com.d365fo.mobileops.presentation.common.tour

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.components.SquareButton
import com.d365fo.mobileops.presentation.common.components.SquareTextButton
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/** Where each tour target sits on screen, keyed by id - filled in by [tourTarget]. */
class TourTargets {
    val rects = mutableStateMapOf<String, Rect>()
}

/** Provided around the signed-in app (AppNavGraph); null elsewhere, and [tourTarget] does nothing then. */
val LocalTourTargets = compositionLocalOf<TourTargets?> { null }

/** Marks this element as a stop on the navigation tour - see [AppTourOverlay]. */
fun Modifier.tourTarget(id: String): Modifier = composed {
    val targets = LocalTourTargets.current ?: return@composed this
    onGloballyPositioned { targets.rects[id] = it.boundsInRoot() }
}

/** One stop: the element to point at ([target], or none for a centred card), and what to say. */
data class TourStep(val target: String?, val title: String, val text: String)

/** The tour of the signed-in app, in the order it is shown. */
val NavigationTour = listOf(
    TourStep(
        target = null,
        title = "A quick look around",
        text = "Six stops. Next, or Skip.",
    ),
    TourStep(
        target = "warehouse",
        title = "Your warehouse",
        text = "Your scans book here. Tap to change.",
    ),
    TourStep(
        target = "tiles",
        title = "Your work",
        text = "Receive, ship, count, create - with what is open today."
    ),
    TourStep(
        target = "connection",
        title = "Online or offline",
        text = "Green: online. Amber: offline - keep working.",
    ),
    TourStep(
        target = "tab_activity",
        title = "Activity",
        text = "Sends that need you show here in red.",
    ),
    TourStep(
        target = "tab_onhand",
        title = "On-hand",
        text = "How many, and where - for any item.",
    ),
    TourStep(
        target = "signout",
        title = "End of shift",
        text = "Settings and Sign out are in this menu.",
    ),
)

/**
 * The tour itself: the screen dimmed except for the current target, and a card next to it.
 * Taps on the app underneath are swallowed while it shows, so a tap meant for Next never starts
 * a task by accident.
 */
@Composable
fun AppTourOverlay(steps: List<TourStep>, targets: TourTargets, onFinish: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    val step = steps[index]
    val rect = step.target?.let { targets.rects[it] }
    val density = LocalDensity.current
    val pad = with(density) { 6.dp.toPx() }
    val corner = with(density) { WarehouseDimens.CardCorner.toPx() }
    val small = with(density) { 72.dp.toPx() }
    val accent = WarehouseStatusColors.accent

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            drawRect(Color.Black.copy(alpha = 0.62f))
            rect?.let {
                if (it.maxDimension < small) {
                    // An icon: a ring round it, so it does not run into the status bar.
                    val radius = it.maxDimension / 2 + pad / 2
                    drawCircle(Color.Transparent, radius, it.center, blendMode = BlendMode.Clear)
                    drawCircle(accent, radius, it.center, style = Stroke(width = 3.dp.toPx()))
                } else {
                    // Kept inside the screen, so the ring still shows round a bottom tab.
                    val edge = 2.dp.toPx()
                    val left = (it.left - pad).coerceAtLeast(edge)
                    val top = (it.top - pad).coerceAtLeast(edge)
                    val right = (it.right + pad).coerceAtMost(this.size.width - edge)
                    val bottom = (it.bottom + pad).coerceAtMost(this.size.height - edge)
                    val topLeft = Offset(left, top)
                    val size = Size(right - left, bottom - top)
                    drawRoundRect(Color.Transparent, topLeft, size, CornerRadius(corner), blendMode = BlendMode.Clear)
                    drawRoundRect(accent, topLeft, size, CornerRadius(corner), style = Stroke(width = 3.dp.toPx()))
                }
            }
        }

        // Below the target when it is in the top half of the screen, above it otherwise.
        val screenHeight = with(density) { maxHeight.toPx() }
        val cardModifier = when {
            rect == null -> Modifier.align(Alignment.Center)
            rect.center.y < screenHeight / 2 ->
                Modifier.align(Alignment.TopCenter).offset(y = with(density) { (rect.bottom + pad * 3).toDp() })
            else ->
                Modifier.align(Alignment.BottomCenter).offset(y = -with(density) { (screenHeight - rect.top + pad * 3).toDp() })
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = cardModifier
                .padding(horizontal = WarehouseDimens.ScreenPadding)
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(WarehouseDimens.CardCorner))
                .border(2.dp, accent, RoundedCornerShape(WarehouseDimens.CardCorner))
                .padding(16.dp),
        ) {
            Text(step.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = accent)
            Text(step.text, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${index + 1} of ${steps.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = WarehouseStatusColors.text3,
                    modifier = Modifier.weight(1f),
                )
                if (index < steps.lastIndex) {
                    SquareTextButton(onClick = onFinish) { Text("Skip") }
                }
                Box(modifier = Modifier.size(8.dp))
                SquareButton(onClick = { if (index < steps.lastIndex) index++ else onFinish() }) {
                    Text(if (index < steps.lastIndex) "Next" else "Done")
                }
            }
        }
    }
}
