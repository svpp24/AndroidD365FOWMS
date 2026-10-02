package com.d365fo.mobileops.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
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
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.d365fo.mobileops.R
import com.d365fo.mobileops.data.sync.SyncStatus
import com.d365fo.mobileops.domain.model.TransferMode
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.presentation.modules.dataenquiry.LocationEntryScreen
import com.d365fo.mobileops.presentation.modules.dataenquiry.LocationInquiryScreen
import com.d365fo.mobileops.presentation.modules.dataenquiry.PoLinesEntryScreen
import com.d365fo.mobileops.presentation.modules.dataenquiry.PoLinesScreen
import com.d365fo.mobileops.presentation.modules.dataenquiry.PoOfTodayScreen
import com.d365fo.mobileops.presentation.modules.cyclecounting.CountingCaptureScreen
import com.d365fo.mobileops.presentation.modules.cyclecounting.CountingJournalListScreen
import com.d365fo.mobileops.presentation.modules.cyclecounting.CountingScanScreen
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

    /**
     * The step-driven receiving flow: scan a PO, then an item, then a quantity, then whatever
     * dimensions that item tracks. Nothing is preloaded; each step fetches only what it needs.
     */
    const val RECEIVE_FLOW = "receive/flow"

    /** On-demand on-hand: scan an item, query it live. */
    const val ON_HAND = "inquiry/onhand"

    /**
     * Transfer orders, one route per direction.
     *
     * Two routes rather than one with a parameter: the direction is chosen from the menu and never
     * changes mid-flow, so making it part of the address means it cannot be lost on a restore.
     */
    const val TRANSFER_SHIP = "transfer/ship"
    const val TRANSFER_RECEIVE = "transfer/receive"
    const val FAILED_SYNC = "sync/failures"
    const val SETTINGS = "settings"

    /** Open counting journals for the worker's assigned warehouse. See CountingJournalListScreen. */
    const val COUNTING_JOURNALS = "counting/journals"

    /** Start counting one imported journal - scan an item, enter its quantity. See CountingScanScreen. */
    const val COUNTING_CAPTURE = "counting/capture/{journalId}"
    fun countingCapture(journalId: String) = "counting/capture/$journalId"

    /** The same lines, read-only (search, counted/not-counted filter, marker) - View lines. */
    const val COUNTING_LINES = "counting/lines/{journalId}"
    fun countingLines(journalId: String) = "counting/lines/$journalId"

    /** Supervisor only: variance review, approve / reject / recount, post. */
    const val COUNTING_VARIANCE = "counting/supervisor/variance/{journalId}"
    fun countingVariance(journalId: String) = "counting/supervisor/variance/$journalId"

    /**
     * The read-only lookups - promoted to first-class Home tiles directly (see HomeScreen's own
     * `MODULES`) rather than sitting behind an intermediate menu screen, so there is no
     * "enquiry/menu" route any more, just these three destinations.
     */
    const val INQUIRY_LOCATION = "enquiry/location"
    const val INQUIRY_PO_TODAY = "enquiry/po-today"
    const val INQUIRY_PO_LINES = "enquiry/po-lines"

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
    const val INQUIRY_PO_LINES_ENTRY = "enquiry/po-lines/entry"
}

/**
 * The four destinations always reachable at a tap, bottom-anchored the way every mainstream
 * Android app places primary navigation - Gmail, Maps, Play Store. Deliberately not every
 * route in [Routes]: a bottom tab is for a *section* you switch between, not a *workflow* you
 * start and finish. Receiving and the two transfer directions stay one tap away from Home
 * instead, because a bottom bar competing for thumb space with a quantity keypad mid-capture,
 * or inviting a tab-switch away from a half-scanned pallet, is worse than not having one there
 * at all.
 */
private data class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

// On-hand used the Warehouse glyph originally - swapped for Inventory2 (still Icons.Filled,
// same family as Home/Assignment/Settings below) because Warehouse's own silhouette is
// noticeably heavier/blockier than the other three, reading as a different icon style even
// though it technically wasn't one.
private val BOTTOM_TABS = listOf(
    BottomTab(Routes.HOME, "Home", Icons.Default.Home),
    BottomTab(Routes.ON_HAND, "On-hand", Icons.Default.Inventory2),
    BottomTab(Routes.FAILED_SYNC, "Activity", Icons.AutoMirrored.Filled.Assignment),
    BottomTab(Routes.SETTINGS, "Settings", Icons.Default.Settings),
)

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

    // Edge-triggered, not level-triggered: SyncStatus only ever says what the connection is
    // *now*, so a transition has to be tracked here to know when to say something about it.
    // Keyed on fullyOnline, not just online - either kind of degraded connectivity (see the
    // SyncStatus class doc) sets wasOffline, and the banner only fires once *both* device
    // internet and the AOS tenant answer again. wasOffline starts false on purpose - an app
    // launched already online has nothing to announce, this is only for a connection that
    // actually came back.
    var wasOffline by remember { mutableStateOf(false) }
    var showBackOnline by remember { mutableStateOf(false) }

    LaunchedEffect(syncStatus.fullyOnline) {
        if (syncStatus.fullyOnline) {
            if (wasOffline) {
                showBackOnline = true
                delay(BACK_ONLINE_BANNER_MS)
                showBackOnline = false
            }
            wasOffline = false
        } else {
            wasOffline = true
            showBackOnline = false
        }
    }

    // The one correct way to reach a bottom-tab destination, from anywhere - a bare
    // navController.navigate(route) pushes a second, option-less back-stack entry for a route
    // the tab bar already manages with popUpTo/saveState. The tab bar's own
    // popUpTo(startDestination){saveState=true} + restoreState=true then has an entry to unwind
    // that was never pushed under those same rules, and tapping Home from it can silently go
    // nowhere - confirmed live once, back when a second entry point (the Home top bar's
    // failure-count icon, since removed) bypassed this.
    fun navigateToTab(route: String) {
        navController.navigate(route) {
            // Standard bottom-nav wiring: switching tabs never stacks a second copy of one,
            // backing out of a tab returns to Home rather than walking back through every tab
            // ever visited, and each tab keeps its own scroll position/state across switches.
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            Column {
                // Replaces the old always-present top-bar connectivity icon: nothing shown at
                // all while fully online (work queues and posts the same either way, so there
                // is nothing to say), a small strip the moment either device internet or the
                // AOS tenant itself is unreachable - on every screen, not just Home, since an
                // operator mid-scan on Receiving needs to know just as much as one sitting on
                // the Home grid. Two different strips, not one: "check your Wi-Fi" is the
                // wrong thing to tell someone whose internet is fine and whose tenant is down,
                // and the reverse is just as wrong - see the SyncStatus class doc. The green
                // "back online" strip is the mirror image of either, but transient rather than
                // persistent: once the queue is draining again there is nothing left to keep
                // saying, so it announces the moment and gets out of the way - see the
                // LaunchedEffect above.
                if (!syncStatus.online) {
                    OfflineStatusBar()
                } else if (!syncStatus.d365Reachable) {
                    D365UnreachableStatusBar()
                } else if (showBackOnline) {
                    BackOnlineStatusBar()
                }
                // Hidden on the workflow screens, not just unhighlighted - see the class doc on
                // BottomTab for why a step-flow screen is not a tab destination at all.
                if (currentRoute?.hierarchy?.any { it.route in BOTTOM_TABS.map(BottomTab::route) } == true) {
                    AppBottomBar(
                        currentRoute = currentRoute.route,
                        failedCount = syncStatus.failedCount,
                        onTabSelected = ::navigateToTab,
                    )
                }
            }
        },
    ) { innerPadding ->
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
                )
            }

            composable(Routes.INQUIRY_LOCATION) {
                LocationInquiryScreen(
                    onBack = { navController.popBackStack() },
                    onManualEntryRequested = { navController.navigate(Routes.INQUIRY_LOCATION_ENTRY) },
                )
            }

            composable(Routes.INQUIRY_LOCATION_ENTRY) {
                LocationEntryScreen(
                    viewModel = hiltViewModel(navController.getBackStackEntry(Routes.INQUIRY_LOCATION)),
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.INQUIRY_PO_TODAY) {
                PoOfTodayScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.INQUIRY_PO_LINES) {
                PoLinesScreen(
                    onBack = { navController.popBackStack() },
                    onManualEntryRequested = { navController.navigate(Routes.INQUIRY_PO_LINES_ENTRY) },
                )
            }

            composable(Routes.INQUIRY_PO_LINES_ENTRY) {
                PoLinesEntryScreen(
                    viewModel = hiltViewModel(navController.getBackStackEntry(Routes.INQUIRY_PO_LINES)),
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.COUNTING_JOURNALS) {
                CountingJournalListScreen(
                    onBack = { navController.popBackStack() },
                    onViewLines = { journalId ->
                        navController.navigate(Routes.countingLines(journalId))
                    },
                    onReviewVariance = { journalId ->
                        navController.navigate(Routes.countingVariance(journalId))
                    },
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

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onSignOut = onSignOut,
                )
            }

            composable(Routes.RECEIVE_FLOW) {
                ReceivingFlowScreen(
                    onBack = { navController.popBackStack() },
                    onManualEntryRequested = { navController.navigate(Routes.RECEIVE_FLOW_ENTRY) },
                )
            }

            composable(Routes.RECEIVE_FLOW_ENTRY) {
                ReceivingEntryScreen(
                    viewModel = hiltViewModel(navController.getBackStackEntry(Routes.RECEIVE_FLOW)),
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

            composable(Routes.TRANSFER_SHIP_ENTRY) {
                TransferEntryScreen(
                    viewModel = hiltViewModel(navController.getBackStackEntry(Routes.TRANSFER_SHIP)),
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

            composable(Routes.TRANSFER_RECEIVE_ENTRY) {
                TransferEntryScreen(
                    viewModel = hiltViewModel(navController.getBackStackEntry(Routes.TRANSFER_RECEIVE)),
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.ON_HAND) {
                OnHandInquiryScreen(
                    onBack = { navController.popBackStack() },
                    onManualEntryRequested = { navController.navigate(Routes.ON_HAND_ENTRY) },
                )
            }

            composable(Routes.ON_HAND_ENTRY) {
                OnHandEntryScreen(
                    viewModel = hiltViewModel(navController.getBackStackEntry(Routes.ON_HAND)),
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.FAILED_SYNC) {
                FailedSyncScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * The entire connectivity signal, now: nothing while online, this strip the moment D365FO isn't
 * reachable. Still amber, not red - going offline is a normal, handled state here (every
 * capture still queues locally and posts once the connection returns), not a failure; a failed
 * posting has its own loud red badge on the Activity tab already. Solid fill rather than the
 * pale tint this used to have: an operator needs to actually notice their captures are queuing
 * locally instead of posting, and the tint alone read as too easy to miss against the rest of
 * the chrome.
 */
@Composable
private fun OfflineStatusBar() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(WarehouseStatusColors.amber)
            .padding(horizontal = WarehouseDimens.ScreenPadding, vertical = 8.dp),
    ) {
        Icon(
            Icons.Default.CloudOff,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.status_offline),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

/**
 * The other half of the "not the same problem" pair with [OfflineStatusBar] - this device has
 * internet, but D365FO itself is not answering. Same amber, same solid fill, same icon even:
 * both strips are "D365FO cannot be reached right now" from the operator's chair, and the
 * reason only matters for what a supervisor does next, not for what the operator sees. Only the
 * wording differs, so it does not send anyone looking at their Wi-Fi for a tenant outage.
 */
@Composable
private fun D365UnreachableStatusBar() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(WarehouseStatusColors.amber)
            .padding(horizontal = WarehouseDimens.ScreenPadding, vertical = 8.dp),
    ) {
        Icon(
            Icons.Default.CloudOff,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.status_d365_unreachable),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

/** How long [BackOnlineStatusBar] stays up before it dismisses itself. */
private const val BACK_ONLINE_BANNER_MS = 3_000L

/**
 * The green mirror of [OfflineStatusBar], shown only for the few seconds right after a
 * connection that was down comes back - see the edge-triggered LaunchedEffect in
 * [AppNavGraph]. Green and not the app's usual accent: this is the one place in the status
 * strip that is explicitly good news, not a neutral state to keep in view.
 */
@Composable
private fun BackOnlineStatusBar() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(WarehouseStatusColors.greenDim)
            .padding(horizontal = WarehouseDimens.ScreenPadding, vertical = 6.dp),
    ) {
        Icon(
            Icons.Default.CloudDone,
            contentDescription = null,
            tint = WarehouseStatusColors.green,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(R.string.status_back_online),
            style = MaterialTheme.typography.labelSmall,
            color = WarehouseStatusColors.green,
        )
    }
}

/**
 * Hand-built rather than Material3's `NavigationBar` - that composable's own container only ever
 * takes a *minimum* height (80dp, via `defaultMinSize`), not a settable one, so there is no
 * reliable way to shrink it from outside. [WarehouseDimens.BottomBarHeight] gets this bar down to
 * a real gloved-fingertip touch target and nothing more, giving the screen above it back the
 * space Material3's default spends on padding no warehouse screen here needs.
 *
 * White bar with a soft top shadow, matching the white top bar ([AppTopBar]); the selected tab
 * gets a round accent-filled icon (the reference layout's round Home button, generalised to
 * whichever tab is current) and an accent label, the rest sit in muted grey.
 */
@Composable
private fun AppBottomBar(
    currentRoute: String?,
    failedCount: Int,
    onTabSelected: (String) -> Unit,
) {
    Surface(
        color = Color.White,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(WarehouseDimens.BottomBarHeight),
    ) {
        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            BOTTOM_TABS.forEach { tab ->
                val selected = currentRoute == tab.route
                val tint = if (selected) WarehouseStatusColors.accent else WarehouseStatusColors.text3
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(),
                            onClick = { onTabSelected(tab.route) },
                        ),
                ) {
                    val iconTint = if (selected) Color.White else WarehouseStatusColors.text3
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(30.dp)
                            .background(
                                if (selected) WarehouseStatusColors.accent else Color.Transparent,
                                CircleShape,
                            ),
                    ) {
                        // The one badge worth putting on a tab icon: failed postings are the
                        // one thing on this bar that means "something needs you", the same
                        // reason a real Android app puts a badge on a bottom tab rather than
                        // only in a menu three taps away.
                        if (tab.route == Routes.FAILED_SYNC && failedCount > 0) {
                            BadgedBox(badge = { Badge { Text("$failedCount") } }) {
                                Icon(
                                    tab.icon,
                                    contentDescription = tab.label,
                                    tint = iconTint,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            Icon(
                                tab.icon,
                                contentDescription = tab.label,
                                tint = iconTint,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                    )
                }
            }
        }
    }
}
