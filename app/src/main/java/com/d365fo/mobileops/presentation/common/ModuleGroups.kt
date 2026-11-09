package com.d365fo.mobileops.presentation.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import com.d365fo.mobileops.presentation.common.components.LineIcons
import com.d365fo.mobileops.presentation.common.focus.FocusListRow
import com.d365fo.mobileops.presentation.common.focus.FocusScreen
/**
 * Home's rows that open a menu (redesign brief sections 6 and 9): Receive (purchase orders,
 * transfers), Ship, Count and Create (purchase order, transfer order, return to vendor). A row
 * with one module the worker may use opens it straight away; with more, [ModuleGroupScreen].
 */
internal enum class ModuleGroup(
    val title: String,
    val icon: ImageVector,
    /** Each module and what the menu calls it. */
    val entries: List<Pair<WarehouseWorkflow, String>>,
) {
    RECEIVE(
        "Receive", LineIcons.Receive,
        listOf(WarehouseWorkflow.PO_RECEIVING to "Purchase orders", WarehouseWorkflow.TRANSFER_RECEIVE to "Transfers"),
    ),
    SHIP("Ship", LineIcons.Ship, listOf(WarehouseWorkflow.TRANSFER_SHIP to "Transfers")),
    COUNT("Count", LineIcons.Count, listOf(WarehouseWorkflow.INVENTORY_COUNT to "Count")),
    CREATE(
        "Create", LineIcons.Create,
        listOf(
            WarehouseWorkflow.PO_CREATE to "Purchase order",
            WarehouseWorkflow.TO_CREATE to "Transfer order",
            WarehouseWorkflow.PURCHASE_RETURN to "Return to vendor",
        ),
    ),
    ;

    /** This group's modules among [visible], in the group's own order. */
    fun modules(visible: List<ModuleTile>): List<ModuleTile> =
        entries.mapNotNull { (w, _) -> visible.firstOrNull { it.workflow == w } }

    fun label(module: ModuleTile): String = entries.firstOrNull { it.first == module.workflow }?.second ?: module.title

    companion object {
        const val ROUTE_PREFIX = "group/"
        fun route(group: ModuleGroup) = ROUTE_PREFIX + group.name
    }
}

/** A group's menu: one row per module, with its count where Home has one. */
@Composable
fun ModuleGroupScreen(
    groupId: String,
    onBack: () -> Unit,
    onModuleSelected: (String) -> Unit,
    accessViewModel: HomeAccessViewModel = hiltViewModel(),
    dashboardViewModel: HomeDashboardViewModel = hiltViewModel(),
) {
    val group = ModuleGroup.entries.firstOrNull { it.name == groupId } ?: ModuleGroup.RECEIVE
    val blocked by accessViewModel.blockedWorkflows.collectAsStateWithLifecycle()
    val menu by accessViewModel.menu.collectAsStateWithLifecycle()
    val figures by dashboardViewModel.figures.collectAsStateWithLifecycle()
    val modules = group.modules(visibleModules(menu, blocked))
    FocusScreen(title = group.title, onBack = onBack) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            modules.forEach { module ->
                val count = when (module.workflow) {
                    WarehouseWorkflow.PO_RECEIVING -> figures.posDueToday
                    WarehouseWorkflow.TRANSFER_RECEIVE -> figures.transfersToReceive
                    WarehouseWorkflow.TRANSFER_SHIP -> figures.transfersToShip
                    else -> null
                }
                FocusListRow(
                    title = group.label(module),
                    icon = module.icon,
                    count = count?.toString(),
                    chevron = true,
                    onClick = { module.route?.let(onModuleSelected) },
                )
            }
        }
    }
}