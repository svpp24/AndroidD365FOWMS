package com.d365fo.mobileops.presentation.common.focus

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusShapes
import com.d365fo.mobileops.presentation.common.theme.FocusType
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens

/**
 * The number pad that changes the big number (brief section 4.5): a bottom sheet over the dimmed
 * screen - "QUANTITY", the number in navy, keys 1-9, delete, 0 and an orange Set. The first key
 * replaces the number shown rather than adding to it (typing 5 over 10 gives 5, not 105).
 * [allowDecimal] adds a "." key for units that take decimals - the mock-up's pad has none, and
 * today's quantity entry accepts them. Tapping the dimmed area or Back closes it unchanged.
 */
@Composable
fun BoxScope.QuantityPadSheet(
    initial: String,
    onSet: (String) -> Unit,
    onDismiss: () -> Unit,
    allowDecimal: Boolean = false,
) {
    var entry by remember(initial) { mutableStateOf(initial) }
    var replaceNext by remember(initial) { mutableStateOf(initial.isNotEmpty()) }
    fun key(edit: (String) -> String) {
        val base = if (replaceNext) "" else entry
        replaceNext = false
        entry = edit(base).take(12)
    }
    BackHandler(onBack = onDismiss)
    // The dimmed screen behind: tap closes.
    Box(
        Modifier
            .matchParentSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    )
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .shadow(16.dp, FocusShapes.sheet)
            .clip(FocusShapes.sheet)
            .background(FocusColors.surface)
            // Taps inside the sheet must not reach the dimmed area behind it.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(FocusDimens.side),
    ) {
        FocusCaption("Quantity")
        Text(
            entry.ifEmpty { "0" },
            style = FocusType.padNumber,
            color = if (entry.isEmpty()) FocusColors.caption else FocusColors.navy,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
        val canSet = entry.isNotEmpty() && entry.trimEnd('.').isNotEmpty()
        val rows = buildList {
            add(listOf("1", "2", "3"))
            add(listOf("4", "5", "6"))
            add(listOf("7", "8", "9"))
            add(if (allowDecimal) listOf(".", "0", "⌫") else listOf("⌫", "0", "Set"))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { k ->
                        PadKey(
                            label = k,
                            enabled = k != "Set" || canSet,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                when (k) {
                                    "Set" -> onSet(entry.trimEnd('.'))
                                    "⌫" -> { replaceNext = false; entry = entry.dropLast(1) }
                                    "." -> key { if (it.contains('.')) it else it.ifEmpty { "0" } + "." }
                                    else -> key { it + k }
                                }
                            },
                        )
                    }
                }
            }
            if (allowDecimal) {
                FocusButton(
                    text = "Set",
                    onClick = { onSet(entry.trimEnd('.')) },
                    primary = true,
                    enabled = canSet,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PadKey(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val set = label == "Set"
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = WarehouseDimens.MinTouchTarget)
            .clip(RoundedCornerShape(12.dp))
            .background(if (set) FocusColors.action else FocusColors.padKey)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        if (label == "⌫") {
            Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete digit", tint = FocusColors.ink)
        } else {
            Text(
                label,
                style = FocusType.padKey,
                color = when {
                    set && !enabled -> FocusColors.surface.copy(alpha = 0.6f)
                    set -> FocusColors.surface
                    else -> FocusColors.ink
                },
            )
        }
    }
}

/** What a [FocusListRow] says about its line: done (green tick), open (grey circle), warn, or plain. */
enum class RowState { Done, Open, Warn, Plain }

/**
 * A list row (Lines screens, open counts, enquiries): an icon coloured by [state], the title (in
 * the code font when [titleIsCode]), an optional subtitle, an optional count on the right ("1/1",
 * "12", or a word) and an optional chevron. 60dp minimum, divider underneath. Nothing truncates.
 */
@Composable
fun FocusListRow(
    title: String,
    modifier: Modifier = Modifier,
    state: RowState = RowState.Plain,
    icon: ImageVector? = null,
    subtitle: String? = null,
    count: String? = null,
    countColor: Color = FocusColors.muted,
    titleIsCode: Boolean = false,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val (shownIcon, tint) = when (state) {
        RowState.Done -> (icon ?: Icons.Filled.CheckCircle) to FocusColors.okIcon
        RowState.Open -> (icon ?: Icons.Filled.RadioButtonUnchecked) to FocusColors.iconMuted
        RowState.Warn -> (icon ?: Icons.Filled.Warning) to FocusColors.warnIcon
        RowState.Plain -> icon to FocusColors.navy
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = guardedClick(onClick)) else Modifier)
                .heightIn(min = FocusDimens.listRow)
                .padding(horizontal = FocusDimens.side, vertical = 10.dp),
        ) {
            shownIcon?.let { Icon(it, contentDescription = state.label(), tint = tint, modifier = Modifier.size(26.dp)) }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title.breakAfterHyphens(),
                    style = if (titleIsCode) FocusType.rowCode else FocusType.rowTitle,
                    color = FocusColors.ink,
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = FocusType.rowDetail, color = FocusColors.muted)
                }
            }
            count?.takeIf { it.isNotBlank() }?.let {
                val numeric = it.matches(Regex("^[0-9/ .]+$"))
                Text(it, style = if (numeric) FocusType.rowCount else FocusType.rowWord, color = countColor)
            }
            if (chevron) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = FocusColors.iconMuted, modifier = Modifier.size(22.dp))
            }
        }
        HorizontalDivider(color = FocusColors.divider)
    }
}

private fun RowState.label(): String? = when (this) {
    RowState.Done -> "Done"
    RowState.Open -> "Open"
    RowState.Warn -> "Check"
    RowState.Plain -> null
}

/** One item of a [FocusMenu]: an icon, a label, and red for Sign out. */
data class FocusMenuItem(val icon: ImageVector, val label: String, val color: Color? = null, val onClick: () -> Unit)

/** The hamburger: navy lines; open, a navy square with a white X. 48dp. */
@Composable
fun HamburgerButton(open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (open) FocusColors.navy else Color.Transparent)
            .clickable(role = Role.Button, onClickLabel = if (open) "Close menu" else "Open menu", onClick = guardedClick(onClick)),
    ) {
        Icon(
            if (open) Icons.Filled.Close else Icons.Filled.Menu,
            contentDescription = if (open) "Close menu" else "Menu",
            tint = if (open) FocusColors.surface else FocusColors.navy,
            modifier = Modifier.size(26.dp),
        )
    }
}

/**
 * The hamburger's dropdown: the screen dims, a 250dp white card under the top right corner lists
 * [items], 68dp each. Tapping outside or Back closes it. Place it at the root of the screen.
 */
@Composable
fun FocusMenu(open: Boolean, onDismiss: () -> Unit, items: List<FocusMenuItem>) {
    if (!open) return
    Popup(onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(FocusColors.scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 60.dp, end = 16.dp)
                    .width(250.dp)
                    .shadow(8.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(FocusColors.surface),
            ) {
                items.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider(color = FocusColors.divider)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                onDismiss()
                                item.onClick()
                            }
                            .heightIn(min = FocusDimens.menuItem)
                            .padding(horizontal = 16.dp),
                    ) {
                        Icon(item.icon, contentDescription = null, tint = item.color ?: FocusColors.navy, modifier = Modifier.size(26.dp))
                        Text(item.label, style = FocusType.menuItem, color = item.color ?: FocusColors.ink)
                    }
                }
            }
        }
    }
}

/**
 * Home's On-hand panel: a navy 24dp card - package icon, "On-hand", "How many, and where?" - and
 * the white scan pill "Scan or type item".
 */
@Composable
fun HomeOnHandPanel(modifier: Modifier = Modifier, icon: ImageVector, onSubmit: ((String) -> Unit)? = null) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(FocusShapes.panel)
            .background(FocusColors.navy)
            .padding(FocusDimens.side),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = FocusColors.surface, modifier = Modifier.size(40.dp))
            Column {
                Text("On-hand", style = FocusType.panelTitle, color = FocusColors.surface)
                Text("How many, and where?", style = FocusType.panelSubtitle, color = FocusColors.navyText)
            }
        }
        // Typing an item here is a scan: Home routes it to On-hand like a trigger scan.
        ScanPillField(label = "Scan or type item", onPanel = true, onSubmit = onSubmit, modifier = Modifier.padding(top = 16.dp))
    }
}

/**
 * A Home row: 64dp, icon, title, the count on the right in the code font (red for Activity's
 * failed sends), chevron, divider. No badges.
 */
@Composable
fun HomeRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: String? = null,
    countColor: Color = FocusColors.muted,
    iconColor: Color = FocusColors.navy,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = guardedClick(onClick))
                .heightIn(min = FocusDimens.homeRow)
                .padding(horizontal = FocusDimens.side, vertical = 8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(28.dp))
            Text(title, style = FocusType.homeRow, color = FocusColors.ink, modifier = Modifier.weight(1f))
            count?.takeIf { it.isNotBlank() }?.let { Text(it, style = FocusType.rowCount, color = countColor) }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = FocusColors.iconMuted, modifier = Modifier.size(22.dp))
        }
        HorizontalDivider(color = FocusColors.divider)
    }
}
