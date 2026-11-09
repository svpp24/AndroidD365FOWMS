package com.d365fo.mobileops.presentation.common

import com.d365fo.mobileops.presentation.common.components.LineIcons
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.presentation.common.components.LocalFeedbackLauncher
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/*
 * Home in the 20 Oct restyle (user: "apply this design to the android app" - a reference app's
 * layout): greeting in the bar, a search field over a soft illustrated band, the menu as white
 * service cards with a coloured badge and an outlined Open button, most-used shortcuts and a
 * Report / Suggest section. Only the presentation changed - the menu, permissions and ordering
 * are HomeScreen's as before.
 */

// The scene in faint white on the accent band (21 Oct) - the lavender band read as the reference app.
private val HeroShelf = Color.White.copy(alpha = 0.13f)
private val HeroBox = Color.White.copy(alpha = 0.19f)
private val HeroBoxTeal = Color(0xFFF2B544).copy(alpha = 0.55f)
private val HeroLift = Color.White.copy(alpha = 0.24f)
private val HeroWheel = Color.White.copy(alpha = 0.34f)
private val CardShape = RoundedCornerShape(10.dp)

/**
 * "Hello Abdul" with the worker's initials - the tabs' top bar title. With [onWarehouse], the
 * warehouse code under the name, tap to change it (Home) - under, not beside: a chip there
 * broke a long name mid-word (20 Oct).
 */
@Composable
internal fun HomeGreeting(onWarehouse: (() -> Unit)? = null, viewModel: WarehouseSwitcherViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val name = session?.displayName?.trim().orEmpty()
    val first = name.split(' ').firstOrNull { it.isNotBlank() }.orEmpty()
    val initials = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(36.dp).background(WarehouseStatusColors.accent, RoundedCornerShape(10.dp)),
        ) {
            Text(initials.ifBlank { "?" }, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        Column {
            Text(
                text = greeting() + if (first.isNotBlank()) ", $first" else "",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            val code = session?.selected?.warehouseId
            if (onWarehouse != null && code != null) {
                Text(
                    text = "Warehouse $code" + if (session?.warehouses.orEmpty().size > 1) "  ·  Change" else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = WarehouseStatusColors.accent,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onWarehouse),
                )
            }
        }
    }
}

/**
 * The band under the bar (the mock's): a search field - opens Search (items, orders, journals)
 * - and the "My Dashboard" heading. No More button: Services is a tab already (20 Oct).
 */
@Composable
internal fun HomeHero(
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().heightIn(min = 140.dp).background(WarehouseStatusColors.accent)) {
        // A warehouse scene across the whole band (20 Oct): a long, sparsely stocked rack and a
        // forklift carrying a pallet - quiet shapes behind the search and "My Dashboard", not a
        // grid of boxes (the first fill read as a checkerboard).
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val r = CornerRadius(6f, 6f)
            // Floor.
            drawRect(HeroShelf, Offset(0f, h - h * 0.03f), Size(w, h * 0.03f))
            // The rack: three bays across the left two thirds, one shelf and a top beam.
            val rackRight = w * 0.62f
            val post = w * 0.014f
            val beam = h * 0.028f
            val shelfY = h * 0.52f
            val topY = h * 0.14f
            for (x in listOf(w * 0.02f, w * 0.22f, w * 0.42f, rackRight - post)) {
                drawRect(HeroShelf, Offset(x, topY), Size(post, h - topY))
            }
            drawRect(HeroShelf, Offset(w * 0.02f, topY), Size(rackRight - w * 0.02f, beam))
            drawRect(HeroShelf, Offset(w * 0.02f, shelfY), Size(rackRight - w * 0.02f, beam))
            // A few boxes, gaps between - stocked, not full.
            fun box(x: Float, bottom: Float, bw: Float, bh: Float, c: Color = HeroBox) =
                drawRoundRect(c, Offset(x, bottom - bh), Size(bw, bh), r)
            box(w * 0.05f, shelfY, w * 0.10f, h * 0.22f)
            box(w * 0.26f, shelfY, w * 0.07f, h * 0.15f, HeroBoxTeal)
            box(w * 0.46f, shelfY, w * 0.11f, h * 0.24f)
            box(w * 0.08f, h * 0.97f, w * 0.11f, h * 0.26f)
            box(w * 0.30f, h * 0.97f, w * 0.08f, h * 0.18f)
            // The forklift, facing left towards the rack.
            val base = h * 0.97f
            val fx = w * 0.70f
            val wheel = h * 0.075f
            // Mast and forks, with a pallet and box on them.
            drawRect(HeroLift, Offset(fx, base - h * 0.62f), Size(w * 0.012f, h * 0.62f - wheel))
            drawRect(HeroLift, Offset(fx - w * 0.07f, base - h * 0.16f), Size(w * 0.075f, h * 0.022f))
            drawRect(HeroShelf, Offset(fx - w * 0.075f, base - h * 0.20f), Size(w * 0.08f, h * 0.035f))
            drawRoundRect(HeroBoxTeal, Offset(fx - w * 0.07f, base - h * 0.40f), Size(w * 0.07f, h * 0.20f), r)
            // Body and counterweight.
            drawRoundRect(HeroLift, Offset(fx + w * 0.012f, base - h * 0.30f), Size(w * 0.19f, h * 0.20f), CornerRadius(10f, 10f))
            // Overhead guard and the driver's cab opening.
            val guard = HeroLift
            val stroke = w * 0.008f
            drawLine(guard, Offset(fx + w * 0.03f, base - h * 0.30f), Offset(fx + w * 0.03f, base - h * 0.62f), stroke)
            drawLine(guard, Offset(fx + w * 0.03f, base - h * 0.62f), Offset(fx + w * 0.13f, base - h * 0.62f), stroke)
            drawLine(guard, Offset(fx + w * 0.13f, base - h * 0.62f), Offset(fx + w * 0.15f, base - h * 0.30f), stroke)
            drawCircle(HeroBox, radius = h * 0.05f, center = Offset(fx + w * 0.085f, base - h * 0.38f))
            // Wheels.
            drawCircle(HeroWheel, radius = wheel, center = Offset(fx + w * 0.05f, base - wheel))
            drawCircle(HeroWheel, radius = wheel, center = Offset(fx + w * 0.17f, base - wheel))
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .clip(CardShape)
                    .background(Color.White)
                    .clickable(role = Role.Button, onClickLabel = "Search items, orders, journals", onClick = onSearch)
                    .padding(horizontal = 14.dp),
            ) {
                Icon(LineIcons.SearchThin, contentDescription = null, tint = WarehouseStatusColors.text3)
                Text("Search items, orders, journals", style = MaterialTheme.typography.bodyLarge, color = WarehouseStatusColors.text3)
            }
            Text(
                "Today",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                modifier = Modifier.heightIn(min = 38.dp).wrapContentHeight(Alignment.CenterVertically),
            )
        }
    }
}

/** One tip: a short title and a line - workers glance, they don't read (21 Oct). */
private data class Tip(val icon: androidx.compose.ui.graphics.vector.ImageVector, val tint: Color, val title: String, val text: String)

private val TIPS = listOf(
    Tip(LineIcons.Count, Color(0xFF2E7D4F), "Not on the journal?", "Scan it anyway - it's added and marked."),
    Tip(LineIcons.Search, Color(0xFF7B2FB0), "Find anything", "Search an item, PO, transfer or journal."),
    Tip(LineIcons.Store, Color(0xFFB5222D), "Quick stock check", "Red button: scan an item, see its stock."),
    Tip(LineIcons.Upload, Color(0xFF0B5563), "No signal?", "Keep working. It sends when you're back."),
    Tip(LineIcons.Activity, Color(0xFF8A5A00), "Did it post?", "Activity shows what's sent and what's waiting."),
    Tip(LineIcons.Ship, Color(0xFF0E7F74), "Whole line?", "Use Ship all outstanding - no typing."),
)

/** Tips: short cards in a row, swiped through. */
@Composable
internal fun TipsRow(modifier: Modifier = Modifier) {
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(TIPS.size) { i ->
            val tip = TIPS[i]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .width(250.dp)
                    .heightIn(min = 76.dp)
                    .clip(CardShape)
                    .background(Color.White)
                    .padding(12.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(34.dp).background(tip.tint, RoundedCornerShape(9.dp))) {
                    Icon(tip.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(tip.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                    Text(tip.text, style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.text3)
                }
            }
        }
    }
}

/** A section heading on Home - "My Services", "Your Most Used", "Report / Suggest". */
@Composable
internal fun HomeSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = modifier.padding(top = 6.dp))
}

/** The badge colour of a module or menu folder - by what it does, so a kind of work keeps its colour. */
internal fun moduleTint(module: ModuleTile): Color {
    val t = module.title.lowercase()
    return when {
        "count" in t || t.startsWith("inventory") -> Color(0xFF2E7D4F)
        "receiv" in t || "inbound" in t || t.startsWith("po ") || "po receiving" in t -> Color(0xFF1D63D6)
        "ship" in t || "outbound" in t || "transfer" in t || "to " in t || "to's" in t -> Color(0xFF0E7F74)
        "return" in t -> Color(0xFFB4441A)
        "inquir" in t || "lines" in t || "serial" in t || "location" in t || "today" in t -> Color(0xFF7B2FB0)
        "creation" in t || "management" in t -> Color(0xFF8A5A00)
        else -> WarehouseStatusColors.accent
    }
}

/**
 * One menu entry as a dashboard card: coloured badge and title, its one line, and an outlined
 * Open button along the bottom. Tap opens it; long-press shows what it does (unchanged).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ServiceCard(
    module: ModuleTile,
    enabled: Boolean,
    onClick: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val usable = module.available && enabled
    val tint = if (usable) moduleTint(module) else WarehouseStatusColors.text3
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .heightIn(min = 150.dp)
            .clip(CardShape)
            .background(Color.White)
            .combinedClickable(onClick = { if (usable) onClick() else onInfo() }, onLongClick = onInfo)
            .padding(12.dp),
    ) {
        // Badge above the title: beside it, "Inventory management" broke mid-word (20 Oct).
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp).background(tint, RoundedCornerShape(8.dp))) {
            Icon(module.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
        }
        Text(
            text = module.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (usable) MaterialTheme.colorScheme.onSurface else WarehouseStatusColors.text3,
        )
        Text(
            text = module.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = WarehouseStatusColors.text3,
            modifier = Modifier.weight(1f),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .border(1.5.dp, if (usable) WarehouseStatusColors.accent else WarehouseStatusColors.border, RoundedCornerShape(8.dp)),
        ) {
            Text(
                text = when {
                    !module.available -> "Planned"
                    !enabled -> "Not enabled"
                    else -> "Open"
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (usable) WarehouseStatusColors.accent else WarehouseStatusColors.text3,
            )
        }
    }
}

/**
 * Report / Suggest (the mock's two cards): a stock discrepancy at a location - the counting
 * team's report, which may trigger a count - when that is switched on, and feedback about the app
 * with a screenshot of Home.
 */
@Composable
internal fun ReportSuggestCards(modifier: Modifier = Modifier) {
    val launch = LocalFeedbackLauncher.current
    val view = LocalView.current
    val discrepancy = hiltViewModel<com.d365fo.mobileops.presentation.modules.cyclecounting.opportunity.DiscrepancyViewModel>()
    // The dialog only; this card opens it.
    com.d365fo.mobileops.presentation.modules.cyclecounting.opportunity.ReportDiscrepancyAction(
        locationId = null, itemId = null, showTrigger = false, viewModel = discrepancy,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        if (discrepancy.enabled) {
            ReportCard(
                icon = androidx.compose.material.icons.Icons.Outlined.ReportProblem,
                tint = Color(0xFFB4441A),
                title = "Discrepancy",
                text = "Wrong stock at a location",
                onClick = { discrepancy.open() },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        if (launch != null) {
            ReportCard(
                icon = Icons.AutoMirrored.Outlined.Chat,
                tint = Color(0xFF2E7D4F),
                title = "Share feedback",
                text = "A problem or an idea",
                onClick = { launch(runCatching { view.rootView.drawToBitmap() }.getOrNull()) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun ReportCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(CardShape)
            .background(Color.White)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = tint)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = WarehouseStatusColors.text3)
    }
}

/** "Good morning" / "Good afternoon" / "Good evening" by the device clock. */
private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 4..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}
