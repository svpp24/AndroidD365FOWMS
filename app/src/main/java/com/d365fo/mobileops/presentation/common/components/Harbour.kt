package com.d365fo.mobileops.presentation.common.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The Harbour design's building blocks (9 Nov, store layout): context pills, list headers and
 * line rows in a white card, module cards with coloured icons and tags, and the bottom dock with
 * an outlined button beside a gradient one - "Hold to post" where a tap must not post by accident.
 */

private val RowShape = RoundedCornerShape(12.dp)

/** Runs edge to edge through a parent's side padding of [horizontal] - for the dock and tabs. */
fun Modifier.bleed(horizontal: androidx.compose.ui.unit.Dp): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val extra = (horizontal * 2).roundToPx()
        val p = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
        layout(constraints.maxWidth, p.height) { p.place(-horizontal.roundToPx(), 0) }
    },
)

/** A small accent pill with an icon - what a scan card is working on (PO, vendor, bin). */
@Composable
fun ContextPill(icon: ImageVector?, text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(WarehouseStatusColors.accentDim)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = WarehouseStatusColors.accent, modifier = Modifier.size(15.dp)) }
        Text(text.breakableCode(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = WarehouseStatusColors.accent)
    }
}

/** Pills that wrap onto more lines rather than running off the card. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContextPills(pills: List<Pair<ImageVector?, String>>, modifier: Modifier = Modifier) {
    if (pills.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) { pills.forEach { (icon, text) -> ContextPill(icon, text) } }
}

/** Lets a long document number break after a hyphen, never in the middle of the number. */
fun String.breakableCode(): String = replace("-", "-​")

/** [breakableCode] for files that already have another `breakableCode` in scope. */
object Breakable {
    fun String.code(): String = breakableCode()
}

/**
 * The heading over a list: a tinted chip (icon, title, count badge) and an optional accent note
 * on the right ("of 4 lines", "Newest").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListHeader(
    @Suppress("UNUSED_PARAMETER") icon: ImageVector?,
    title: String,
    count: Int? = null,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onTrailing: (() -> Unit)? = null,
) {
    // Focus design: an uppercase caption with its count, and a quiet note or action on the right.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
    ) {
        Text(
            (title + (count?.let { " · $it" } ?: "")).uppercase(),
            style = com.d365fo.mobileops.presentation.common.theme.FocusType.caption,
            color = com.d365fo.mobileops.presentation.common.theme.FocusColors.caption,
            modifier = Modifier.weight(1f),
        )
        trailing?.let {
            Text(
                it,
                style = com.d365fo.mobileops.presentation.common.theme.FocusType.rowDetail,
                color = if (onTrailing != null) com.d365fo.mobileops.presentation.common.theme.FocusColors.navy else com.d365fo.mobileops.presentation.common.theme.FocusColors.muted,
                textAlign = TextAlign.End,
                modifier = if (onTrailing != null) {
                    Modifier.heightIn(min = WarehouseDimens.MinTouchTarget).clickable(onClick = onTrailing).padding(vertical = 6.dp, horizontal = 4.dp)
                } else {
                    Modifier
                },
            )
        }
    }
}

/** A white rounded card holding [LineRow]s, with hairlines between them. */
@Composable
fun ListCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(Color.White),
        content = content,
    )
}

/** The hairline between two rows of a [ListCard]. */
@Composable
fun RowDivider() = HorizontalDivider(color = WarehouseStatusColors.overlay)

/** The round marker at the start of a row: what state the line is in. */
enum class Lead { Done, Idle, Bad, Warn, Accent }

@Composable
fun LeadDot(kind: Lead, icon: ImageVector? = null, text: String? = null) {
    val (bg, fg) = when (kind) {
        Lead.Done -> WarehouseStatusColors.greenDim to WarehouseStatusColors.green
        Lead.Idle -> WarehouseStatusColors.surface2 to WarehouseStatusColors.text3
        Lead.Bad -> WarehouseStatusColors.redDim to WarehouseStatusColors.red
        Lead.Warn -> WarehouseStatusColors.amberDim to WarehouseStatusColors.amber
        Lead.Accent -> WarehouseStatusColors.accentDim to WarehouseStatusColors.accent
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(30.dp).clip(CircleShape).background(bg)) {
        when {
            text != null -> Text(text, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = fg)
            icon != null -> Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(17.dp))
        }
    }
}

/** A status tag on a row: "Counting", "Refused", "Posted". */
@Composable
fun StatusTag(text: String, kind: Lead) {
    val (bg, fg) = when (kind) {
        Lead.Done -> WarehouseStatusColors.greenDim to WarehouseStatusColors.green
        Lead.Idle -> WarehouseStatusColors.surface2 to WarehouseStatusColors.text3
        Lead.Bad -> WarehouseStatusColors.redDim to WarehouseStatusColors.red
        Lead.Warn -> WarehouseStatusColors.amberDim to WarehouseStatusColors.amber
        Lead.Accent -> WarehouseStatusColors.accentDim to WarehouseStatusColors.accent
    }
    Text(
        text.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.3.sp,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, fg, RoundedCornerShape(12.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
/**
 * One row of a [ListCard]: a [LeadDot], an accent title (with an optional [StatusTag]), a
 * darker first line and lighter lines under it, and something on the right - a quantity or a
 * chevron.
 */
@Composable
fun LineRow(
    title: String,
    modifier: Modifier = Modifier,
    lead: (@Composable () -> Unit)? = null,
    status: (@Composable () -> Unit)? = null,
    description: String? = null,
    lines: List<String> = emptyList(),
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    // A one-line row centres its title on the icon and chevron; with more lines they sit at the top.
    val oneLine = description.isNullOrBlank() && lines.none { it.isNotBlank() } && below == null
    Row(
        verticalAlignment = if (oneLine) Alignment.CenterVertically else Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = workerClick(onClick)) else Modifier)
            .heightIn(min = WarehouseDimens.MinTouchTarget)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        lead?.invoke()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            // The tag wraps under the title when both don't fit - beside it, a narrow row broke
            // the document number in the middle of its digits. Without a tag, a plain Text: the
            // wrapping layout costs more, and long lists are mostly rows without one.
            if (status == null) {
                Text(
                    title.breakableCode(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = WarehouseStatusColors.accent,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        title.breakableCode(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = WarehouseStatusColors.accent,
                    )
                    Box(Modifier.align(Alignment.CenterVertically)) { status() }
                }
            }
            description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = WarehouseStatusColors.text2)
            }
            lines.filter { it.isNotBlank() }.forEach {
                Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Normal, color = WarehouseStatusColors.text3)
            }
            below?.invoke(this)
        }
        trailing?.invoke()
    }
}

/** A row's quantity on the right: the number large in the accent, its unit under it. */
@Composable
fun QtyTrail(quantity: String, unit: String?) {
    Column(horizontalAlignment = Alignment.End) {
        Text(quantity, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = WarehouseStatusColors.accent)
        unit?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = WarehouseStatusColors.text3)
        }
    }
}

/** The grey chevron at the end of a row that opens something. */
@Composable
fun RowChevron() = Icon(
    Icons.AutoMirrored.Filled.KeyboardArrowRight,
    contentDescription = null,
    tint = WarehouseStatusColors.text3,
)

/** A module's colour pair: the icon fill and its light tint for tags. */
data class ModuleColor(val fill: Color, val tint: Color) {
    companion object {
        val Purple = ModuleColor(Color(0xFF7C3AED), Color(0xFFEEE5FC))
        val Sienna = ModuleColor(Color(0xFFC1501E), Color(0xFFF9EEE8))
        val Green = ModuleColor(Color(0xFF15803D), Color(0xFFE1F3E8))
        val Blue = ModuleColor(Color(0xFF0274BE), Color(0xFFEAF7FD))
        val Petrol = ModuleColor(Color(0xFF0B5563), Color(0xFFDCEBEE))
        val Grey = ModuleColor(Color(0xFF8E98A6), Color(0xFFF0F2F4))
    }
}

/**
 * A module as its own white card: a coloured rounded icon, the name, and tags under it in the
 * module's colour ("3 due today", "Supervisor").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModuleCard(
    icon: ImageVector,
    color: ModuleColor,
    title: String,
    tags: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    val c = if (enabled) color else ModuleColor.Grey
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(Color.White)
            .then(
                if (onLongClick != null) {
                    Modifier.pointerInput(onClick, onLongClick) {
                        detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() })
                    }
                } else {
                    Modifier.clickable(onClick = workerClick(onClick))
                },
            )
            .heightIn(min = WarehouseDimens.MinTouchTarget + 8.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(c.fill),
        ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp)) }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = if (enabled) WarehouseStatusColors.text1 else WarehouseStatusColors.text3,
            )
            if (tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags.forEach { tag ->
                        Text(
                            tag.uppercase(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.3.sp,
                            color = c.fill,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(c.tint)
                                .border(1.dp, c.fill, RoundedCornerShape(12.dp))
                                .padding(horizontal = 7.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = WarehouseStatusColors.text1)
    }
}

/**
 * A screen's own tabs under the bar - Activity's Needs attention / Registration queue, a count
 * list's To count / Recount / Done: accent and underlined when selected, an optional red badge.
 */
@Composable
fun SegmentTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    badges: Map<Int, Int> = emptyMap(),
    /** The tab whose badge means "needs someone" - red, not the accent (Activity's first). */
    alertTab: Int? = null,
) {
    Column(modifier = modifier.fillMaxWidth().background(Color.White)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                val on = i == selected
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f).clickable(role = Role.Tab) { onSelect(i) },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.heightIn(min = WarehouseDimens.MinTouchTarget - 3.dp).padding(horizontal = 4.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Bold,
                            color = if (on) WarehouseStatusColors.accent else WarehouseStatusColors.text3,
                            textAlign = TextAlign.Center,
                        )
                        badges[i]?.takeIf { it > 0 }?.let {
                            Text(
                                "$it",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (i == alertTab) WarehouseStatusColors.red else WarehouseStatusColors.accent)
                                    .padding(horizontal = 5.dp),
                            )
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 3.dp, max = 3.dp)
                            .background(if (on) WarehouseStatusColors.accent else Color.Transparent),
                    )
                }
            }
        }
        HorizontalDivider(color = WarehouseStatusColors.overlay)
    }
}

/** The white bar pinned to the bottom of a flow: an outlined button beside a gradient one. */
@Composable
fun FlowDock(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    // Focus design: the action row on the page colour - 20dp sides, 16dp between the buttons.
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
        modifier = modifier.fillMaxWidth().background(com.d365fo.mobileops.presentation.common.theme.FocusColors.page).padding(horizontal = 20.dp, vertical = 12.dp),
        content = content,
    )
}

private val DockShape = RoundedCornerShape(12.dp)

/** The dock's secondary button: accent outline, accent label. */
@Composable
fun DockOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = com.d365fo.mobileops.presentation.common.theme.FocusColors.navy,
    icon: ImageVector? = null,
    text: String,
) {
    // Focus design: the white secondary button - 16dp corners, the label in [color].
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = WarehouseDimens.MinTouchTarget)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(com.d365fo.mobileops.presentation.common.theme.FocusShapes.button)
            .background(com.d365fo.mobileops.presentation.common.theme.FocusColors.surface)
            .border(BorderStroke(1.dp, com.d365fo.mobileops.presentation.common.theme.FocusColors.divider), com.d365fo.mobileops.presentation.common.theme.FocusShapes.button)
            .clickable(enabled = enabled, role = Role.Button, onClick = workerClick(onClick))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(20.dp)) }
        Text(text, style = com.d365fo.mobileops.presentation.common.theme.FocusType.button, color = color, textAlign = TextAlign.Center)
    }
}

/** The dock's main button: the accent gradient, white label. */
@Composable
fun GradientButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    // Focus design: the orange primary button (the name stays - every caller keeps working).
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = WarehouseDimens.MinTouchTarget)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(com.d365fo.mobileops.presentation.common.theme.FocusShapes.button)
            .background(com.d365fo.mobileops.presentation.common.theme.FocusColors.action)
            .clickable(enabled = enabled, role = Role.Button, onClick = workerClick(onClick))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides com.d365fo.mobileops.presentation.common.theme.FocusColors.surface,
            LocalTextStyle provides com.d365fo.mobileops.presentation.common.theme.FocusType.button.copy(color = com.d365fo.mobileops.presentation.common.theme.FocusColors.surface, textAlign = TextAlign.Center),
        ) { content() }
    }
}

/** How long [HoldButton] has to be held. */
const val HOLD_TO_CONFIRM_MS = 1_000

/**
 * "Hold to post": the gradient button that only fires once held for [HOLD_TO_CONFIRM_MS] - a
 * darker fill sweeps across while held, letting go early empties it. A plain tap shows a hint
 * under it instead. TalkBack's click confirms straight away.
 */
@Composable
fun HoldButton(
    label: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // One hold button in the app: the Focus one (1-second hold, progress fill, no dialog).
    com.d365fo.mobileops.presentation.common.focus.HoldToConfirmButton(label, onConfirm = onConfirm, modifier = modifier, enabled = enabled)
}
