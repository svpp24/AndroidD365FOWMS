package com.d365fo.mobileops.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.domain.model.HandheldMenu
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import com.d365fo.mobileops.presentation.common.components.AppTopBar
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * Where a module lives (30 Oct): Inbound, Outbound and Inventory management on Home, look-ups on
 * the Enquiry tab.
 */
internal enum class ModuleArea(val title: String) {
    INBOUND("Inbound"),
    OUTBOUND("Outbound"),
    INVENTORY("Inventory management"),
    ENQUIRY("Enquiry"),
}

internal fun areaOf(workflow: WarehouseWorkflow?): ModuleArea? = when (workflow) {
    WarehouseWorkflow.PO_RECEIVING, WarehouseWorkflow.TRANSFER_RECEIVE,
    WarehouseWorkflow.PO_CREATE, WarehouseWorkflow.PO_CONFIRM -> ModuleArea.INBOUND
    WarehouseWorkflow.TRANSFER_SHIP, WarehouseWorkflow.TO_CREATE, WarehouseWorkflow.PURCHASE_RETURN -> ModuleArea.OUTBOUND
    WarehouseWorkflow.INVENTORY_COUNT -> ModuleArea.INVENTORY
    WarehouseWorkflow.LOCATION_INQUIRY, WarehouseWorkflow.PO_OF_TODAY, WarehouseWorkflow.TRANSFER_ORDERS,
    WarehouseWorkflow.ITEMS_BY_CATEGORY, WarehouseWorkflow.PRICE_CHECK -> ModuleArea.ENQUIRY
    // Taken off the menu (user, 12 Nov); still in D365FO's module list, so a menu line may name them.
    WarehouseWorkflow.PO_LINES, WarehouseWorkflow.TRANSFERS_BY_STORE, WarehouseWorkflow.PICKED_SERIALS -> null
    null -> null
}

/**
 * The modules this worker may open, in order: the D365FO handheld menu's modules (folders
 * opened, in the menu's order) when there is one, else every built module. Menu access and
 * module switches ([blocked]) apply either way.
 */
internal fun visibleModules(menu: HandheldMenu?, blocked: Set<WarehouseWorkflow>): List<ModuleTile> {
    if (menu == null) return MODULES.filter { it.available && (it.workflow == null || it.workflow !in blocked) }
    val seen = mutableSetOf<String>()
    val out = mutableListOf<ModuleTile>()
    fun walk(menuId: String) {
        if (!seen.add(menuId)) return
        menuTiles(menu, menuId, blocked).forEach { tile ->
            val sub = tile.route?.takeIf { it.startsWith("menu/") }?.removePrefix("menu/")?.let { android.net.Uri.decode(it) }
            if (sub != null) walk(sub) else if (tile.available && out.none { it.title == tile.title }) out += tile
        }
    }
    walk(menu.rootId)
    return out
}

/** The modules of one area, for Home's sections and the Enquiry tab. */
internal fun modulesIn(area: ModuleArea, modules: List<ModuleTile>): List<ModuleTile> =
    modules.filter { areaOf(it.workflow) == area }

/**
 * Enquiry (redesign brief section 9): the look-ups as rows - Location inquiry, Purchase orders,
 * Price check, Items by category and Transfer orders. Scanning works here too: the
 * look-up screens take the scan.
 */
@Composable
fun ServicesScreen(
    onModuleSelected: (String) -> Unit,
    onBack: () -> Unit = {},
    accessViewModel: HomeAccessViewModel = hiltViewModel(),
    dashboardViewModel: HomeDashboardViewModel = hiltViewModel(),
) {
    val blocked by accessViewModel.blockedWorkflows.collectAsStateWithLifecycle()
    val menu by accessViewModel.menu.collectAsStateWithLifecycle()
    val figures by dashboardViewModel.figures.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { accessViewModel.refresh() }

    val enquiries = remember(menu, blocked) { modulesIn(ModuleArea.ENQUIRY, visibleModules(menu, blocked)) }

    com.d365fo.mobileops.presentation.common.focus.FocusScreen(title = "Enquiry", onBack = onBack) {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            if (enquiries.isEmpty()) {
                Text(
                    "No enquiries on your menu",
                    style = com.d365fo.mobileops.presentation.common.theme.FocusType.rowDetail,
                    color = com.d365fo.mobileops.presentation.common.theme.FocusColors.muted,
                    modifier = Modifier.padding(20.dp),
                )
            }
            // No counts here (user, 12 Nov): Home's are today's, these lists hold every open order.
            enquiries.forEach { module ->
                com.d365fo.mobileops.presentation.common.focus.FocusListRow(
                    title = module.title,
                    icon = module.icon,
                    chevron = true,
                    onClick = { module.route?.let(onModuleSelected) },
                )
            }
        }
    }
}
/**
 * A white card of modules, one row each: badge, title, one line, and a chevron - or today's count
 * for the module ([counts], e.g. "9 to ship") as a tonal marker.
 */
@Composable
internal fun ModuleListCard(
    modules: List<ModuleTile>,
    modifier: Modifier = Modifier,
    counts: Map<WarehouseWorkflow, String> = emptyMap(),
    onOpen: (ModuleTile) -> Unit,
) {
    // Each module its own card (Harbour): coloured icon, name, and a tag - today's count when
    // there is one, else what the module is for.
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        modules.forEach { module ->
            com.d365fo.mobileops.presentation.common.components.ModuleCard(
                icon = module.icon,
                color = harbourColor(module),
                title = module.title,
                // No tags (user, 9 Nov): no counts on the menus and no hints - the name says it.
                tags = emptyList(),
                onClick = { onOpen(module) },
            )
        }
    }
}

/** A module's colour in the Harbour design - by the kind of work, so it keeps its colour. */
internal fun harbourColor(module: ModuleTile): com.d365fo.mobileops.presentation.common.components.ModuleColor {
    val c = com.d365fo.mobileops.presentation.common.components.ModuleColor
    return when (module.workflow) {
        WarehouseWorkflow.PO_RECEIVING, WarehouseWorkflow.PO_CREATE, WarehouseWorkflow.PO_CONFIRM -> c.Purple
        WarehouseWorkflow.TRANSFER_SHIP, WarehouseWorkflow.TRANSFER_RECEIVE, WarehouseWorkflow.TO_CREATE,
        WarehouseWorkflow.PURCHASE_RETURN -> c.Sienna
        WarehouseWorkflow.INVENTORY_COUNT -> c.Green
        null -> c.Petrol
        else -> if (areaOf(module.workflow) == ModuleArea.ENQUIRY) c.Blue else c.Petrol
    }
}
