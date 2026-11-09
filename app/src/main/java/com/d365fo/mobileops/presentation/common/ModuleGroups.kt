package com.d365fo.mobileops.presentation.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import com.d365fo.mobileops.presentation.common.components.AppTopBar
import com.d365fo.mobileops.presentation.common.components.LineIcons
import com.d365fo.mobileops.presentation.common.components.ModuleCard
import com.d365fo.mobileops.presentation.common.components.ModuleColor
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens

/**
 * Home's main menus grouped (user, 9 Nov): purchasing, transfers and counting, one card each. A
 * group with one module the worker may use opens that module straight away; with more it opens
 * [ModuleGroupScreen], the group's modules as cards.
 */
internal enum class ModuleGroup(
    val title: String,
    val icon: ImageVector,
    val color: ModuleColor,
    val workflows: List<WarehouseWorkflow>,
) {
    PURCHASE(
        "Purchase", LineIcons.Receive, ModuleColor.Purple,
        listOf(WarehouseWorkflow.PO_RECEIVING, WarehouseWorkflow.PO_CREATE, WarehouseWorkflow.PURCHASE_RETURN),
    ),
    TRANSFERS(
        "Transfers", LineIcons.Swap, ModuleColor.Sienna,
        listOf(WarehouseWorkflow.TRANSFER_SHIP, WarehouseWorkflow.TRANSFER_RECEIVE, WarehouseWorkflow.TO_CREATE),
    ),
    COUNT("Inventory count", LineIcons.Count, ModuleColor.Green, listOf(WarehouseWorkflow.INVENTORY_COUNT)),
    ;

    /** This group's modules among [visible], in the group's own order. */
    fun modules(visible: List<ModuleTile>): List<ModuleTile> =
        workflows.mapNotNull { w -> visible.firstOrNull { it.workflow == w } }

    companion object {
        const val ROUTE_PREFIX = "group/"
        fun route(group: ModuleGroup) = ROUTE_PREFIX + group.name
    }
}

/** Today's counts per module ("3 due today", "10 to ship") - shared by Home and the group screen. */
internal fun todaysCounts(figures: HomeDashboardViewModel.Figures): Map<WarehouseWorkflow, String> = buildMap {
    figures.posDueToday?.let { put(WarehouseWorkflow.PO_RECEIVING, "$it due today") }
    figures.transfersToShip?.let { put(WarehouseWorkflow.TRANSFER_SHIP, "$it to ship") }
    figures.countsInProgress?.let { put(WarehouseWorkflow.INVENTORY_COUNT, "$it open") }
}

/** One group on Home: its card, with its modules' counts as tags (or their names). */
@Composable
internal fun ModuleGroupCard(
    group: ModuleGroup,
    modules: List<ModuleTile>,
    counts: Map<WarehouseWorkflow, String>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tags = modules.mapNotNull { m -> m.workflow?.let(counts::get) }
        .ifEmpty { if (modules.size == 1) listOf(modules[0].summary) else modules.map { it.title } }
    ModuleCard(
        icon = group.icon,
        color = group.color,
        title = if (modules.size == 1 && group != ModuleGroup.COUNT) modules[0].title else group.title,
        tags = tags,
        onClick = onOpen,
        modifier = modifier,
    )
}

/** A group's modules as cards - opened from its card on Home. */
@Composable
fun ModuleGroupScreen(
    groupId: String,
    onBack: () -> Unit,
    onModuleSelected: (String) -> Unit,
    accessViewModel: HomeAccessViewModel = hiltViewModel(),
    dashboardViewModel: HomeDashboardViewModel = hiltViewModel(),
) {
    val group = ModuleGroup.entries.firstOrNull { it.name == groupId } ?: ModuleGroup.PURCHASE
    val blocked by accessViewModel.blockedWorkflows.collectAsStateWithLifecycle()
    val menu by accessViewModel.menu.collectAsStateWithLifecycle()
    val figures by dashboardViewModel.figures.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { dashboardViewModel.refresh() }
    val modules = group.modules(visibleModules(menu, blocked))
    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text(group.title) },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = WarehouseDimens.ScreenPadding, vertical = 12.dp),
        ) {
            item {
                ModuleListCard(modules, counts = todaysCounts(figures)) { module -> module.route?.let(onModuleSelected) }
            }
        }
    }
}
