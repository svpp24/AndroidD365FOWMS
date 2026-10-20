package com.d365fo.mobileops.presentation.common

import com.d365fo.mobileops.presentation.common.components.LineIcons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AlertDialog
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.d365fo.mobileops.presentation.common.components.SquareTextButton as TextButton
import com.d365fo.mobileops.presentation.common.components.raised
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar
import com.d365fo.mobileops.presentation.common.tour.tourTarget
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
internal data class ModuleTile(
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
internal val MODULES = listOf(
    ModuleTile(
        title = "PO receiving",
        summary = "Scan a PO to start",
        detail = "Scan a purchase order, then an item, then the quantity, then whichever " +
            "dimensions that item actually tracks - location, batch, serial. One prompt at a " +
            "time, each one checked against D365FO before the next appears. Nothing is " +
            "downloaded in advance: every step fetches only the record it needs.",
        d365 = "PurchTable / PurchLine - registered (InventTransWMS_Register) by the HSMobileOps batch job or Process pending",
        icon = LineIcons.Receive,
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
        icon = LineIcons.Ship,
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
        icon = LineIcons.Store,
        route = Routes.TRANSFER_RECEIVE,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.TRANSFER_RECEIVE,
    ),
    ModuleTile(
        title = "PO creation",
        summary = "Order from a vendor - created and confirmed",
        detail = "Pick a vendor, scan the items and enter quantities. Prices and discounts are " +
            "D365FO's own (trade agreements, else the item's purchase price) and are shown " +
            "while connected. Works offline too: the order waits on the device and D365FO " +
            "prices it when it is created. Only for workers allowed it in Menu access.",
        d365 = "PurchTable / PurchLine, created by HSMobileOpsOrderCreateSvc",
        icon = LineIcons.Create,
        route = Routes.PO_CREATE,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.PO_CREATE,
    ),
    ModuleTile(
        title = "Purchase return",
        summary = "Send stock back to a vendor - posted",
        detail = "Pick the vendor and a return reason, then scan what goes back with its bin, " +
            "batch or serial and quantity. D365FO creates a return order (with the vendor's " +
            "RMA number when there is one), confirms it and posts the packing slip, so the " +
            "stock leaves at once. Works offline too - it is posted when D365FO can be " +
            "reached. Only for workers allowed it in Menu access.",
        d365 = "PurchTable (Returned order) / PurchLine, posted by HSMobileOpsOrderCreateSvc",
        icon = LineIcons.Return,
        route = Routes.PURCHASE_RETURN,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.PURCHASE_RETURN,
    ),
    ModuleTile(
        title = "TO creation",
        summary = "Move stock between your warehouses",
        detail = "Choose the From and To warehouses (both must be yours), scan items and " +
            "enter quantities. While connected the stock at From is shown per batch and " +
            "serial and more than that is refused. Works offline too - D365FO checks the " +
            "stock when the order is sent. Ship it straight after with Transfer - Ship.",
        d365 = "InventTransferTable / InventTransferLine, created by HSMobileOpsOrderCreateSvc",
        icon = LineIcons.Create,
        route = Routes.TO_CREATE,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.TO_CREATE,
    ),
    ModuleTile(
        title = "Inventory count",
        summary = "Open a counting journal for this warehouse",
        detail = "Counting journals are created in D365FO, not on the device - this lists the " +
            "ones open for your assigned warehouse. Get data downloads a journal's lines; count " +
            "by scanning items, barcodes or serial numbers, then End count sends the counted " +
            "lines back. Nothing is posted from the device.",
        d365 = "InventJournalTable / InventJournalTrans - counts imported via DMF, posted in D365FO",
        icon = LineIcons.Count,
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
        icon = LineIcons.Location,
        route = Routes.INQUIRY_LOCATION,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.LOCATION_INQUIRY,
    ),
    ModuleTile(
        title = "PO's of today",
        summary = "POs due today in your warehouse",
        detail = "Every purchase order requested for delivery today in your warehouse.",
        d365 = "PurchaseOrderHeadersV2 (OData reads only)",
        icon = LineIcons.Today,
        route = Routes.INQUIRY_PO_TODAY,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.PO_OF_TODAY,
    ),
    ModuleTile(
        title = "PO lines",
        summary = "Scan or type a PO number",
        detail = "Scan or type a purchase order number to see its lines - ordered, received " +
            "and outstanding quantities. Read-only: nothing here starts a receipt.",
        d365 = "PurchaseOrderLinesV2 (OData reads only)",
        icon = LineIcons.ListLines,
        route = Routes.INQUIRY_PO_LINES,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.PO_LINES,
    ),
    ModuleTile(
        title = "TO's by Store",
        summary = "List of transfer orders by store",
        detail = "Open transfer orders for this warehouse, grouped by the other store - what is " +
            "to ship to each store, and what each store has sent here. Tap one to ship or " +
            "receive it.",
        d365 = "InventTransferTable (HSMobileOpsTransferListSvc, reads only)",
        icon = LineIcons.Store,
        route = Routes.INQUIRY_TRANSFERS_BY_STORE,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.TRANSFERS_BY_STORE,
    ),
    ModuleTile(
        title = "Transfer orders",
        summary = "List of transfer order headers",
        detail = "Every open transfer order this warehouse can act on: still to ship from here, " +
            "or shipped here and not yet received. Search by number or store; tap one to open " +
            "Transfer - Ship or Receive on it.",
        d365 = "InventTransferTable (HSMobileOpsTransferListSvc, reads only)",
        icon = LineIcons.Swap,
        route = Routes.INQUIRY_TRANSFERS,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.TRANSFER_ORDERS,
    ),
    ModuleTile(
        title = "Picked serials",
        summary = "History of picked serial numbers",
        detail = "Serial numbers that left this warehouse in the last 7, 30 or 90 days - picked, " +
            "shipped or sold - with the order that took each one. Search by serial or item.",
        d365 = "InventTrans / InventDim (HSMobileOpsTransferListSvc, reads only)",
        icon = LineIcons.Serial,
        route = Routes.INQUIRY_PICKED_SERIALS,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.PICKED_SERIALS,
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
    /** Opens the Change warehouse screen - from the warehouse line under the title. */
    onChangeWarehouse: () -> Unit = {},
    /** Opens a bottom tab (Services from More, Activity from its widget). */
    onOpenTab: (String) -> Unit = onModuleSelected,
    unfinishedViewModel: UnfinishedWorkViewModel = hiltViewModel(),
    dashboardViewModel: HomeDashboardViewModel = hiltViewModel(),
    accessViewModel: HomeAccessViewModel = hiltViewModel(),
) {
    var infoFor by remember { mutableStateOf<ModuleTile?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val unfinished by unfinishedViewModel.unfinished.collectAsStateWithLifecycle()
    val onHoldElsewhere by unfinishedViewModel.onHoldElsewhere.collectAsStateWithLifecycle()
    val blockedWorkflows by accessViewModel.blockedWorkflows.collectAsStateWithLifecycle()
    val handheldMenu by accessViewModel.menu.collectAsStateWithLifecycle()
    // Each time Home shows: pick up Menu access changed in D365FO since the last look.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        accessViewModel.refresh()
        dashboardViewModel.refresh()
    }
    val figures by dashboardViewModel.figures.collectAsStateWithLifecycle()
    val sync by dashboardViewModel.sync.collectAsStateWithLifecycle()
    val usage = rememberModuleUsage(warehouseId)
    // Most-used first; modules this worker has no permission for are left out, planned ones last.
    // Ordered once per visit to the app, not after every tap - tiles must not move while the
    // worker goes back and forth.
    val flatModules = remember(blockedWorkflows) {
        // Home is rebuilt each time it is shown, so the order is fixed once per app run.
        val order = HomeTileOrder.titles ?: MODULES
            .sortedWith(compareBy<ModuleTile>({ !it.available }, { -usage.count(it.title) }))
            .map { it.title }
            .also { HomeTileOrder.titles = it }
        MODULES
            .filter { it.workflow == null || it.workflow !in blockedWorkflows }
            .sortedBy { order.indexOf(it.title) }
    }
    // With Mobile device menus set up in D365FO, Home is the worker's menu in its own order -
    // sub-menus and modules as the administrator placed them. Without, the plain list above.
    val modules = remember(handheldMenu, blockedWorkflows) {
        handheldMenu?.let { menu -> menuTiles(menu, menu.rootId, blockedWorkflows) }
    } ?: flatModules

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
            text = { Text("Anything you posted is still sent to D365FO. Scans you have not posted yet stay on this device for when you sign back in.") },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = { confirmSignOut = false }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
                    confirm = { com.d365fo.mobileops.presentation.common.components.CancelButton(onClick = {
                    confirmSignOut = false
                    onSignOut?.invoke()
                }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Sign out") } },
                )
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // The warehouse code on the right of the bar, at a glance; its full name is the
                // first card under the bar (WarehouseHeader), where it has room to wrap.
                // "Hello <name>" and the warehouse code (tap: change warehouse) - 20 Oct restyle.
                title = { Box(modifier = Modifier.tourTarget("warehouse")) { HomeGreeting(onWarehouse = onChangeWarehouse) } },
                // On the far right of the bar, not beside the title - still visible at a glance
                // on the screen an operator actually looks at, not buried in Settings, but out
                // of the title's way rather than crowding it on the left. See
                // WorkerSessionRepository for where it comes from.
                // Sign out in the ⋮ menu with Send feedback (feedback, 14 Oct) - still confirmed.
                onSignOut = onSignOut?.let { { confirmSignOut = true } },
                expandedHeight = WarehouseDimens.TopBarHeight,
            )
        },
    ) { padding ->
        val side = Modifier.padding(horizontal = WarehouseDimens.ScreenPadding)
        // Most opened on this device, for the shortcuts row - only modules it may open.
        // Most opened on this device first; until there is any use, the first modules it may open.
        val mostUsed = remember(blockedWorkflows, usage.version) {
            MODULES
                .filter { it.available && (it.workflow == null || it.workflow !in blockedWorkflows) }
                .sortedByDescending { usage.count(it.title) }
                .take(4)
        }
        fun allowed(w: com.d365fo.mobileops.domain.model.WarehouseWorkflow) = w !in blockedWorkflows
        fun n(v: Int?, one: String, many: String) = v?.let { if (it == 1) "1 $one" else "$it $many" } ?: "–"
        val widgets = buildList<@Composable (Modifier) -> Unit> {
            if (allowed(com.d365fo.mobileops.domain.model.WarehouseWorkflow.INVENTORY_COUNT)) add { m ->
                DashboardWidget("Inventory count", LineIcons.Count, Color(0xFF2E7D4F), "In progress",
                    n(figures.countsInProgress, "journal", "journals"), "Open", { onModuleSelected(Routes.COUNTING_JOURNALS) }, m)
            }
            if (allowed(com.d365fo.mobileops.domain.model.WarehouseWorkflow.PO_RECEIVING)) add { m ->
                DashboardWidget("PO receiving", LineIcons.Receive, Color(0xFF1D63D6), "Due today",
                    n(figures.posDueToday, "order", "orders"), "Receive", { onModuleSelected(Routes.RECEIVE_FLOW) }, m)
            }
            if (allowed(com.d365fo.mobileops.domain.model.WarehouseWorkflow.TRANSFER_SHIP)) add { m ->
                DashboardWidget("Transfers", LineIcons.Ship, Color(0xFF0E7F74), "To ship",
                    n(figures.transfersToShip, "order", "orders"), "Ship", { onModuleSelected(Routes.TRANSFER_SHIP) }, m)
            }
            add { m ->
                val failed = sync.failedCount
                val waiting = sync.pendingCount
                DashboardWidget("Activity", LineIcons.Activity, WarehouseStatusColors.accent,
                    if (failed > 0) "Refused" else "Waiting to send",
                    when { failed > 0 -> n(failed, "posting", "postings"); else -> n(waiting, "posting", "postings") },
                    "View", { onOpenTab(Routes.FAILED_SYNC) }, m)
            }
        }
        // Opens at the top: something scrolled it down on sign-in (seen 20 Oct, twice) - held at
        // the top until the worker scrolls it themself.
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        var touched by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) touched = true }
        androidx.compose.runtime.LaunchedEffect(widgets.size, unfinished.size) { if (!touched) listState.scrollToItem(0) }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "hero", contentType = "hero") {
                HomeHero(
                    onSearch = { onModuleSelected(Routes.ON_HAND) },
                )
            }
            items(widgets.chunked(2), key = { row -> "w" + widgets.indexOf(row.first()) }, contentType = { "widget-row" }) { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = side.fillMaxWidth().height(IntrinsicSize.Min)
                        .then(if (row.first() == widgets.first()) Modifier.tourTarget("tiles") else Modifier),
                ) {
                    row.forEach { widget -> widget(Modifier.weight(1f).fillMaxHeight()) }
                    if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }

            // Unfinished work first. It is the only thing on this screen that is time-
            // sensitive: the modules will still be there tomorrow, a half-counted pallet
            // in an aisle will not.
            if (unfinished.isNotEmpty()) {
                item(key = "unfinished-header") {
                    HomeSectionTitle("Continue where you left off", modifier = side)
                }
                items(
                    unfinished,
                    key = { "${it.flowType}:${it.orderId}" },
                    contentType = { "unfinished" },
                ) { work ->
                    UnfinishedWorkCard(
                        modifier = side,
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
            if (onHoldElsewhere > 0) {
                item(key = "on-hold") {
                    Text(
                        text = "$onHoldElsewhere unfinished job${if (onHoldElsewhere == 1) " is" else "s are"} on hold at " +
                            "another warehouse. Change warehouse to continue ${if (onHoldElsewhere == 1) "it" else "them"}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarehouseStatusColors.text3,
                        modifier = side.clickable(onClick = onChangeWarehouse).padding(vertical = 4.dp),
                    )
                }
            }

            // Two columns of white cards, each with a gradient arrow bar along the bottom - the
            // reference layout's menu grid, on explicit instruction. Without Mobile device menus,
            // one grid, most-used first - splitting it into Tasks and Look up was tried and
            // reverted on request. Chunked into rows rather than a lazy grid, so both cards in a
            // row stay equal-width.
            if (mostUsed.isNotEmpty()) {
                item(key = "most-used-title") { HomeSectionTitle("Your Most Used", modifier = side) }
                // (Shortcuts open the module straight away; Services has the whole menu.)
                item(key = "most-used") {
                    MostUsedRow(mostUsed) { module ->
                        if (!unfinishedViewModel.justScanned()) module.route?.let { route ->
                            usage.record(module.title)
                            onModuleSelected(route)
                        }
                    }
                }
            }
            item(key = "news-title") { HomeSectionTitle("What's New", modifier = side) }
            item(key = "news") { WhatsNewCard(modifier = side) }
            item(key = "report-title") { HomeSectionTitle("Report / Suggest", modifier = side) }
            item(key = "report") { ReportSuggestCards(modifier = side) }
        }
    }
}

@Composable
internal fun ModuleInfoDialog(module: ModuleTile, enabled: Boolean, onDismiss: () -> Unit) {
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
            com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                dismiss = null,
                confirm = { com.d365fo.mobileops.presentation.common.components.ConfirmButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Close") } },
            )
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun UnfinishedWorkCard(
    work: UnfinishedWork,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val amber = WarehouseStatusColors.amber
    val amberDim = WarehouseStatusColors.amberDim

    Card(
        onClick = onResume,
        shape = RoundedCornerShape(WarehouseDimens.CardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier.fillMaxWidth(),
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
                    // Wraps: on a narrow screen the chip moves under the order number rather
                    // than being squeezed into a column beside it.
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = work.orderId,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        StatusChip(
                            text = "In progress", color = amber, dimColor = amberDim,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
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

/**
 * How often each module was opened on this device - a per-device convenience for ordering the
 * Home tiles, nothing that needs to survive a reinstall. Reads and writes never throw.
 */
private class ModuleUsage(private val prefs: android.content.SharedPreferences?) {
    var version by mutableStateOf(0)
        private set

    fun count(title: String): Int = runCatching { prefs?.getInt(title, 0) ?: 0 }.getOrDefault(0)

    fun record(title: String) {
        runCatching { prefs?.edit()?.putInt(title, count(title) + 1)?.apply() }
        version++
    }
}

@Composable
private fun rememberModuleUsage(@Suppress("UNUSED_PARAMETER") key: String?): ModuleUsage {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember {
        ModuleUsage(runCatching { context.getSharedPreferences("home_module_usage", android.content.Context.MODE_PRIVATE) }.getOrNull())
    }
}

/** The Home tile order for this app run - see HomeScreen. */
private object HomeTileOrder {
    var titles: List<String>? = null
}
