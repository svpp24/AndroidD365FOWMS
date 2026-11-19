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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.components.SquareButton
import com.d365fo.mobileops.presentation.common.components.SquareTextButton
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/** Where each tour target sits in the window, keyed by id - filled in by [tourTarget]. */
class TourTargets {
    val rects = mutableStateMapOf<String, Rect>()
}

/** Provided around the signed-in app (AppNavGraph); null elsewhere, and [tourTarget] does nothing then. */
val LocalTourTargets = compositionLocalOf<TourTargets?> { null }

/**
 * Marks this element as a stop on the navigation tour - see [AppTourOverlay]. In window
 * coordinates: the overlay subtracts its own position, so a bar above it (the demo banner) no
 * longer moves every highlight up (user, 19 Nov 2026: "pointers are misaligned").
 */
fun Modifier.tourTarget(id: String): Modifier = composed {
    val targets = LocalTourTargets.current ?: return@composed this
    onGloballyPositioned { targets.rects[id] = it.boundsInWindow() }
}

/** One stop: the elements to point at (their union; none for a centred card), and what to say. */
data class TourStep(val targets: List<String>, val title: String, val text: String)

/** The tour of the signed-in app, in the order it is shown (option A, user 19 Nov 2026). */
val NavigationTour = listOf(
    TourStep(
        targets = listOf("connection"),
        title = "Your warehouse",
        text = "Your scans book here. Tap to change it. Green ring: online, amber: offline.",
    ),
    TourStep(
        targets = listOf("row_Receive", "row_Ship", "row_Count", "row_Create"),
        title = "Your work",
        text = "Receive, ship, count and create - with what's open today.",
    ),
    TourStep(
        targets = listOf("row_Activity"),
        title = "Activity",
        text = "Anything that could not be sent shows here in red.",
    ),
    TourStep(
        targets = listOf("signout"),
        title = "End of shift",
        text = "Settings and Sign out are in this menu.",
    ),
)

/**
 * The tour itself: the screen dimmed except for the current target, ringed in orange, and a card
 * that points at it. Taps on the app underneath are swallowed while it shows, so a tap meant for
 * Next never starts a task by accident.
 */
@Composable
fun AppTourOverlay(steps: List<TourStep>, targets: TourTargets, onFinish: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val step = steps[index]
    val rect = step.targets.mapNotNull { targets.rects[it] }
        .reduceOrNull { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
        ?.translate(-origin)
    val density = LocalDensity.current
    val pad = with(density) { 6.dp.toPx() }
    val gap = with(density) { 22.dp.toPx() }
    val corner = with(density) { 16.dp.toPx() }
    val accent = FocusColors.action

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        val screenHeight = with(density) { maxHeight.toPx() }
        val below = rect != null && rect.center.y < screenHeight / 2
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            drawRect(Color(0xFF0F172A).copy(alpha = 0.72f))
            rect?.let {
                // Kept inside the screen, so the ring still shows round a part at an edge.
                val edge = 2.dp.toPx()
                val left = (it.left - pad).coerceAtLeast(edge)
                val top = (it.top - pad).coerceAtLeast(edge)
                val right = (it.right + pad).coerceAtMost(size.width - edge)
                val bottom = (it.bottom + pad).coerceAtMost(size.height - edge)
                val topLeft = Offset(left, top)
                val box = Size(right - left, bottom - top)
                drawRoundRect(Color.Transparent, topLeft, box, CornerRadius(corner), blendMode = BlendMode.Clear)
                drawRoundRect(accent, topLeft, box, CornerRadius(corner), style = Stroke(width = 3.dp.toPx()))
                // The card's pointer, under or over the ring, at the part's centre.
                val half = 12.dp.toPx()
                val cx = it.center.x.coerceIn(28.dp.toPx(), size.width - 28.dp.toPx())
                val tip = if (below) bottom + 6.dp.toPx() else top - 6.dp.toPx()
                val base = if (below) bottom + gap + 1f else top - gap - 1f
                drawPath(Path().apply { moveTo(cx - half, base); lineTo(cx + half, base); lineTo(cx, tip); close() }, Color.White)
            }
        }

        val cardModifier = when {
            rect == null -> Modifier.align(Alignment.Center)
            below -> Modifier.align(Alignment.TopCenter).offset(y = with(density) { (rect.bottom + pad + gap).toDp() })
            else -> Modifier.align(Alignment.BottomCenter).offset(y = -with(density) { (screenHeight - rect.top + pad + gap).toDp() })
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = cardModifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(step.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = FocusColors.navy)
            Text(step.text, style = MaterialTheme.typography.bodyLarge, color = FocusColors.ink)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(
                    "${index + 1} of ${steps.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = FocusColors.muted,
                    modifier = Modifier.weight(1f),
                )
                if (index < steps.lastIndex) {
                    Text(
                        "Skip",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = FocusColors.navy,
                        modifier = Modifier.clickable(onClick = onFinish).padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .background(FocusColors.navy, RoundedCornerShape(14.dp))
                        .clickable { if (index < steps.lastIndex) index++ else onFinish() }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Text(
                        if (index < steps.lastIndex) "Next" else "Done",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
        }
    }
}
