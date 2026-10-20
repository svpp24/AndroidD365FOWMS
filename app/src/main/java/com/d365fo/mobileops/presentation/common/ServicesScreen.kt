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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import com.d365fo.mobileops.presentation.common.components.AppTopBar
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/** One accordion group on Services: a menu folder (or a fixed group) and what it opens. */
private data class ServiceGroup(val head: ModuleTile, val children: List<ModuleTile>)

/**
 * Services (20 Oct restyle, the mock's "All Services"): every menu entry the worker may open,
 * as white accordion cards - a folder opens to its modules in place. The D365FO handheld menu
 * when there is one, else the modules grouped by kind of work. Menu access applies as on Home.
 */
@Composable
fun ServicesScreen(
    onModuleSelected: (String) -> Unit,
    accessViewModel: HomeAccessViewModel = hiltViewModel(),
) {
    val blocked by accessViewModel.blockedWorkflows.collectAsStateWithLifecycle()
    val menu by accessViewModel.menu.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { accessViewModel.refresh() }

    val groups = remember(menu, blocked) {
        val m = menu
        if (m != null) {
            menuTiles(m, m.rootId, blocked).map { tile ->
                val id = tile.route?.takeIf { it.startsWith("menu/") }?.removePrefix("menu/")?.let { android.net.Uri.decode(it) }
                ServiceGroup(tile, if (id != null) menuTiles(m, id, blocked) else emptyList())
            }
        } else {
            flatGroups(blocked)
        }
    }
    // One group open at a time, like the reference app (video, 20 Oct).
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { AppTopBar(title = { HomeGreeting() }, expandedHeight = WarehouseDimens.TopBarHeight) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "title") { HomeSectionTitle("All Services") }
            items(groups, key = { it.head.title }) { group ->
                val expandable = group.children.isNotEmpty()
                val expanded = group.head.title == open
                Column(modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(10.dp))) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                if (expandable) {
                                    open = if (expanded) null else group.head.title
                                } else {
                                    group.head.route?.let(onModuleSelected)
                                }
                            }
                            .padding(14.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(32.dp).background(moduleTint(group.head), RoundedCornerShape(8.dp)),
                        ) {
                            Icon(group.head.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(group.head.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(group.head.summary, style = MaterialTheme.typography.bodyMedium, color = WarehouseStatusColors.text3)
                        }
                        Icon(
                            when {
                                !expandable -> Icons.AutoMirrored.Filled.KeyboardArrowRight
                                expanded -> Icons.Default.KeyboardArrowUp
                                else -> Icons.Default.KeyboardArrowDown
                            },
                            contentDescription = if (expandable) (if (expanded) "Collapse" else "Expand") else null,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    // The group's tasks in a light inset panel inside the card, divided, sliding open.
                    androidx.compose.animation.AnimatedVisibility(visible = expandable && expanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                                .background(Color(0xFFF2F3F7), RoundedCornerShape(8.dp)),
                        ) {
                            group.children.forEachIndexed { i, child ->
                                if (i > 0) HorizontalDivider(color = WarehouseStatusColors.border.copy(alpha = 0.45f), modifier = Modifier.padding(horizontal = 12.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = WarehouseDimens.MinTouchTarget)
                                        .clickable(role = Role.Button) { child.route?.let(onModuleSelected) }
                                        .padding(horizontal = 14.dp),
                                ) {
                                    Text(child.title, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Without D365FO handheld menus: the built modules grouped by kind of work. */
private fun flatGroups(blocked: Set<WarehouseWorkflow>): List<ServiceGroup> {
    val usable = MODULES.filter { it.available && (it.workflow == null || it.workflow !in blocked) }
    fun pick(vararg titles: String) = usable.filter { it.title in titles }
    val groups = listOf(
        "Inbound" to pick("PO receiving", "Transfer — Receive", "PO creation"),
        "Outbound" to pick("Transfer — Ship", "TO creation", "Purchase return"),
        "Inventory count" to pick("Inventory count"),
        "Inquiries" to pick("Location inquiry", "PO's of today", "PO lines", "TO's by Store", "Transfer orders", "Picked serials"),
    )
    return groups.filter { it.second.isNotEmpty() }.map { (name, children) ->
        ServiceGroup(
            ModuleTile(
                title = name,
                summary = children.joinToString(", ") { it.title },
                detail = "",
                d365 = "",
                icon = folderIcon(name),
                route = null,
            ),
            children,
        )
    }
}
