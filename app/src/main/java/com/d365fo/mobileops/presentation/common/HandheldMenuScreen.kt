package com.d365fo.mobileops.presentation.common

import com.d365fo.mobileops.presentation.common.components.LineIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.domain.model.HandheldMenu
import com.d365fo.mobileops.domain.model.MenuDefinition
import com.d365fo.mobileops.domain.model.MenuEntry
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import com.d365fo.mobileops.presentation.common.components.AppTopBar
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * The tiles of one handheld menu, in the order set up in D365FO (Mobile device menus): a
 * module's own tile, or a tile that opens a sub-menu. Modules the worker may not use (Menu
 * access) and modules without a screen in this app are left out, and so is a sub-menu with
 * nothing left in it.
 */
internal fun menuTiles(menu: HandheldMenu, menuId: String, blocked: Set<WarehouseWorkflow>): List<ModuleTile> {
    val isShown = { workflow: WarehouseWorkflow -> workflow !in blocked && tileFor(workflow) != null }
    return menu.visibleEntries(menuId, isShown).mapNotNull { entry ->
        when (entry) {
            is MenuEntry.Module -> tileFor(entry.workflow)
            is MenuEntry.SubMenu -> menu.menu(entry.menuId)?.let { subMenuTile(it, menu, isShown) }
        }
    }
}

private fun tileFor(workflow: WarehouseWorkflow): ModuleTile? = MODULES.firstOrNull { it.workflow == workflow && it.available }

private fun subMenuTile(subMenu: MenuDefinition, menu: HandheldMenu, isShown: (WarehouseWorkflow) -> Boolean): ModuleTile {
    val names = menu.visibleEntries(subMenu.id, isShown).mapNotNull { entry ->
        when (entry) {
            is MenuEntry.Module -> tileFor(entry.workflow)?.title
            is MenuEntry.SubMenu -> menu.menu(entry.menuId)?.name
        }
    }
    return ModuleTile(
        title = subMenu.name,
        // What is inside, not just how many (20 Oct restyle: "1 item" said nothing).
        summary = names.joinToString(", ").ifBlank { "Nothing here yet" },
        detail = "Opens: ${names.joinToString(", ")}.",
        d365 = "Mobile device menu ${subMenu.id} (Inventory hub > Setup > Mobile device menus)",
        icon = folderIcon(subMenu.name),
        route = Routes.handheldMenu(subMenu.id),
    )
}

/** A menu with nothing this worker may open - said plainly, so it doesn't look like a fault. */
@Composable
internal fun EmptyMenuNotice(menuMissing: Boolean = false) {
    Text(
        text = if (menuMissing) {
            "This menu is gone. Go back to Home."
        } else {
            "Nothing here for you yet - ask a supervisor."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = WarehouseStatusColors.text3,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/**
 * A sub-menu opened from Home (or from another sub-menu): its tiles in the same two-column grid,
 * with its name as the title and Back to the menu it was opened from. Shares Home's
 * [HomeAccessViewModel], so the menus and Menu access are the ones Home already has.
 */
@Composable
fun HandheldMenuScreen(
    menuId: String,
    accessViewModel: HomeAccessViewModel,
    onModuleSelected: (String) -> Unit,
    onBack: () -> Unit,
) {
    val blockedWorkflows by accessViewModel.blockedWorkflows.collectAsStateWithLifecycle()
    val handheldMenu by accessViewModel.menu.collectAsStateWithLifecycle()
    val definition = handheldMenu?.menu(menuId)
    val tiles = remember(handheldMenu, blockedWorkflows, menuId) {
        handheldMenu?.let { menuTiles(it, menuId, blockedWorkflows) }.orEmpty()
    }
    var infoFor by remember { mutableStateOf<ModuleTile?>(null) }

    infoFor?.let { tile -> ModuleInfoDialog(module = tile, enabled = true, onDismiss = { infoFor = null }) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text(definition?.name ?: "Menu") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                expandedHeight = WarehouseDimens.TopBarHeight,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = WarehouseDimens.ScreenPadding),
            contentPadding = PaddingValues(vertical = WarehouseDimens.ItemSpacing),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (tiles.isEmpty()) {
                item(key = "empty-menu") { EmptyMenuNotice(menuMissing = definition == null) }
            }
            items(tiles.chunked(2), key = { row -> row.joinToString { it.title } }, contentType = { "module-row" }) { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                ) {
                    row.forEach { tile ->
                        ServiceCard(
                            module = tile,
                            enabled = true,
                            onClick = { tile.route?.let(onModuleSelected) },
                            onInfo = { infoFor = tile },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** A menu folder's badge - the mock's icon for that kind of work, by the folder's name. */
internal fun folderIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector {
    val t = name.lowercase()
    return when {
        "inbound" in t || "receiv" in t -> LineIcons.Receive
        "outbound" in t || "ship" in t -> LineIcons.Ship
        "count" in t || "inventory" in t -> LineIcons.Count
        "inquir" in t || "lookup" in t -> LineIcons.Search
        "store" in t -> LineIcons.Store
        else -> LineIcons.Folder
    }
}
