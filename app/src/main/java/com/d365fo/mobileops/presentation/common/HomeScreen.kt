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
        summary = "Receive a PO",
        detail = "Scan the PO, then each item and quantity.",
        d365 = "PurchTable / PurchLine - registered (InventTransWMS_Register) by the HSMobileOps batch job or Process pending",
        icon = LineIcons.Receive,
        route = Routes.RECEIVE_FLOW,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.PO_RECEIVING,
    ),
    ModuleTile(
        title = "Transfer — Ship",
        summary = "Ship a transfer",
        detail = "Scan the transfer, then each item and quantity.",
        d365 = "InventTransferTable / InventTransferLine, posted via InventTransferUpdShip",
        icon = LineIcons.Ship,
        route = Routes.TRANSFER_SHIP,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.TRANSFER_SHIP,
    ),
    ModuleTile(
        title = "Transfer — Receive",
        summary = "Receive a transfer",
        detail = "Scan the transfer, then each item, quantity and location.",
        d365 = "InventTransferTable / InventTransferLine, posted via InventTransferUpdReceive",
        icon = LineIcons.Store,
        route = Routes.TRANSFER_RECEIVE,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.TRANSFER_RECEIVE,
    ),
    ModuleTile(
        title = "PO creation",
        summary = "Order from a vendor",
        detail = "Pick a vendor, scan items, enter quantities.",
        d365 = "PurchTable / PurchLine, created by HSMobileOpsOrderCreateSvc",
        icon = LineIcons.Create,
        route = Routes.PO_CREATE,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.PO_CREATE,
    ),
    ModuleTile(
        title = "Purchase return",
        summary = "Return to a vendor",
        detail = "Pick a vendor and reason, scan what goes back.",
        d365 = "PurchTable (Returned order) / PurchLine, posted by HSMobileOpsOrderCreateSvc",
        icon = LineIcons.Return,
        route = Routes.PURCHASE_RETURN,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.PURCHASE_RETURN,
    ),
    ModuleTile(
        title = "TO creation",
        summary = "Move stock between warehouses",
        detail = "Pick From and To, scan items, enter quantities.",
        d365 = "InventTransferTable / InventTransferLine, created by HSMobileOpsOrderCreateSvc",
        icon = LineIcons.Create,
        route = Routes.TO_CREATE,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.TO_CREATE,
    ),
    ModuleTile(
        title = "Inventory count",
        summary = "Store, cycle and wall-to-wall counts",
        detail = "Store count needs no journal; cycle and wall-to-wall count a journal.",
        d365 = "InventJournalTable / InventJournalTrans - counts imported via DMF, posted in D365FO; store counts via the Stock count control center",
        icon = LineIcons.Count,
        route = Routes.COUNTING_HUB,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.INVENTORY_COUNT,
    ),
    // The former "Data enquiry" sub-menu's six rows, promoted to first-class tiles here rather
    // than sitting behind an intermediate menu screen - on explicit instruction, since a tap to
    // open a menu just to tap again was a wasted step for lookups a worker reaches for often.
    // Same order DataEnquiryScreen used to list them in; the three with route = null are exactly
    // the three that screen's own EnquiryTile list already marked not built yet.
    ModuleTile(
        title = "Location inquiry",
        summary = "Check a location",
        detail = "Scan a location to see what is on it, by category.",
        d365 = "WarehouseLocations (OData reads only)",
        icon = LineIcons.Location,
        route = Routes.INQUIRY_LOCATION,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.LOCATION_INQUIRY,
    ),
    ModuleTile(
        title = "Purchase orders",
        summary = "Open POs here",
        detail = "Every open purchase order of this store, soonest delivery first.",
        d365 = "PurchTable (HSMobileOpsPurchListSvc, reads only)",
        icon = LineIcons.Today,
        route = Routes.INQUIRY_PO_TODAY,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.PO_OF_TODAY,
    ),
    ModuleTile(
        title = "Price check",
        summary = "What it sells for",
        detail = "Scan an item, enter quantity and unit: the store's price, as the price simulator gives it.",
        d365 = "Commerce price simulator (RetailPricingSimulator, via HSMobileOpsPriceCheckSvc); writes nothing",
        icon = LineIcons.Search,
        route = Routes.INQUIRY_PRICE,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.PRICE_CHECK,
    ),
    ModuleTile(
        title = "Items by category",
        summary = "Browse items",
        detail = "Categories, their items, and each item's details and barcodes.",
        d365 = "The device's item list (HSMobileOpsSkuPageSvc), with the retail product hierarchy",
        icon = LineIcons.ListLines,
        route = Routes.INQUIRY_ITEMS,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.ITEMS_BY_CATEGORY,
    ),
    ModuleTile(
        title = "Transfer orders",
        summary = "Open transfers",
        detail = "To ship from here or to receive here.",
        d365 = "InventTransferTable (HSMobileOpsTransferListSvc, reads only)",
        icon = LineIcons.Swap,
        route = Routes.INQUIRY_TRANSFERS,
        workflow = com.d365fo.mobileops.domain.model.WarehouseWorkflow.TRANSFER_ORDERS,
    ),
    // On-hand inquiry is deliberately not a tile here - it has its own bottom-nav tab (see
    // AppNavGraph's BOTTOM_TABS), and a tile that opens the exact same screen as a tab one
    // scroll away is the duplicate-button problem all over again, just moved into the grid.
)

private const val UNFINISHED_SHOWN = 3

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
    /** Failed sends - Activity's red count, hidden when 0. */
    failedCount: Int = 0,
    online: Boolean = true,
    onSettings: () -> Unit = {},
    /** A scan on Home opened a process: navigate there and hand it the scan. */
    onScanOpen: (HomeScanViewModel.Open) -> Unit = { onModuleSelected(it.route) },
    dashboardViewModel: HomeDashboardViewModel = hiltViewModel(),
    unfinishedViewModel: UnfinishedWorkViewModel = hiltViewModel(),
    accessViewModel: HomeAccessViewModel = hiltViewModel(),
) {
    var infoFor by remember { mutableStateOf<ModuleTile?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val unfinished by unfinishedViewModel.unfinished.collectAsStateWithLifecycle()
    val blockedWorkflows by accessViewModel.blockedWorkflows.collectAsStateWithLifecycle()
    val handheldMenu by accessViewModel.menu.collectAsStateWithLifecycle()
    // Each time Home shows: pick up Menu access changed in D365FO since the last look.
    val figures by dashboardViewModel.figures.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        accessViewModel.refresh()
        dashboardViewModel.refresh()
    }
    val usage = rememberModuleUsage(warehouseId)
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
            text = { Text("Posted work still goes to D365FO. Unposted scans stay here.") },
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

    FocusHome(
        warehouseId = warehouseId,
        failedCount = failedCount,
        online = online,
        modules = visibleModules(handheldMenu, blockedWorkflows),
        figures = figures,
        onOpen = { route -> if (!unfinishedViewModel.justScanned()) onModuleSelected(route) },
        onRecord = usage::record,
        onSettings = onSettings,
        onSignOut = onSignOut?.let { { confirmSignOut = true } },
        onChangeWarehouse = onChangeWarehouse,
        onScanOpen = onScanOpen,
    )
}

/**
 * "Continue where you left off": unfinished jobs on this device, with resume and discard - on
 * the Activity tab (moved from Home, 9 Nov). Three shown, then "Show all".
 */
internal fun androidx.compose.foundation.lazy.LazyListScope.unfinishedWorkSection(
    unfinished: List<UnfinishedWork>,
    showAll: Boolean,
    onToggleShowAll: () -> Unit,
    onHoldElsewhere: Int,
    onResume: (UnfinishedWork) -> Unit,
    onDiscard: (UnfinishedWork) -> Unit,
    onChangeWarehouse: () -> Unit,
    side: Modifier = Modifier,
) {
    if (unfinished.isNotEmpty()) {
        item(key = "unfinished-header") {
            com.d365fo.mobileops.presentation.common.components.ListHeader(
                icon = androidx.compose.material.icons.Icons.Filled.Checklist,
                title = "Continue where you left off",
                count = unfinished.size,
                modifier = side,
            )
        }
        items(
            if (showAll) unfinished else unfinished.take(UNFINISHED_SHOWN),
            key = { "${it.flowType}:${it.orderId}" },
            contentType = { "unfinished" },
        ) { work ->
            UnfinishedWorkCard(
                modifier = side,
                work = work,
                onResume = { onResume(work) },
                onDiscard = { onDiscard(work) },
            )
        }
        if (unfinished.size > UNFINISHED_SHOWN) {
            item(key = "unfinished-more") {
                Text(
                    text = if (showAll) "Show fewer" else "Show all ${unfinished.size} unfinished",
                    style = MaterialTheme.typography.titleSmall,
                    color = WarehouseStatusColors.accent,
                    modifier = side
                        .clickable(onClick = onToggleShowAll)
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
    if (onHoldElsewhere > 0) {
        item(key = "on-hold") {
            Text(
                text = "$onHoldElsewhere unfinished at another warehouse - change warehouse",
                style = MaterialTheme.typography.bodySmall,
                color = WarehouseStatusColors.text3,
                modifier = side.clickable(onClick = onChangeWarehouse).padding(vertical = 4.dp),
            )
        }
    }
}

/** The module a job resumes in - for the switched-off check before resuming. */
internal fun workflowFor(work: UnfinishedWork) = when (work.flowType) {
    com.d365fo.mobileops.data.local.entity.FlowType.RECEIVING -> com.d365fo.mobileops.domain.model.WarehouseWorkflow.PO_RECEIVING
    com.d365fo.mobileops.data.local.entity.FlowType.TRANSFER_SHIP -> com.d365fo.mobileops.domain.model.WarehouseWorkflow.TRANSFER_SHIP
    com.d365fo.mobileops.data.local.entity.FlowType.TRANSFER_RECEIVE -> com.d365fo.mobileops.domain.model.WarehouseWorkflow.TRANSFER_RECEIVE
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

                // The D365FO tables (module.d365) are for the docs, not the worker (21 Oct).

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
    // A Harbour row: amber marker, the order with an "In progress" tag, and Discard on the right
    // (top-aligned, next to the title it belongs to).
    com.d365fo.mobileops.presentation.common.components.ListCard(modifier = modifier) {
        com.d365fo.mobileops.presentation.common.components.LineRow(
            title = work.orderId,
            status = { com.d365fo.mobileops.presentation.common.components.StatusTag("In progress", com.d365fo.mobileops.presentation.common.components.Lead.Warn) },
            lines = listOfNotNull(
                UnfinishedWorkViewModel.labelFor(work.flowType) + " · " + work.lineCount +
                    (if (work.lineCount == 1) " line" else " lines") + " · " + relativeTime(work.updatedAt),
                work.warehouseId?.takeIf { it.isNotBlank() }?.let { "Store $it" },
            ),
            lead = {
                com.d365fo.mobileops.presentation.common.components.LeadDot(
                    com.d365fo.mobileops.presentation.common.components.Lead.Warn,
                    icon = androidx.compose.material.icons.Icons.Filled.Checklist,
                )
            },
            trailing = { TextButton(onClick = onDiscard) { Text("Discard") } },
            onClick = onResume,
        )
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
