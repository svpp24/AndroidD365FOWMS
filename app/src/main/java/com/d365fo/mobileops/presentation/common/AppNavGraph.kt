package com.d365fo.mobileops.presentation.common

import com.d365fo.mobileops.presentation.common.components.LineIcons
import androidx.compose.runtime.CompositionLocalProvider
import com.d365fo.mobileops.presentation.common.tour.AppTourOverlay
import com.d365fo.mobileops.presentation.common.tour.LocalTourTargets
import com.d365fo.mobileops.presentation.common.tour.NavigationTour
import com.d365fo.mobileops.presentation.common.tour.TourTargets
import com.d365fo.mobileops.presentation.common.tour.TourViewModel
import com.d365fo.mobileops.presentation.common.tour.tourTarget
import com.d365fo.mobileops.presentation.common.components.ConnectionDialog
import com.d365fo.mobileops.util.Formatters
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.d365fo.mobileops.R
import com.d365fo.mobileops.data.sync.SyncStatus
import com.d365fo.mobileops.domain.model.TransferMode
import com.d365fo.mobileops.presentation.modules.ordercreate.PoCreateScreen
import com.d365fo.mobileops.presentation.modules.ordercreate.ToCreateScreen
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.presentation.modules.dataenquiry.LocationEntryScreen
import com.d365fo.mobileops.presentation.modules.dataenquiry.LocationInquiryScreen
import com.d365fo.mobileops.presentation.modules.dataenquiry.PoOfTodayScreen
import com.d365fo.mobileops.presentation.modules.cyclecounting.CountingCaptureScreen
import com.d365fo.mobileops.presentation.modules.cyclecounting.CountingJournalListScreen
import com.d365fo.mobileops.presentation.modules.cyclecounting.CountingScanScreen
import com.d365fo.mobileops.presentation.modules.cyclecounting.supervisor.AccuracyReportScreen
import com.d365fo.mobileops.presentation.modules.cyclecounting.supervisor.SupervisorVarianceScreen
import com.d365fo.mobileops.presentation.modules.stepflow.OnHandEntryScreen
import com.d365fo.mobileops.presentation.modules.stepflow.OnHandInquiryScreen
import com.d365fo.mobileops.presentation.modules.transfer.TransferEntryScreen
import com.d365fo.mobileops.presentation.modules.transfer.TransferFlowScreen
import com.d365fo.mobileops.presentation.modules.stepflow.ReceivingEntryScreen
import com.d365fo.mobileops.presentation.modules.stepflow.ReceivingFlowScreen
import kotlinx.coroutines.delay

/**
 * Routes.
 *
 * String-based rather than type-safe navigation because the deep-link surface has to stay
 * addressable from an MDM push — a supervisor sending a device straight to one order is a
 * real workflow.
 */
object Routes {
    const val HOME = "home"

    /** The whole menu as an accordion - a bottom tab (20 Oct restyle). */
    const val SERVICES = "services"

    /** A sub-menu of the worker's handheld menus (Mobile device menus) - see HandheldMenuScreen. */
    const val HANDHELD_MENU = "menu/{menuId}"
    fun handheldMenu(menuId: String) = "menu/" + android.net.Uri.encode(menuId)

    /** Switch between the worker's assigned warehouses; other warehouses' work goes on hold. */
    const val CHANGE_WAREHOUSE = "warehouse/change"

    /**
     * The step-driven receiving flow: scan a PO, then an item, then a quantity, then whatever
     * dimensions that item tracks. Nothing is preloaded; each step fetches only what it needs.
     */
    const val RECEIVE_FLOW = "receive/flow"

    /** On-demand on-hand: scan an item, query it live. */
    const val ON_HAND = "inquiry/onhand"
    /** Home's search: items, orders, journals (20 Oct). */
    const val SEARCH = "search"

    /**
     * Transfer orders, one route per direction.
     *
     * Two routes rather than one with a parameter: the direction is chosen from the menu and never
     * changes mid-flow, so making it part of the address means it cannot be lost on a restore.
     */
    const val TRANSFER_SHIP = "transfer/ship"
    const val TRANSFER_RECEIVE = "transfer/receive"

    /** Create a purchase order / a transfer order - see PoCreateScreen and ToCreateScreen. */
    const val PO_CREATE = "orders/po/create"
    const val TO_CREATE = "orders/to/create"

    /** Return stock to a vendor - see ReturnCreateScreen. */
    const val PURCHASE_RETURN = "orders/return"

    /** Confirm approved purchase orders; [poConfirm] with a PO opens that one straight away. */
    const val PO_CONFIRM = "orders/po/confirm?purchId={purchId}"
    fun poConfirm(purchId: String? = null) = if (purchId.isNullOrBlank()) "orders/po/confirm" else "orders/po/confirm?purchId=$purchId"
    const val FAILED_SYNC = "sync/failures"
    const val SETTINGS = "settings"

    /** Send feedback - from any screen's feedback button (with its screenshot) or Settings. */
    const val FEEDBACK = "feedback"

    /** Inventory count: Store, Cycle and Wall-to-wall count as sub-menus. See CountingHubScreen. */
    const val COUNTING_HUB = "counting"

    /** Open counting journals for the worker's warehouse, of one kind or all. See CountingJournalListScreen. */
    const val COUNTING_JOURNALS = "counting/journals?kind={kind}"
    fun countingJournals(kind: com.d365fo.mobileops.domain.model.CountKind? = null) =
        if (kind == null) "counting/journals" else "counting/journals?kind=${kind.route}"

    /** Start counting one imported journal - scan an item, enter its quantity. See CountingScanScreen. */
    const val COUNTING_CAPTURE = "counting/capture/{journalId}"
    fun countingCapture(journalId: String) = "counting/capture/$journalId"

    /** The same lines, read-only (search, counted/not-counted filter, marker) - View lines. */
    const val COUNTING_LINES = "counting/lines/{journalId}"
    fun countingLines(journalId: String) = "counting/lines/$journalId"

    /** Supervisor only: variance review, approve / reject / recount, post. */
    const val COUNTING_VARIANCE = "counting/supervisor/variance/{journalId}"
    fun countingVariance(journalId: String) = "counting/supervisor/variance/$journalId"

    /** Supervisor only: a dual count journal's disagreements, every count side by side. */
    const val COUNTING_DUAL_REVIEW = "counting/supervisor/dual/{journalId}"
    fun countingDualReview(journalId: String) = "counting/supervisor/dual/$journalId"


    /** A free store count for the Stock count control center. */
    const val COUNTING_STORE = "counting/store"

    /** The store count screen for any kind of count: a hazard or blind count by its id, or wall to wall. */
    const val COUNTING_STORE_ROUTE = "counting/store?masterId={masterId}&type={type}"
    fun countingStore(masterId: String?, type: com.d365fo.mobileops.data.counting.CountType) =
        "counting/store?masterId=${masterId.orEmpty()}&type=${type.symbol}"

    /** The open and finished counts of one kind (Hazard, Wall to wall, Blind). See CountListScreen. */
    const val COUNTING_LIST = "counting/list/{type}"
    fun countingList(type: com.d365fo.mobileops.data.counting.CountType) = "counting/list/${type.route}"

    /** A cycle count supervisor's new count (Blind, Hazard, Wall to wall). See CountCreateScreen. */
    const val COUNTING_CREATE = "counting/create/{type}"
    fun countingCreate(type: com.d365fo.mobileops.data.counting.CountType) = "counting/create/${type.route}"

    /** A hazard count by first-level category, for a cycle count supervisor. See CountCategoriesScreen. */
    const val COUNTING_CATEGORIES = "counting/categories/{masterId}"
    fun countingCategories(masterId: String) = "counting/categories/$masterId"

    /** A finished count's counted, on-hand and variance. See CountResultsScreen. */
    const val COUNTING_RESULTS = "counting/results/{masterId}"
    fun countingResults(masterId: String) = "counting/results/$masterId"

    /** Supervisor only: count accuracy by location / item / class / counter. */
    const val COUNTING_ACCURACY = "counting/supervisor/accuracy"

    /**
     * The read-only lookups - promoted to first-class Home tiles directly (see HomeScreen's own
     * `MODULES`) rather than sitting behind an intermediate menu screen, so there is no
     * "enquiry/menu" route any more, just these three destinations.
     */
    const val INQUIRY_LOCATION = "enquiry/location"
    const val INQUIRY_PO_TODAY = "enquiry/po-today"
    const val INQUIRY_TRANSFERS = "enquiry/transfers"

    const val INQUIRY_PRICE = "enquiry/price"

    /** Items by category: the tree's root, a category (its path, encoded), and one item. */
    const val INQUIRY_ITEMS = "enquiry/items"
    const val INQUIRY_ITEMS_CATEGORY = "enquiry/items/category/{path}"
    fun itemsCategory(path: String) = "enquiry/items/category/" + android.net.Uri.encode(path)
    const val INQUIRY_ITEM = "enquiry/items/item/{itemId}"
    fun itemDetail(itemId: String) = "enquiry/items/item/" + android.net.Uri.encode(itemId)

    /**
     * The dedicated "type it in" screen for whichever scan-first field sent you here - see
     * TextEntryScreen. One route per origin screen, not one shared parameterised route: each
     * fetches its own origin screen's still-alive ViewModel by that origin's own back-stack
     * entry (`navController.getBackStackEntry(...)`), so the address has to name it.
     */
    const val RECEIVE_FLOW_ENTRY = "receive/flow/entry"
    const val TRANSFER_SHIP_ENTRY = "transfer/ship/entry"
    const val TRANSFER_RECEIVE_ENTRY = "transfer/receive/entry"
    const val ON_HAND_ENTRY = "inquiry/onhand/entry"
    const val INQUIRY_LOCATION_ENTRY = "enquiry/location/entry"
}

/** Home, when a switched-off module was the only screen on the stack. */
private fun navigateToTabSafely(navController: NavHostController, route: String) {
    runCatching { navController.navigate(route) { launchSingleTop = true } }
}

@Composable
fun AppNavGraph(
    syncStatus: SyncStatus,
    /** The signed-in worker's backoffice-assigned warehouse, or null before it resolves. */
    warehouseId: String?,
    navController: NavHostController = rememberNavController(),
    onSignOut: () -> Unit = {},
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    // The navigation tour - once per worker, on Home, over the whole app including the
    // bottom bar. Elements mark themselves as stops with Modifier.tourTarget.
    val tourTargets = remember { TourTargets() }
    val tour: TourViewModel = hiltViewModel()
    val showTour by tour.show.collectAsStateWithLifecycle()
    val tourOnScreen = showTour && currentRoute?.route == Routes.HOME

    // Edge-triggered: the connection dialog shows once each time the connection drops (or when
    // the app opens already offline), not on every screen. After that the top bar's connection
    // icon carries the state, and tapping it shows the same dialog again.
    var shownForThisOutage by remember { mutableStateOf(false) }
    var showConnectionDialog by remember { mutableStateOf(false) }
    LaunchedEffect(syncStatus.fullyOnline) {
        if (syncStatus.fullyOnline) {
            shownForThisOutage = false
            showConnectionDialog = false
        } else if (!shownForThisOutage) {
            // The status starts as "no network" until the first check answers, and a Wi-Fi
            // handover blips for a second: only an outage that lasts gets the dialog.
            delay(OFFLINE_DIALOG_GRACE_MS)
            shownForThisOutage = true
            showConnectionDialog = true
        }
    }
    // Held back while the tour shows - it explains the same icon - and shown when it ends.
    if (showConnectionDialog && !tourOnScreen) {
        ConnectionDialog(status = syncStatus, onDismiss = { showConnectionDialog = false })
    }

    // A module switched off in D365FO (Mobile module switches) doesn't open from anywhere - a
    // link from another screen included. The screen closes and the worker is told why.
    val moduleSwitches: ModuleSwitchViewModel = hiltViewModel()
    val switchedOff by moduleSwitches.disabled.collectAsStateWithLifecycle()
    var switchedOffNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentRoute?.route, switchedOff) {
        val workflow = workflowForRoute(currentRoute?.route) ?: return@LaunchedEffect
        val message = switchedOff[workflow] ?: return@LaunchedEffect
        switchedOffNotice = "${workflow.label}: $message"
        if (!navController.popBackStack()) navigateToTabSafely(navController, Routes.HOME)
    }
    switchedOffNotice?.let { notice ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { switchedOffNotice = null },
            title = { androidx.compose.material3.Text("Switched off") },
            text = { androidx.compose.material3.Text(notice) },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.ConfirmButton(onClick = { switchedOffNotice = null }) {
                    androidx.compose.material3.Text("OK")
                }
            },
        )
    }

    // Saves today's orders for offline use while D365FO answers - see OfflinePrefetcher.
    val offlineReady: OfflineReadyViewModel = hiltViewModel()
    LaunchedEffect(warehouseId, syncStatus.fullyOnline) {
        if (syncStatus.fullyOnline && warehouseId != null) offlineReady.onlineAt(warehouseId)
    }

    // The top bar's feedback button: remember which screen and what it looked like, then open
    // the feedback screen - see FeedbackButton / FeedbackOrigin.
    val feedbackOrigin: com.d365fo.mobileops.presentation.common.FeedbackOriginViewModel = hiltViewModel()
    val feedbackScope = rememberCoroutineScope()
    val launchFeedback: (android.graphics.Bitmap?) -> Unit = { shot ->
        val screen = screenLabel(navController.currentDestination?.route)
        feedbackScope.launch {
            feedbackOrigin.origin.capture(screen, shot)
            navController.navigate(Routes.FEEDBACK)
        }
    }

    CompositionLocalProvider(
        LocalTourTargets provides tourTargets,
        com.d365fo.mobileops.presentation.common.components.LocalFeedbackLauncher provides launchFeedback,
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        // No bottom bar and no scan button (redesign brief sections 6 and 11): Home is the root,
        // everything else has a back arrow.
        Scaffold { innerPadding ->

            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.padding(innerPadding),
            ) {

                composable(Routes.HOME) {
                    HomeScreen(
                        warehouseId = warehouseId,
                        onModuleSelected = { navController.navigate(it) },
                        onSignOut = onSignOut,
                        onChangeWarehouse = { navController.navigate(Routes.CHANGE_WAREHOUSE) },
                        failedCount = syncStatus.failedCount,
                        online = syncStatus.fullyOnline,
                        onSettings = { navController.navigate(Routes.SETTINGS) },
                        onScanOpen = { open -> navController.navigate(open.route) { launchSingleTop = true } },
                    )
                }

                composable(Routes.SERVICES) {
                    val homeEntry = remember(it) { runCatching { navController.getBackStackEntry(Routes.HOME) }.getOrNull() }
                    ServicesScreen(
                        onModuleSelected = { route -> navController.navigate(route) },
                        onBack = { navController.popBackStack() },
                        accessViewModel = if (homeEntry != null) hiltViewModel(homeEntry) else hiltViewModel(),
                    )
                }

                // A group of Home's menus (user, 9 Nov) - Home's own view models, as a sub-menu.
                composable(
                    ModuleGroup.ROUTE_PREFIX + "{groupId}",
                    arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
                ) { entry ->
                    val homeEntry = remember(entry) { runCatching { navController.getBackStackEntry(Routes.HOME) }.getOrNull() }
                    ModuleGroupScreen(
                        groupId = entry.arguments?.getString("groupId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onModuleSelected = { route -> navController.navigate(route) },
                        accessViewModel = if (homeEntry != null) hiltViewModel(homeEntry) else hiltViewModel(),
                    )
                }

                composable(
                    Routes.HANDHELD_MENU,
                    arguments = listOf(navArgument("menuId") { type = NavType.StringType }),
                ) { entry ->
                    // Home's own view model, so a sub-menu shows the menus and Menu access Home
                    // already has instead of asking D365FO again.
                    val homeEntry = remember(entry) { runCatching { navController.getBackStackEntry(Routes.HOME) }.getOrNull() }
                    HandheldMenuScreen(
                        menuId = entry.arguments?.getString("menuId").orEmpty(),
                        accessViewModel = if (homeEntry != null) hiltViewModel(homeEntry) else hiltViewModel(),
                        onModuleSelected = { navController.navigate(it) },
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.CHANGE_WAREHOUSE) {
                    ChangeWarehouseScreen(onDone = { navController.popBackStack() })
                }

                composable(Routes.INQUIRY_LOCATION) {
                    LocationInquiryScreen(
                        onBack = { navController.popBackStack() },
                        onManualEntryRequested = { navController.navigate(Routes.INQUIRY_LOCATION_ENTRY) },
                    )
                }

                composable(Routes.INQUIRY_LOCATION_ENTRY) { entry ->
                    LocationEntryScreen(
                        viewModel = hiltViewModel(remember(entry) { navController.getBackStackEntry(Routes.INQUIRY_LOCATION) }),
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.INQUIRY_PO_TODAY) {
                    PoOfTodayScreen(
                        onBack = { navController.popBackStack() },
                        onReceive = { navController.navigate(Routes.RECEIVE_FLOW) },
                    )
                }

                composable(Routes.SEARCH) {
                    com.d365fo.mobileops.presentation.common.search.SearchScreen(
                        onBack = { navController.popBackStack() },
                        onOpenReceiving = { navController.navigate(Routes.RECEIVE_FLOW) },
                        onOpenShip = { navController.navigate(Routes.TRANSFER_SHIP) },
                        onOpenTransferReceive = { navController.navigate(Routes.TRANSFER_RECEIVE) },
                        onOpenJournals = { navController.navigate(Routes.countingJournals()) },
                        onOpenJournalLines = { journalId -> navController.navigate(Routes.countingLines(journalId)) },
                    )
                }

                composable(Routes.INQUIRY_PRICE) {
                    com.d365fo.mobileops.presentation.modules.dataenquiry.PriceCheckScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.INQUIRY_ITEMS) {
                    com.d365fo.mobileops.presentation.modules.dataenquiry.ItemCategoryScreen(
                        onBack = { navController.popBackStack() },
                        onCategory = { navController.navigate(Routes.itemsCategory(it)) },
                        onItem = { navController.navigate(Routes.itemDetail(it)) },
                    )
                }

                composable(
                    Routes.INQUIRY_ITEMS_CATEGORY,
                    arguments = listOf(navArgument("path") { type = NavType.StringType }),
                ) {
                    com.d365fo.mobileops.presentation.modules.dataenquiry.ItemCategoryScreen(
                        onBack = { navController.popBackStack() },
                        onCategory = { navController.navigate(Routes.itemsCategory(it)) },
                        onItem = { navController.navigate(Routes.itemDetail(it)) },
                    )
                }

                composable(
                    Routes.INQUIRY_ITEM,
                    arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
                ) {
                    com.d365fo.mobileops.presentation.modules.dataenquiry.ItemDetailScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.INQUIRY_TRANSFERS) {
                    com.d365fo.mobileops.presentation.modules.dataenquiry.TransferOrdersScreen(
                        onBack = { navController.popBackStack() },
                        onOpenShip = { navController.navigate(Routes.TRANSFER_SHIP) },
                        onOpenReceive = { navController.navigate(Routes.TRANSFER_RECEIVE) },
                    )
                }





                composable(Routes.COUNTING_HUB) {
                    com.d365fo.mobileops.presentation.modules.cyclecounting.CountingHubScreen(
                        onBack = { navController.popBackStack() },
                        onStoreCount = { navController.navigate(Routes.COUNTING_STORE) },
                        onJournals = { kind -> navController.navigate(Routes.countingJournals(kind)) },
                        onAccuracy = { navController.navigate(Routes.COUNTING_ACCURACY) },
                        onCountType = { type -> navController.navigate(Routes.countingList(type)) },
                    )
                }

                composable(
                    Routes.COUNTING_LIST,
                    arguments = listOf(navArgument("type") { type = NavType.StringType }),
                ) {
                    com.d365fo.mobileops.presentation.modules.cyclecounting.counts.CountListScreen(
                        onBack = { navController.popBackStack() },
                        onCount = { masterId, type -> navController.navigate(Routes.countingStore(masterId, type)) },
                        onResults = { masterId -> navController.navigate(Routes.countingResults(masterId)) },
                        onCategories = { masterId -> navController.navigate(Routes.countingCategories(masterId)) },
                        onCreate = { type -> navController.navigate(Routes.countingCreate(type)) },
                    )
                }

                composable(
                    Routes.COUNTING_CREATE,
                    arguments = listOf(navArgument("type") { type = NavType.StringType }),
                ) {
                    com.d365fo.mobileops.presentation.modules.cyclecounting.counts.CountCreateScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(
                    Routes.COUNTING_CATEGORIES,
                    arguments = listOf(navArgument("masterId") { type = NavType.StringType }),
                ) {
                    com.d365fo.mobileops.presentation.modules.cyclecounting.counts.CountCategoriesScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(
                    Routes.COUNTING_RESULTS,
                    arguments = listOf(navArgument("masterId") { type = NavType.StringType }),
                ) {
                    com.d365fo.mobileops.presentation.modules.cyclecounting.counts.CountResultsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(
                    Routes.COUNTING_JOURNALS,
                    arguments = listOf(navArgument("kind") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { entry ->
                    CountingJournalListScreen(
                        kind = com.d365fo.mobileops.domain.model.CountKind.fromRoute(entry.arguments?.getString("kind")),
                        onBack = { navController.popBackStack() },
                        onViewLines = { journalId ->
                            navController.navigate(Routes.countingLines(journalId))
                        },
                        onReviewVariance = { journalId ->
                            navController.navigate(Routes.countingVariance(journalId))
                        },
                        onReviewDualCount = { journalId ->
                            navController.navigate(Routes.countingDualReview(journalId))
                        },
                        onStoreCount = { navController.navigate(Routes.COUNTING_STORE) },
                        onAccuracy = { navController.navigate(Routes.COUNTING_ACCURACY) },
                        onStartCounting = { journalId ->
                            navController.navigate(Routes.countingCapture(journalId))
                        },
                    )
                }

                composable(
                    Routes.COUNTING_CAPTURE,
                    arguments = listOf(navArgument("journalId") { type = NavType.StringType }),
                ) {
                    CountingScanScreen(onBack = { navController.popBackStack() })
                }

                composable(
                    Routes.COUNTING_LINES,
                    arguments = listOf(navArgument("journalId") { type = NavType.StringType }),
                ) {
                    CountingCaptureScreen(onBack = { navController.popBackStack() })
                }

                composable(
                    Routes.COUNTING_VARIANCE,
                    arguments = listOf(navArgument("journalId") { type = NavType.StringType }),
                ) {
                    SupervisorVarianceScreen(onBack = { navController.popBackStack() })
                }

                composable(
                    Routes.COUNTING_DUAL_REVIEW,
                    arguments = listOf(navArgument("journalId") { type = NavType.StringType }),
                ) {
                    com.d365fo.mobileops.presentation.modules.cyclecounting.supervisor.DualCountReviewScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.COUNTING_ACCURACY) {
                    AccuracyReportScreen(onBack = { navController.popBackStack() })
                }

                composable(
                    Routes.COUNTING_STORE_ROUTE,
                    arguments = listOf(
                        navArgument("masterId") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("type") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) {
                    com.d365fo.mobileops.presentation.modules.cyclecounting.store.StoreCountScreen(
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onSignOut = onSignOut,
                        onChangeWarehouse = { navController.navigate(Routes.CHANGE_WAREHOUSE) },
                        onFeedback = { navController.navigate(Routes.FEEDBACK) },
                    )
                }

                composable(Routes.FEEDBACK) {
                    FeedbackScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.RECEIVE_FLOW) {
                    ReceivingFlowScreen(
                        onBack = { navController.popBackStack() },
                        onManualEntryRequested = { navController.navigate(Routes.RECEIVE_FLOW_ENTRY) },
                    )
                }

                composable(Routes.RECEIVE_FLOW_ENTRY) { entry ->
                    ReceivingEntryScreen(
                        viewModel = hiltViewModel(remember(entry) { navController.getBackStackEntry(Routes.RECEIVE_FLOW) }),
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.TRANSFER_SHIP) {
                    TransferFlowScreen(
                        mode = TransferMode.SHIP,
                        onBack = { navController.popBackStack() },
                        onManualEntryRequested = { navController.navigate(Routes.TRANSFER_SHIP_ENTRY) },
                    )
                }

                composable(Routes.PO_CREATE) {
                    PoCreateScreen(
                        onBack = { navController.popBackStack() },
                        onConfirm = { purchId -> navController.navigate(Routes.poConfirm(purchId)) },
                    )
                }

                composable(
                    Routes.PO_CONFIRM,
                    arguments = listOf(
                        navArgument("purchId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) {
                    com.d365fo.mobileops.presentation.modules.ordercreate.PoConfirmScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.PURCHASE_RETURN) {
                    com.d365fo.mobileops.presentation.modules.ordercreate.ReturnCreateScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.TO_CREATE) {
                    ToCreateScreen(
                        onBack = { navController.popBackStack() },
                        onShip = { navController.navigate(Routes.TRANSFER_SHIP) },
                    )
                }

                composable(Routes.TRANSFER_SHIP_ENTRY) { entry ->
                    TransferEntryScreen(
                        viewModel = hiltViewModel(remember(entry) { navController.getBackStackEntry(Routes.TRANSFER_SHIP) }),
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.TRANSFER_RECEIVE) {
                    TransferFlowScreen(
                        mode = TransferMode.RECEIVE,
                        onBack = { navController.popBackStack() },
                        onManualEntryRequested = { navController.navigate(Routes.TRANSFER_RECEIVE_ENTRY) },
                    )
                }

                composable(Routes.TRANSFER_RECEIVE_ENTRY) { entry ->
                    TransferEntryScreen(
                        viewModel = hiltViewModel(remember(entry) { navController.getBackStackEntry(Routes.TRANSFER_RECEIVE) }),
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.ON_HAND) {
                    OnHandInquiryScreen(
                        onBack = { navController.popBackStack() },
                        onManualEntryRequested = { navController.navigate(Routes.ON_HAND_ENTRY) },
                    )
                }

                composable(Routes.ON_HAND_ENTRY) { entry ->
                    OnHandEntryScreen(
                        viewModel = hiltViewModel(remember(entry) { navController.getBackStackEntry(Routes.ON_HAND) }),
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.FAILED_SYNC) {
                    val homeEntry = remember(it) { runCatching { navController.getBackStackEntry(Routes.HOME) }.getOrNull() }
                    FailedSyncScreen(
                        onBack = { navController.popBackStack() },
                        onOpen = { route -> navController.navigate(route) },
                        onChangeWarehouse = { navController.navigate(Routes.CHANGE_WAREHOUSE) },
                        accessViewModel = if (homeEntry != null) hiltViewModel(homeEntry) else hiltViewModel(),
                    )
                }
            }
        }

        if (tourOnScreen) {
            AppTourOverlay(NavigationTour, tourTargets, onFinish = tour::markSeen)
        }
    }
    }
}






/** How long the connection must stay down before the offline dialog shows. */
private const val OFFLINE_DIALOG_GRACE_MS = 2_000L


/** The screen a route shows, as a worker would name it - for feedback (see FeedbackOrigin). */
internal fun screenLabel(route: String?): String = when {
    route == null -> "General"
    route == Routes.HOME -> "Home"
    route.startsWith("menu/") -> "Menu"
    route == Routes.RECEIVE_FLOW || route == Routes.RECEIVE_FLOW_ENTRY -> "PO receiving"
    route == Routes.TRANSFER_SHIP || route == Routes.TRANSFER_SHIP_ENTRY -> "Transfer - Ship"
    route == Routes.TRANSFER_RECEIVE || route == Routes.TRANSFER_RECEIVE_ENTRY -> "Transfer - Receive"
    route == Routes.ON_HAND || route == Routes.ON_HAND_ENTRY -> "On-hand"
    route == Routes.FAILED_SYNC -> "Activity"
    route == Routes.SETTINGS -> "Settings"
    route == Routes.CHANGE_WAREHOUSE -> "Change warehouse"
    route == Routes.PO_CREATE -> "PO creation"
    route == Routes.TO_CREATE -> "TO creation"
    route == Routes.PURCHASE_RETURN -> "Purchase return"
    route.startsWith("orders/po/confirm") -> "PO confirmation"
    route.startsWith("counting/") -> "Inventory count"
    route.startsWith(Routes.INQUIRY_LOCATION) -> "Location inquiry"
    route == Routes.INQUIRY_PO_TODAY -> "Purchase orders"
    route.startsWith(Routes.INQUIRY_ITEMS) -> "Items by category"
    route == Routes.INQUIRY_PRICE -> "Price check"
    route == Routes.INQUIRY_TRANSFERS -> "Transfer orders"
    else -> route
}
