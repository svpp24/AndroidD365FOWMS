package com.d365fo.mobileops.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.d365fo.mobileops.presentation.common.components.SquareTextButton as TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.data.repository.UnfinishedWork
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.components.MenuRow
import com.d365fo.mobileops.presentation.common.components.StatusChip
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * The operations menu — every module in the non-WMS scope, in the order a shift tends to
 * touch them: inbound, then transfers, then outbound, then stock maintenance and inquiry.
 *
 * Each card carries one short line, not a paragraph. On a 360dp handheld a three-line
 * description pushed the list down to two and a half visible modules, which defeats the
 * point of a menu. The full explanation moved behind the info button, where it costs a tap
 * instead of costing every worker screen space on every visit.
 *
 * Modules that are not built yet are shown rather than hidden, and labelled plainly. A menu
 * that silently omits half the scope makes a worker think the app is broken or that their
 * device is missing a permission.
 */
private data class ModuleTile(
    val title: String,
    /** One line on the card. Kept short enough not to wrap at 360dp. */
    val summary: String,
    /** The fuller explanation, shown only from the info button. */
    val detail: String,
    /** What this posts in D365FO — the thing an implementer actually needs to know. */
    val d365: String,
    val icon: ImageVector,
    val route: String?,
) {
    val available: Boolean get() = route != null
}

/**
 * The menu: everything built, plus whatever is actually planned next - each unbuilt module
 * still gets a real tile (`route = null`), greyed out and marked "Planned" by [ModuleCard]
 * rather than omitted, so the scope of this app is honest about where it's going, not just
 * where it already is. A worker seeing "Planned" knows the app isn't broken and the feature
 * isn't forgotten; an omitted module reads as neither. A tile moves to "Ready" the day its
 * `route` stops being null.
 */
private val MODULES = listOf(
    ModuleTile(
        title = "PO receiving",
        summary = "Scan a PO to start",
        detail = "Scan a purchase order, then an item, then the quantity, then whichever " +
            "dimensions that item actually tracks - location, batch, serial. One prompt at a " +
            "time, each one checked against D365FO before the next appears. Nothing is " +
            "downloaded in advance: every step fetches only the record it needs.",
        d365 = "PurchTable / PurchLine, posted via PurchFormLetter as a product receipt",
        icon = Icons.Default.MoveToInbox,
        route = Routes.RECEIVE_FLOW,
    ),
    ModuleTile(
        title = "Transfer — ship",
        summary = "Scan a transfer to send",
        detail = "Scan a transfer order, then each item and quantity leaving this warehouse. " +
            "Only lines with something still to ship are offered, and the order has to belong " +
            "to this warehouse - shipping from the wrong end is the commonest mistake here and " +
            "is refused by name rather than shown as an empty list.",
        d365 = "InventTransferTable / InventTransferLine, posted via InventTransferUpdShip",
        icon = Icons.Default.LocalShipping,
        route = Routes.TRANSFER_SHIP,
    ),
    ModuleTile(
        title = "Transfer — receive",
        summary = "Scan a transfer to book in",
        detail = "Scan an arriving transfer order, then each item, quantity and put-away " +
            "location. Bounded by what was actually shipped, not by what was ordered - a line " +
            "with four of ten shipped has four to receive, and the app will not offer more.",
        d365 = "InventTransferTable / InventTransferLine, posted via InventTransferUpdReceive",
        icon = Icons.Default.Warehouse,
        route = Routes.TRANSFER_RECEIVE,
    ),
    ModuleTile(
        title = "Inventory count",
        summary = "Scan a location to count",
        detail = "Scan a location (or an item, for a spot count), enter the counted quantity " +
            "for each item found there, and post the variance as a counting journal. Not built " +
            "yet - shown here, greyed out, rather than omitted, so the scope of this app is " +
            "honest about what it does and does not do yet.",
        d365 = "InventJournalTable / InventJournalTransCount, posted as a counting journal",
        icon = Icons.Default.Checklist,
        route = null,
    ),
    ModuleTile(
        title = "Data enquiry",
        summary = "Look up a location, PO or line",
        detail = "Read-only lookups: a warehouse location, purchase orders due today, and the " +
            "lines on a purchase order you already have the number for. Nothing here changes " +
            "D365FO - every screen behind this tile only ever reads.",
        d365 = "WarehouseLocations, PurchaseOrderHeadersV2 / PurchaseOrderLinesV2 (OData reads only)",
        icon = Icons.AutoMirrored.Filled.ManageSearch,
        route = Routes.DATA_ENQUIRY,
    ),
    // On-hand inquiry is deliberately not a tile here - it has its own bottom-nav tab (see
    // AppNavGraph's BOTTOM_TABS), and a tile that opens the exact same screen as a tab one
    // scroll away is the duplicate-button problem all over again, just moved into the grid.
)

@Composable
fun HomeScreen(
    /** The signed-in worker's backoffice-assigned warehouse, or null before it resolves. */
    warehouseId: String?,
    onModuleSelected: (String) -> Unit,
    /**
     * Null hides the icon entirely, rather than showing a button that does nothing - callers
     * that have no sign-out to offer (there are none today, but the parameter is optional for
     * the same reason SettingsScreen's own onSignOut is) should omit it, not wire a no-op.
     */
    onSignOut: (() -> Unit)? = null,
    unfinishedViewModel: UnfinishedWorkViewModel = hiltViewModel(),
) {
    var infoFor by remember { mutableStateOf<ModuleTile?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val unfinished by unfinishedViewModel.unfinished.collectAsStateWithLifecycle()

    infoFor?.let { module ->
        ModuleInfoDialog(module = module, onDismiss = { infoFor = null })
    }

    // A second, faster path to sign out - alongside, not instead of, the one on the Settings
    // tab. Settings still carries the full session context (assigned warehouse, posting
    // toggle); this is the one-tap version for the common case of handing the device to the
    // next worker, the same "Workload / Sign out side by side" top area the standard WMS app
    // uses. Confirmed rather than immediate: it is now one tap away from the screen an
    // operator is looking at all shift, not buried behind Settings, so an accidental tap
    // costs more than it used to.
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Captured work stays on the device and posts once you sign back in.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    onSignOut?.invoke()
                }) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("ForeFront Mobile", style = MaterialTheme.typography.titleMedium)
                },
                // On the far right of the bar, not beside the title - still visible at a glance
                // on the screen an operator actually looks at, not buried in Settings, but out
                // of the title's way rather than crowding it on the left. See
                // WorkerSessionRepository for where it comes from.
                actions = {
                    Text(
                        text = warehouseId?.takeIf { it.isNotBlank() } ?: "No warehouse assigned",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    if (onSignOut != null) {
                        IconButton(onClick = { confirmSignOut = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Logout,
                                contentDescription = "Sign out",
                            )
                        }
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                vertical = WarehouseDimens.ItemSpacing,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Unfinished work first. It is the only thing on this screen that is time-
            // sensitive: the modules will still be there tomorrow, a half-counted pallet
            // in an aisle will not.
            if (unfinished.isNotEmpty()) {
                item(key = "unfinished-header") {
                    Text(
                        text = "Continue where you left off",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    )
                }
                items(unfinished, key = { ":" }) { work ->
                    UnfinishedWorkCard(
                        work = work,
                        onResume = {
                            onModuleSelected(UnfinishedWorkViewModel.routeFor(work.flowType))
                        },
                        onDiscard = { unfinishedViewModel.discard(work) },
                    )
                }
            }

            // One continuous list, not a card per module - the WMS mobile app's own "Main Menu"
            // pattern, and a real space win on top of it: four separate 64dp+ cards with 10dp
            // gaps between them cost more height than four rows sharing one surface and a
            // divider each. A single `item` rather than `items(MODULES)` because the point of
            // lazy items - not composing rows that never scroll into view - buys nothing for a
            // menu this short, and the whole thing needs to be one visual surface.
            item(key = "module-menu") {
                Card(
                    shape = RoundedCornerShape(WarehouseDimens.CardCorner),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        MODULES.forEachIndexed { index, module ->
                            ModuleRow(
                                module = module,
                                onClick = { module.route?.let(onModuleSelected) },
                                onInfo = { infoFor = module },
                            )
                            if (index != MODULES.lastIndex) {
                                HorizontalDivider(color = WarehouseStatusColors.border)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One row: icon, title, status, a tap target to open it - the WMS mobile app's own Main Menu
 * rhythm. The summary line and the top accent stripe the card version carried are gone; a list
 * this short reads at a glance without them, and a chevron already says "tap to enter" the way
 * the stripe used to say "this one works."
 *
 * Thin wrapper over [MenuRow] - see that composable for the shared implementation. The info
 * button stays enabled even on an unbuilt module: knowing what a module *will* do is exactly
 * the question a greyed-out row provokes.
 */
@Composable
private fun ModuleRow(
    module: ModuleTile,
    onClick: () -> Unit,
    onInfo: () -> Unit,
) {
    MenuRow(
        icon = module.icon,
        title = module.title,
        available = module.available,
        onClick = onClick,
        onInfo = onInfo,
    )
}

@Composable
private fun ModuleInfoDialog(module: ModuleTile, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(module.icon, contentDescription = null) },
        title = { Text(module.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = module.detail, style = MaterialTheme.typography.bodyLarge)

                Column {
                    Text(
                        text = "In D365FO",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = module.d365,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!module.available) {
                    Text(
                        text = "This module is not built yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = WarehouseDimens.MinTouchTarget),
            ) {
                Text("Close")
            }
        },
    )
}

/**
 * One unfinished job.
 *
 * Shows the count of banked lines and how long ago, because those are the two things that
 * decide whether to resume it or throw it away: five lines from two minutes ago is the pallet
 * you are standing next to, five lines from yesterday is somebody else's shift.
 *
 * Discard is deliberately not a swipe. Swipe-to-delete on a device used in gloves, held at an
 * angle, next to a moving forklift, deletes things nobody meant to delete.
 */
@Composable
private fun UnfinishedWorkCard(
    work: UnfinishedWork,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
) {
    val amber = WarehouseStatusColors.amber
    val amberDim = WarehouseStatusColors.amberDim

    Card(
        onClick = onResume,
        shape = RoundedCornerShape(WarehouseDimens.CardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(3.dp).background(amber))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(WarehouseDimens.ScreenPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = work.orderId,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        StatusChip(text = "In progress", color = amber, dimColor = amberDim)
                    }
                    Text(
                        text = UnfinishedWorkViewModel.labelFor(work.flowType) +
                            " · " + work.lineCount +
                            (if (work.lineCount == 1) " line" else " lines") +
                            " · " + relativeTime(work.updatedAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = WarehouseStatusColors.text3,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    work.warehouseId?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = "Warehouse $it",
                            style = MaterialTheme.typography.labelMedium,
                            color = WarehouseStatusColors.text3,
                        )
                    }
                }
                TextButton(onClick = onDiscard) { Text("Discard") }
            }
        }
    }
}

/** Coarse on purpose: nobody needs "47 minutes ago", they need "this shift or not". */
private fun relativeTime(epochMs: Long): String {
    val minutes = (System.currentTimeMillis() - epochMs) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} h ago"
        else -> "${minutes / (60 * 24)} d ago"
    }
}
