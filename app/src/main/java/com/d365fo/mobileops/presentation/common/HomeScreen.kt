package com.d365fo.mobileops.presentation.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.d365fo.mobileops.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material3.AlertDialog
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.d365fo.mobileops.presentation.common.components.SquareTextButton as TextButton
import com.d365fo.mobileops.presentation.common.components.raised
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
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
    /** The fuller explanation, shown on a long-press - see [ModuleInfoDialog]. */
    val detail: String,
    /** What this posts in D365FO — the thing an implementer actually needs to know. */
    val d365: String,
    val icon: ImageVector,
    val route: String?,
    /**
     * Null for every tile except the four an admin can gate from the Admin Center - see
     * [HomeAccessViewModel]. Distinct from [available]: this is a *second*, independent reason
     * a built module can still be unusable, checked against the signed-in worker's own
     * permissions rather than whether the screen exists.
     */
    val workflow: com.d365fo.mobileops.domain.model.WarehouseWorkflow? = null,
) {
    val available: Boolean get() = route != null
}

/**
 * The menu: everything built, plus whatever is actually planned next - each unbuilt module
 * still gets a real tile (`route = null`), greyed out and marked "Planned" by [ModuleTileCard]
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
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.PO_RECEIVING,
    ),
    ModuleTile(
        title = "Transfer — Ship",
        summary = "Scan a transfer to send",
        detail = "Scan a transfer order, then each item and quantity leaving this warehouse. " +
            "Only lines with something still to ship are offered, and the order has to belong " +
            "to this warehouse - shipping from the wrong end is the commonest mistake here and " +
            "is refused by name rather than shown as an empty list.",
        d365 = "InventTransferTable / InventTransferLine, posted via InventTransferUpdShip",
        icon = Icons.Default.LocalShipping,
        route = Routes.TRANSFER_SHIP,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.TRANSFER_SHIP,
    ),
    ModuleTile(
        title = "Transfer — Receive",
        summary = "Scan a transfer to book in",
        detail = "Scan an arriving transfer order, then each item, quantity and put-away " +
            "location. Bounded by what was actually shipped, not by what was ordered - a line " +
            "with four of ten shipped has four to receive, and the app will not offer more.",
        d365 = "InventTransferTable / InventTransferLine, posted via InventTransferUpdReceive",
        icon = Icons.Default.Warehouse,
        route = Routes.TRANSFER_RECEIVE,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.TRANSFER_RECEIVE,
    ),
    ModuleTile(
        title = "Inventory count",
        summary = "Open a counting journal for this warehouse",
        detail = "Counting journals are created in D365FO, not on the device - this lists the " +
            "ones open for your assigned warehouse. Opening one to actually count against it " +
            "is not built yet (its lines are a separate, bulk fetch), so the list is live but " +
            "each row is read-only for now.",
        d365 = "InventJournalTable / InventJournalTransCount, list only - lines not yet fetched",
        icon = Icons.Default.Checklist,
        route = Routes.COUNTING_JOURNALS,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.INVENTORY_COUNT,
    ),
    // The former "Data enquiry" sub-menu's six rows, promoted to first-class tiles here rather
    // than sitting behind an intermediate menu screen - on explicit instruction, since a tap to
    // open a menu just to tap again was a wasted step for lookups a worker reaches for often.
    // Same order DataEnquiryScreen used to list them in; the three with route = null are exactly
    // the three that screen's own EnquiryTile list already marked not built yet.
    ModuleTile(
        title = "Location inquiry",
        summary = "Scan or type a location",
        detail = "Scan or type a warehouse and location. Answers whether it exists and " +
            "whether it can be received into right now.",
        d365 = "WarehouseLocations (OData reads only)",
        icon = Icons.Default.LocationOn,
        route = Routes.INQUIRY_LOCATION,
    ),
    ModuleTile(
        title = "PO's of today",
        summary = "POs due today in your warehouse",
        detail = "Every purchase order requested for delivery today in your warehouse.",
        d365 = "PurchaseOrderHeadersV2 (OData reads only)",
        icon = Icons.Default.Today,
        route = Routes.INQUIRY_PO_TODAY,
    ),
    ModuleTile(
        title = "PO lines",
        summary = "Scan or type a PO number",
        detail = "Scan or type a purchase order number to see its lines - ordered, received " +
            "and outstanding quantities. Read-only: nothing here starts a receipt.",
        d365 = "PurchaseOrderLinesV2 (OData reads only)",
        icon = Icons.Default.Inventory,
        route = Routes.INQUIRY_PO_LINES,
    ),
    ModuleTile(
        title = "TO's by Store",
        summary = "List of transfer orders by store",
        detail = "Not built yet. There is no list-of-orders query for transfers today - only " +
            "one-at-a-time lookups, the same ones Transfer - ship/receive already use - so " +
            "this needs a new AOS service method before it can be built.",
        d365 = "No AOS list query exists yet for transfer orders",
        icon = Icons.Default.Store,
        route = null,
    ),
    ModuleTile(
        title = "Transfer orders",
        summary = "List of transfer order headers",
        detail = "Not built yet, for the same reason as \"TO's by Store\" - no list query " +
            "exists on this deployment for transfer order headers yet.",
        d365 = "No AOS list query exists yet for transfer orders",
        icon = Icons.Default.Inventory,
        route = null,
    ),
    ModuleTile(
        title = "Picked serials",
        summary = "History of picked serial numbers",
        detail = "Not built yet. Serials captured on this device only exist while a job is " +
            "still in progress and are cleared the moment it posts, so there is nothing " +
            "durable to query yet - a history of what was picked would need a new AOS query.",
        d365 = "No durable AOS query exists yet",
        icon = Icons.Default.Fingerprint,
        route = null,
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
    accessViewModel: HomeAccessViewModel = hiltViewModel(),
) {
    var infoFor by remember { mutableStateOf<ModuleTile?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val unfinished by unfinishedViewModel.unfinished.collectAsStateWithLifecycle()
    val blockedWorkflows by accessViewModel.blockedWorkflows.collectAsStateWithLifecycle()

    infoFor?.let { module ->
        val enabled = module.workflow == null || module.workflow !in blockedWorkflows
        ModuleInfoDialog(module = module, enabled = enabled, onDismiss = { infoFor = null })
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
                    Text(stringResource(R.string.app_name_launcher), style = MaterialTheme.typography.titleMedium)
                },
                // On the far right of the bar, not beside the title - still visible at a glance
                // on the screen an operator actually looks at, not buried in Settings, but out
                // of the title's way rather than crowding it on the left. See
                // WorkerSessionRepository for where it comes from.
                actions = {
                    // Outlined pill, like the reference layout's site selector - reads as "where
                    // I am" chrome rather than loose text in the header.
                    Text(
                        text = warehouseId?.takeIf { it.isNotBlank() } ?: "No warehouse assigned",
                        style = MaterialTheme.typography.labelMedium,
                        color = WarehouseStatusColors.accent,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .border(1.dp, WarehouseStatusColors.accent, RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
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
                items(
                    unfinished,
                    key = { "${it.flowType}:${it.orderId}" },
                    contentType = { "unfinished" },
                ) { work ->
                    UnfinishedWorkCard(
                        work = work,
                        onResume = {
                            if (!unfinishedViewModel.justScanned()) {
                                onModuleSelected(unfinishedViewModel.resume(work))
                            }
                        },
                        onDiscard = { unfinishedViewModel.discard(work) },
                    )
                }
            }

            // Two columns of white cards, each with a gradient arrow bar along the bottom - the
            // reference layout's menu grid, on explicit instruction. Chunked into rows rather than
            // a lazy grid: ten tiles gain nothing from lazy grid composition, and chunking keeps
            // both cards in a row equal-width via weight(1f).
            items(MODULES.chunked(2), key = { row -> row.joinToString { it.title } }, contentType = { "module-row" }) { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                ) {
                    row.forEach { module ->
                        val enabled = module.workflow == null || module.workflow !in blockedWorkflows
                        ModuleTileCard(
                            module = module,
                            enabled = enabled,
                            onClick = {
                                if (!unfinishedViewModel.justScanned()) {
                                    if (enabled) module.route?.let(onModuleSelected)
                                }
                            },
                            onInfo = { infoFor = module },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    // An odd count leaves the last row one card short - a spacer keeps that card
                    // at the same half-width instead of stretching across the row.
                    if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * One menu card: white, rounded, icon and centred title, with a gradient bar and arrow along the
 * bottom edge - the reference layout's card, replacing the earlier solid-accent button rows.
 * An unavailable module keeps the same card but greys the bar and the content, so "not built
 * yet" still reads at a glance. Tap opens the module; long-press shows what it does (the
 * separate info button the earlier version carried is gone, per the reference).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModuleTileCard(
    module: ModuleTile,
    enabled: Boolean,
    onClick: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val usable = module.available && enabled
    val shape = RoundedCornerShape(WarehouseDimens.CardCorner)
    val contentColor = if (usable) MaterialTheme.colorScheme.onSurface else WarehouseStatusColors.text3
    val barBrush = if (usable) WarehouseStatusColors.accentGradient else WarehouseStatusColors.mutedGradient

    Surface(
        shape = shape,
        color = Color.White,
        modifier = modifier
            .heightIn(min = 144.dp)
            .raised(shape)
            .clip(shape)
            .combinedClickable(
                enabled = true,
                onClick = { if (usable) onClick() else onInfo() },
                onLongClick = onInfo,
            ),
    ) {
        Column {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 14.dp),
            ) {
                Icon(
                    imageVector = module.icon,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = if (usable) WarehouseStatusColors.accent else WarehouseStatusColors.text3,
                )
                Text(
                    text = module.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .background(barBrush),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ModuleInfoDialog(module: ModuleTile, enabled: Boolean, onDismiss: () -> Unit) {
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

                // Two independent reasons a built module can still be unusable - see
                // ModuleTile.workflow. Not built yet is checked first: an unbuilt module has
                // no workflow to be blocked on in the first place.
                if (!module.available) {
                    Text(
                        text = "This module is not built yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (!enabled) {
                    Text(
                        text = "Ask your supervisor to enable this module.",
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
                // Top, not centre - the left column is three lines tall (title, subtitle,
                // warehouse) while Discard is one. Centering it against the whole column pushed
                // it down away from the title row it belongs next to - confirmed live.
                verticalAlignment = Alignment.Top,
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
