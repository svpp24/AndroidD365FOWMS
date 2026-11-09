package com.d365fo.mobileops.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import com.d365fo.mobileops.presentation.common.components.LineIcons
import com.d365fo.mobileops.presentation.common.focus.BandKind
import com.d365fo.mobileops.presentation.common.focus.BandState
import com.d365fo.mobileops.presentation.common.focus.FocusDimens
import com.d365fo.mobileops.presentation.common.focus.FocusMenu
import com.d365fo.mobileops.presentation.common.focus.FocusMenuItem
import com.d365fo.mobileops.presentation.common.focus.HamburgerButton
import com.d365fo.mobileops.presentation.common.focus.HomeOnHandPanel
import com.d365fo.mobileops.presentation.common.focus.HomeRow
import com.d365fo.mobileops.presentation.common.focus.ResultBand
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusType
import com.d365fo.mobileops.presentation.common.tour.tourTarget

/**
 * Home in the Focus design (brief section 6): `{user} · {warehouse}`, the connection state and the
 * hamburger (Settings, Sign out); the navy On-hand panel; then one row per area with its count -
 * Receive, Ship, Count, Create, Activity (failed sends, red, hidden at 0) and Enquiry. No bottom
 * bar, no scan button: a scan anywhere on Home opens the process it belongs to.
 */
@Composable
internal fun FocusHome(
    warehouseId: String?,
    failedCount: Int,
    online: Boolean,
    modules: List<ModuleTile>,
    figures: HomeDashboardViewModel.Figures,
    onOpen: (String) -> Unit,
    onRecord: (String) -> Unit,
    onSettings: () -> Unit,
    onSignOut: (() -> Unit)?,
    onChangeWarehouse: () -> Unit,
    onScanOpen: (HomeScanViewModel.Open) -> Unit,
    sessionViewModel: WarehouseSwitcherViewModel = hiltViewModel(),
    scanViewModel: HomeScanViewModel = hiltViewModel(),
) {
    val session by sessionViewModel.session.collectAsStateWithLifecycle()
    val band by scanViewModel.band.collectAsStateWithLifecycle()
    var notAvailable by remember { mutableStateOf<BandState?>(null) }

    // Scans reach Home only while it is in front.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> scanViewModel.attach()
                Lifecycle.Event.ON_PAUSE -> scanViewModel.detach()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            scanViewModel.detach()
        }
    }
    val latestModules by rememberUpdatedState(modules)
    val latestScanOpen by rememberUpdatedState(onScanOpen)
    LaunchedEffect(scanViewModel) {
        scanViewModel.open.collect { open ->
            // Modules switched off for this worker stay shut, from a scan too.
            if (open.workflow != null && latestModules.none { it.workflow == open.workflow }) {
                notAvailable = BandState(BandKind.Bad, "Not available to you", open.workflow.label + " · nothing opened")
                return@collect
            }
            notAvailable = null
            val before = scanViewModel.listenersNow()
            latestScanOpen(open)
            open.forward?.let { scanViewModel.forwardWhenListening(it, before) }
        }
    }

    fun has(vararg workflows: WarehouseWorkflow) = workflows.any { w -> modules.any { it.workflow == w } }
    fun openGroup(group: ModuleGroup) {
        val inGroup = group.modules(modules)
        if (inGroup.size == 1) {
            inGroup[0].route?.let { onRecord(inGroup[0].title); onOpen(it) }
        } else if (inGroup.isNotEmpty()) {
            onOpen(ModuleGroup.route(group))
        }
    }
    val rows = buildList {
        if (has(WarehouseWorkflow.PO_RECEIVING, WarehouseWorkflow.TRANSFER_RECEIVE)) {
            add(HomeEntry(LineIcons.Receive, "Receive", receiveCount(figures, modules)) { openGroup(ModuleGroup.RECEIVE) })
        }
        if (has(WarehouseWorkflow.TRANSFER_SHIP)) {
            add(HomeEntry(LineIcons.Ship, "Ship", figures.transfersToShip?.toString()) { openGroup(ModuleGroup.SHIP) })
        }
        if (has(WarehouseWorkflow.INVENTORY_COUNT)) {
            add(HomeEntry(LineIcons.Count, "Count", figures.countsInProgress?.toString()) { openGroup(ModuleGroup.COUNT) })
        }
        if (has(WarehouseWorkflow.PO_CREATE, WarehouseWorkflow.TO_CREATE, WarehouseWorkflow.PURCHASE_RETURN)) {
            add(HomeEntry(LineIcons.Create, "Create", null) { openGroup(ModuleGroup.CREATE) })
        }
        add(HomeEntry(LineIcons.ActivityThin, "Activity", failedCount.takeIf { it > 0 }?.toString(), alert = true) { onOpen(Routes.FAILED_SYNC) })
        add(HomeEntry(LineIcons.SearchThin, "Enquiry", null, muted = true) { onOpen(Routes.SERVICES) })
    }
    val user = session?.displayName?.trim()?.split(' ')?.firstOrNull { it.isNotBlank() } ?: session?.userName.orEmpty()
    FocusHomeLayout(
        header = listOfNotNull(user.takeIf { it.isNotBlank() }, session?.selected?.warehouseId ?: warehouseId).joinToString(" · "),
        online = online,
        band = notAvailable ?: band,
        rows = rows,
        onSettings = onSettings,
        onSignOut = onSignOut,
        onChangeWarehouse = if (session?.warehouses.orEmpty().size > 1) onChangeWarehouse else null,
        restoreCard = { RestoreWorkCard(modifier = it) },
    )
}

/** One Home row. [alert]: the count is red (Activity). [muted]: grey icon (Enquiry). */
internal data class HomeEntry(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val count: String?,
    val alert: Boolean = false,
    val muted: Boolean = false,
    val onClick: () -> Unit,
)

/** Home's layout, without view models - for the app and for the layout test's samples. */
@Composable
internal fun FocusHomeLayout(
    header: String,
    online: Boolean,
    band: BandState?,
    rows: List<HomeEntry>,
    onSettings: () -> Unit,
    onSignOut: (() -> Unit)?,
    onChangeWarehouse: (() -> Unit)?,
    restoreCard: @Composable (Modifier) -> Unit = {},
    onPanelSubmit: ((String) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FocusColors.page),
    ) {
        // Header: who and where, online, the hamburger.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FocusDimens.topBar)
                .padding(start = FocusDimens.side, end = 8.dp),
        ) {
            Text(
                header,
                style = FocusType.topBarTitle,
                color = FocusColors.ink,
                modifier = Modifier
                    .weight(1f)
                    .tourTarget("warehouse")
                    .then(
                        if (onChangeWarehouse != null) {
                            Modifier.clickable(role = Role.Button, onClickLabel = "Change warehouse", onClick = onChangeWarehouse)
                        } else {
                            Modifier
                        },
                    )
                    .heightIn(min = if (onChangeWarehouse != null) 56.dp else 0.dp)
                    .padding(vertical = 8.dp)
                    .semantics { heading() },
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .tourTarget("connection")
                    .padding(horizontal = 8.dp),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (online) FocusColors.okIcon else FocusColors.caption),
                )
                Text(
                    if (online) "Online" else "Offline",
                    style = FocusType.rowDetail,
                    color = if (online) FocusColors.okText else FocusColors.muted,
                )
            }
            Box(Modifier.tourTarget("signout")) {
                HamburgerButton(open = menuOpen, onClick = { menuOpen = !menuOpen })
            }
        }
        FocusMenu(
            open = menuOpen,
            onDismiss = { menuOpen = false },
            items = listOfNotNull(
                FocusMenuItem(Icons.Filled.Settings, "Settings") { menuOpen = false; onSettings() },
                onSignOut?.let { signOut ->
                    FocusMenuItem(Icons.AutoMirrored.Filled.Logout, "Sign out", color = FocusColors.badIcon) { menuOpen = false; signOut() }
                },
            ),
        )

        band?.let { ResultBand(it, modifier = Modifier.padding(bottom = 12.dp)) }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            HomeOnHandPanel(
                icon = Icons.Filled.Inventory2,
                onSubmit = onPanelSubmit,
                modifier = Modifier
                    .padding(horizontal = FocusDimens.side)
                    .padding(top = 4.dp)
                    .tourTarget("tab_onhand"),
            )
            // Work saved in D365FO from another device or an earlier install of this app.
            restoreCard(Modifier.padding(horizontal = FocusDimens.side, vertical = 8.dp))
            Spacer(Modifier.height(8.dp))
            Column(Modifier.tourTarget("tiles")) {
                rows.forEach { row ->
                    HomeRow(
                        icon = row.icon,
                        title = row.title,
                        count = row.count,
                        countColor = if (row.alert) FocusColors.badIcon else FocusColors.muted,
                        iconColor = if (row.muted) FocusColors.muted else FocusColors.navy,
                        onClick = row.onClick,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
/**
 * Receive's count: POs due today plus transfers coming in - only when the ones the worker may
 * receive are both known; a figure D365FO couldn't give is left out, never guessed.
 */
private fun receiveCount(figures: HomeDashboardViewModel.Figures, modules: List<ModuleTile>): String? {
    val po = modules.any { it.workflow == WarehouseWorkflow.PO_RECEIVING }
    val to = modules.any { it.workflow == WarehouseWorkflow.TRANSFER_RECEIVE }
    val parts = listOfNotNull(
        if (po) figures.posDueToday ?: return null else 0,
        if (to) figures.transfersToReceive ?: return null else 0,
    )
    return parts.sum().toString()
}

/** Home with sample figures - previews and the layout test. [band]: the state after a bad scan. */
@Composable
internal fun FocusHomeSample(band: Boolean = false) {
    FocusHomeLayout(
        header = "Abdul · AAC",
        online = !band,
        band = if (band) BandState(BandKind.Bad, "Not recognised", "XJ-55-AB · nothing added") else null,
        rows = listOf(
            HomeEntry(LineIcons.Receive, "Receive", "4") {},
            HomeEntry(LineIcons.Ship, "Ship", "12") {},
            HomeEntry(LineIcons.Count, "Count", "1") {},
            HomeEntry(LineIcons.Create, "Create", null) {},
            HomeEntry(LineIcons.ActivityThin, "Activity", "2", alert = true) {},
            HomeEntry(LineIcons.SearchThin, "Enquiry", null, muted = true) {},
        ),
        onSettings = {},
        onSignOut = {},
        onChangeWarehouse = null,
    )
}

@androidx.compose.ui.tooling.preview.Preview(name = "Home", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun FocusHomePreview() = com.d365fo.mobileops.presentation.common.focus.FocusPreviewSurface { FocusHomeSample() }