package com.d365fo.mobileops.presentation.common.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusShapes
import com.d365fo.mobileops.presentation.common.theme.FocusType
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens

/** The Focus design's fixed sizes (brief sections 3 and 6, and the mock-ups). */
object FocusDimens {
    /** Screen side margin. */
    val side: Dp = 20.dp
    val topBar: Dp = 56.dp
    val scanPill: Dp = 60.dp
    /** Bottom buttons: 64dp, or the touch target when a larger text size makes that bigger. */
    val button: Dp get() = maxOf(64.dp, WarehouseDimens.MinTouchTarget)
    val homeRow: Dp = 64.dp
    val listRow: Dp = 60.dp
    val menuItem: Dp = 68.dp
}

/** What a [ResultBand] says: kind (colour and icon), a title and an optional detail line. */
/**
 * [autoHideMs]: the band shows a thin line that drains, then fades away after that long - for a
 * "saved" message the worker doesn't need to keep reading (user, 15 Nov 2026). Null keeps it.
 */
data class BandState(val kind: BandKind, val title: String, val detail: String? = null, val autoHideMs: Long? = null)

/** The four band kinds. Each is an icon plus words - never colour alone. */
enum class BandKind { Ok, Bad, Warn, Info }

private data class BandLook(val bg: Color, val icon: ImageVector, val iconTint: Color, val text: Color, val label: String)

private fun BandKind.look() = when (this) {
    BandKind.Ok -> BandLook(FocusColors.okBg, Icons.Filled.CheckCircle, FocusColors.okIcon, FocusColors.okText, "Done")
    BandKind.Bad -> BandLook(FocusColors.badBg, Icons.Filled.Warning, FocusColors.badIcon, FocusColors.badText, "Problem")
    BandKind.Warn -> BandLook(FocusColors.warnBg, Icons.Filled.Error, FocusColors.warnIcon, FocusColors.warnText, "Check")
    BandKind.Info -> BandLook(FocusColors.infoBg, Icons.Filled.Info, FocusColors.infoIcon, FocusColors.infoText, "Info")
}

/**
 * The result of the last scan or action: a rounded 16dp card, 20dp side margin, a 28dp icon, the
 * title and a detail line. It is the only place a flow says what happened - no dialogs, no toasts;
 * the next scan replaces it. Announced to TalkBack when it changes.
 */
@Composable
fun ResultBand(
    state: BandState,
    modifier: Modifier = Modifier,
    remaining: (() -> Float)? = null,
    /** An x at the end - Home's license-expiry band (hidden until tomorrow). */
    onDismiss: (() -> Unit)? = null,
) {
    val look = state.kind.look()
    Box(
        modifier = modifier
            .padding(horizontal = FocusDimens.side)
            .fillMaxWidth()
            .clip(FocusShapes.band)
            .background(look.bg),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(look.icon, contentDescription = look.label, tint = look.iconTint, modifier = Modifier.size(28.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(state.title, style = FocusType.bandTitle, color = look.text)
                state.detail?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = FocusType.bandDetail, color = look.text)
                }
            }
            onDismiss?.let { dismiss ->
                androidx.compose.material3.IconButton(onClick = dismiss, modifier = Modifier.size(48.dp)) {
                    Icon(androidx.compose.material.icons.Icons.Default.Close, contentDescription = "Hide until tomorrow", tint = look.text)
                }
            }
        }
        // The time left before the band goes ([BandState.autoHideMs]). Read in the draw phase only
        // (performance plan, phase 2): read here in composition, the animation recomposed the whole
        // band on every frame for as long as it showed - which is after every scan.
        remaining?.let { left ->
            val tint = look.iconTint
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .drawBehind {
                        drawRect(tint, size = size.copy(width = size.width * left().coerceIn(0f, 1f)))
                    },
            )
        }
    }
}

/**
 * [ResultBand] for a [BandState] with [BandState.autoHideMs]: the line drains, then the band fades
 * and folds away. A new state (another save) starts it again.
 */
@Composable
fun TimedResultBand(state: BandState, modifier: Modifier = Modifier) {
    val ms = state.autoHideMs ?: 0L
    var visible by androidx.compose.runtime.remember(state) { androidx.compose.runtime.mutableStateOf(true) }
    val left = androidx.compose.runtime.remember(state) { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(state) {
        left.animateTo(0f, androidx.compose.animation.core.tween(ms.toInt(), easing = androidx.compose.animation.core.LinearEasing))
        visible = false
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
    ) {
        ResultBand(state, modifier = modifier, remaining = { left.value })
    }
}

/**
 * The middle of a flow screen, centred in the space left: a caption ("LAST SCANNED", "TO
 * RECEIVE"), the code, the description, the big quantity and its unit line. When [onQuantityTap]
 * is set the number is tappable and the unit line ends with "· tap to change". In a "job opened"
 * state pass the job size as [code] / [description] and no [quantity].
 */
@Composable
fun ColumnScope.FocusCenter(
    caption: String,
    code: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    quantity: String? = null,
    quantityColor: Color = FocusColors.navy,
    unitLine: String? = null,
    onQuantityTap: (() -> Unit)? = null,
    /**
     * What the worker has to do now, in two or three words ("Scan the batch"). When set, it is the
     * biggest thing on screen (user, 17 Nov 2026: "I am not able to immediately identify what has
     * to be done") and what was already entered drops into a small card under it.
     */
    task: FocusTask? = null,
) {
    if (task != null) {
        TaskCenter(task, caption, code, modifier, description, quantity, quantityColor, unitLine, onQuantityTap)
        return
    }
    // Centred in the space left, and scrollable when the largest text size makes it taller than
    // that space - the 96sp number at 130% must never be cut (brief section 14).
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier
            .weight(1f)
            .fillMaxWidth(),
    ) {
    // Little room (a long band at the largest text size): smaller code and number, so the number
    // is still seen whole rather than scrolled half out of view.
    val compact = maxHeight < 300.dp
    val codeStyle = if (compact) FocusType.code.copy(fontSize = FocusType.code.fontSize * 0.7f, lineHeight = FocusType.code.fontSize * 0.7f) else FocusType.code
    val quantityStyle = if (compact) FocusType.bigQuantity.copy(fontSize = FocusType.bigQuantity.fontSize * 0.6f, lineHeight = FocusType.bigQuantity.fontSize * 0.6f) else FocusType.bigQuantity
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .heightIn(min = maxHeight)
            .padding(horizontal = FocusDimens.side),
    ) {
        FocusCaption(caption)
        Text(
            code.breakAfterHyphens(),
            style = codeStyle,
            color = FocusColors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        description?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = FocusType.description,
                color = FocusColors.inkSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (quantity != null) {
            val tap = onQuantityTap
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clip(FocusShapes.button)
                    .then(
                        if (tap != null) {
                            Modifier.clickable(role = Role.Button, onClickLabel = "Change quantity", onClick = tap)
                        } else {
                            Modifier
                        },
                    )
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp),
            ) {
                Text(quantity, style = quantityStyle, color = quantityColor, textAlign = TextAlign.Center)
                val unit = listOfNotNull(unitLine?.takeIf { it.isNotBlank() }, "tap to change".takeIf { tap != null })
                    .joinToString(" · ")
                if (unit.isNotEmpty()) {
                    Text(unit, style = FocusType.unitLine, color = FocusColors.caption, textAlign = TextAlign.Center)
                }
            }
        } else {
            // No number: the unit line still says what the job is ("To AAC · Delivery ...").
            unitLine?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = FocusType.unitLine, color = FocusColors.caption, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    }
}

/** The task box's words: [title] says what to do, [detail] where or which, [done] turns it green. */
data class FocusTask(
    val title: String,
    val detail: String? = null,
    val icon: ImageVector = androidx.compose.material.icons.Icons.Outlined.QrCodeScanner,
    val done: Boolean = false,
    /** Amber - done, but something is still open ("Created, not confirmed"). */
    val warn: Boolean = false,
)

@Composable
private fun ColumnScope.TaskCenter(
    task: FocusTask,
    caption: String,
    code: String,
    modifier: Modifier,
    description: String?,
    quantity: String?,
    quantityColor: Color,
    unitLine: String?,
    onQuantityTap: (() -> Unit)?,
) {
    Column(
        modifier = modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = FocusDimens.side, vertical = 8.dp),
    ) {
        FocusTaskBox(task)
        // What is already known: one quiet card, everything centred (user, 17 Nov 2026).
        val hasCard = code.isNotBlank() || !description.isNullOrBlank() || quantity != null || !unitLine.isNullOrBlank()
        if (hasCard) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .clip(FocusShapes.band)
                    .background(FocusColors.surface)
                    .border(1.dp, FocusColors.divider, FocusShapes.band)
                    .then(if (onQuantityTap != null) Modifier.clickable(role = Role.Button, onClickLabel = "Change quantity", onClick = onQuantityTap) else Modifier)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (caption.isNotBlank()) FocusCaption(caption)
                if (code.isNotBlank()) {
                    Text(code.breakAfterHyphens(), style = FocusType.rowCount, color = FocusColors.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
                description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = FocusType.rowDetail, color = FocusColors.inkSoft, textAlign = TextAlign.Center)
                }
                if (quantity != null) {
                    Text(quantity, style = FocusType.rowCount, color = quantityColor, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                }
                // "tap to change" on a line of its own - never broken across two (18 Nov 2026).
                val unit = listOfNotNull(
                    unitLine?.takeIf { it.isNotBlank() },
                    "tap to change".takeIf { quantity != null && onQuantityTap != null },
                ).joinToString("\n")
                if (unit.isNotEmpty()) {
                    Text(unit, style = FocusType.rowDetail, color = FocusColors.caption, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}


/**
 * The task box on its own - what to do now, title only - for screens that are a list (vendors,
 * items, batches, counts, lines): it sits above the list (user, 17 Nov 2026).
 */
@Composable
fun FocusTaskBox(task: FocusTask, modifier: Modifier = Modifier) {
    val boxBg = when { task.warn -> FocusColors.warnBg; task.done -> FocusColors.okBg; else -> FocusColors.taskBg }
    val iconTint = when { task.warn -> FocusColors.warnIcon; task.done -> FocusColors.okIcon; else -> FocusColors.action }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
                .fillMaxWidth()
                .clip(FocusShapes.band)
                .background(boxBg)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 16.dp, vertical = 20.dp),
        ) {
            Icon(task.icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(34.dp))
            Text(
                task.title,
                style = FocusType.taskTitle,
                color = FocusColors.navy,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
            // Title only (user, 17 Nov 2026): anything worth knowing goes in the card under it.
        }
}

/** [FocusTaskBox] above a list, with the screen's side margins. */
@Composable
fun ListTaskBox(title: String, icon: ImageVector, done: Boolean = false, warn: Boolean = false) {
    FocusTaskBox(FocusTask(title, icon = icon, done = done, warn = warn), Modifier.padding(horizontal = FocusDimens.side, vertical = 8.dp))
}

/** An upper-case, letter-spaced caption ("LAST SCANNED", "WHERE", "QUANTITY"). */
@Composable
fun FocusCaption(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = FocusType.caption, color = FocusColors.caption, modifier = modifier)
}

/**
 * Codes and document numbers break only after a hyphen, never inside a number: a zero-width space
 * after each hyphen gives the line breaker that one place.
 */
fun String.breakAfterHyphens(): String = replace("-", "-​")
