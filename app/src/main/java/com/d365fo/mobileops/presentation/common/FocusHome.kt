package com.d365fo.mobileops.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clipToBounds
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
    // The license-expiry band, once a day for the last 30 days (user, 16 Nov 2026).
    val licenseNotice: com.d365fo.mobileops.presentation.auth.LicenseNoticeViewModel = hiltViewModel()
    val licenseBand by licenseNotice.band.collectAsStateWithLifecycle()
    var notAvailable by remember { mutableStateOf<BandState?>(null) }

    // Scans reach Home only while it is in front.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { scanViewModel.attach(); licenseNotice.refresh() }
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
                scanViewModel.notAvailableSound()
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
            add(HomeEntry(HOME_ICONS[0], "Receive", receiveCount(figures, modules)) { openGroup(ModuleGroup.RECEIVE) })
        }
        if (has(WarehouseWorkflow.TRANSFER_SHIP)) {
            add(HomeEntry(HOME_ICONS[1], "Ship", figures.transfersToShip?.toString()) { openGroup(ModuleGroup.SHIP) })
        }
        if (has(WarehouseWorkflow.INVENTORY_COUNT)) {
            add(HomeEntry(HOME_ICONS[2], "Count", figures.countsInProgress?.toString()) { openGroup(ModuleGroup.COUNT) })
        }
        // Count control (spec section 4, 24 Nov 2026): a cycle count supervisor, in the store that is
        // their default warehouse. D365FO checks both again on every call.
        if (showsCountControl(session)) {
            add(HomeEntry(LineIcons.CountControl, "Count control", null) { onRecord("Count control"); onOpen(Routes.COUNT_CONTROL) })
        }
        if (has(WarehouseWorkflow.PO_CREATE, WarehouseWorkflow.TO_CREATE, WarehouseWorkflow.PURCHASE_RETURN)) {
            add(HomeEntry(HOME_ICONS[3], "Create", null) { openGroup(ModuleGroup.CREATE) })
        }
        add(HomeEntry(HOME_ICONS[4], "Activity", failedCount.takeIf { it > 0 }?.toString(), alert = true) { onOpen(Routes.FAILED_SYNC) })
        add(HomeEntry(HOME_ICONS[5], "Enquiry", null, muted = true) { onOpen(Routes.SERVICES) })
    }
    val user = session?.displayName?.trim()?.takeIf { it.isNotBlank() } ?: session?.userName.orEmpty()
    val code = session?.selected?.warehouseId ?: warehouseId.orEmpty()
    FocusHomeLayout(
        initials = initialsOf(user),
        warehouseCode = code,
        warehouseName = session?.selected?.warehouseName?.trim()?.takeIf { it.isNotBlank() } ?: code,
        userLabel = user,
        online = online,
        band = notAvailable ?: band ?: licenseBand,
        onBandDismiss = if (notAvailable == null && band == null && licenseBand != null) licenseNotice::hideToday else null,
        rows = rows,
        onSettings = onSettings,
        onFeedback = { onOpen(Routes.FEEDBACK) },
        onSignOut = onSignOut,
        onChangeWarehouse = if (session?.warehouses.orEmpty().size > 1) onChangeWarehouse else null,
        restoreCard = { RestoreWorkCard(modifier = it) },
    )
}

/**
 * The header circle: the first letter of the name (user, 16 Nov 2026) - the hub user's name, or
 * the employee's when it has none ("Abdul Rahman" -> A). A name with no letter shows its first
 * character ("002" -> 0).
 */
internal fun initialsOf(name: String): String {
    val trimmed = name.trim()
    return (trimmed.firstOrNull { it.isLetter() } ?: trimmed.firstOrNull())?.uppercase().orEmpty()
}

/** On a tablet, the Home row whose area is open on the right (its title), else null. */
val LocalHomeSelected = androidx.compose.runtime.compositionLocalOf<String?> { null }

/**
 * A tablet in portrait (user, 16 Nov 2026): the chosen area, shown under Home's rows turned into a
 * row of tabs. Null everywhere else.
 */
val LocalHomeArea = androidx.compose.runtime.compositionLocalOf<(@Composable () -> Unit)?> { null }

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
    initials: String,
    warehouseCode: String,
    warehouseName: String,
    online: Boolean,
    band: BandState?,
    /** An x on [band]: the license-expiry band only. */
    onBandDismiss: (() -> Unit)? = null,
    rows: List<HomeEntry>,
    onSettings: () -> Unit,
    onSignOut: (() -> Unit)?,
    onChangeWarehouse: (() -> Unit)?,
    restoreCard: @Composable (Modifier) -> Unit = {},
    onPanelSubmit: ((String) -> Unit)? = null,
    onFeedback: (() -> Unit)? = null,
    userLabel: String = initials,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FocusColors.page),
    ) {
        // Header (user, 15 Nov 2026): the worker's initials in a navy circle - a green ring when
        // online, a dashed grey one offline - with the warehouse code on an orange tag, then the
        // warehouse's name on one line, scrolling when it is longer than the line, and the menu.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FocusDimens.topBar + 8.dp)
                .padding(start = FocusDimens.side, end = 8.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                    .heightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) {
                        heading()
                        contentDescription = "$userLabel, $warehouseCode $warehouseName, " + if (online) "online" else "offline"
                    },
            ) {
                // Joined badge (user, 26 Nov 2026): one outlined badge, the warehouse code on a navy
                // end and its name on the white part - no avatar, no online mark. The name wraps and,
                // past two lines, gets smaller down to 14 sp. Never cut off.
                var nameSize by remember(warehouseName) { mutableStateOf(16f) }
                val badge = RoundedCornerShape(14.dp)
                Row(
                    Modifier
                        .weight(1f)
                        .height(IntrinsicSize.Min)
                        .clip(badge)
                        .border(1.5.dp, FocusColors.navy, badge)
                        .background(FocusColors.surface)
                        .tourTarget("connection"),
                ) {
                    if (warehouseCode.isNotBlank()) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxHeight()
                                .background(FocusColors.navy)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                warehouseCode.uppercase(),
                                style = FocusType.rowCode.copy(fontSize = 14.sp, lineHeight = 17.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold),
                                color = FocusColors.surface,
                            )
                        }
                    }
                    Text(
                        if (warehouseName.isNotBlank()) warehouseName else warehouseCode,
                        style = FocusType.rowTitle.copy(fontSize = nameSize.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold),
                        color = FocusColors.ink,
                        lineHeight = (nameSize + 4f).sp,
                        onTextLayout = { layout -> if (layout.lineCount > 2 && nameSize > 14f) nameSize -= 1f },
                        modifier = Modifier
                            .weight(1f)
                            .align(Alignment.CenterVertically)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
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
                // Feedback lives here only (user, 15 Nov 2026), with pictures from the gallery.
                onFeedback?.let { feedback ->
                    FocusMenuItem(Icons.AutoMirrored.Filled.Chat, "Send feedback") { menuOpen = false; feedback() }
                },
                onSignOut?.let { signOut ->
                    FocusMenuItem(Icons.AutoMirrored.Filled.Logout, "Sign out", color = FocusColors.badIcon) { menuOpen = false; signOut() }
                },
            ),
        )

        band?.let { ResultBand(it, modifier = Modifier.padding(bottom = 12.dp), onDismiss = onBandDismiss) }

        val area = LocalHomeArea.current
        if (area != null) {
            HomeOnHandPanel(
                icon = Icons.Filled.Inventory2,
                onSubmit = onPanelSubmit,
                modifier = Modifier.padding(horizontal = FocusDimens.side).padding(top = 4.dp, bottom = 8.dp).tourTarget("tab_onhand"),
            )
            restoreCard(Modifier.padding(horizontal = FocusDimens.side, vertical = 4.dp))
            val selected = LocalHomeSelected.current
            Row(Modifier.fillMaxWidth().tourTarget("tiles")) {
                rows.forEach { row ->
                    val on = row.title == selected
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .background(if (on) FocusColors.infoBg.copy(alpha = 0.6f) else FocusColors.page)
                            .clickable(role = Role.Tab, onClick = row.onClick)
                            .padding(vertical = 12.dp),
                    ) {
                        androidx.compose.material3.Icon(row.icon, contentDescription = null, tint = if (row.muted) FocusColors.muted else FocusColors.navy, modifier = Modifier.size(26.dp))
                        Text(
                            row.title + (row.count?.let { " $it" } ?: ""),
                            style = FocusType.rowDetail.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold),
                            color = if (row.alert && row.count != null) FocusColors.badIcon else FocusColors.ink,
                        )
                    }
                }
            }
            androidx.compose.material3.HorizontalDivider(color = FocusColors.divider)
            Box(Modifier.weight(1f).fillMaxWidth()) { area() }
            return@Column
        }

        // The On-hand card stays put; only what is below it scrolls (user, 16 Nov 2026).
        HomeOnHandPanel(
            icon = Icons.Filled.Inventory2,
            onSubmit = onPanelSubmit,
            modifier = Modifier
                .padding(horizontal = FocusDimens.side)
                .padding(top = 4.dp)
                .tourTarget("tab_onhand"),
        )
        Column(
            modifier = Modifier
                .padding(top = 8.dp)
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .verticalScroll(rememberScrollState()),
        ) {
            // Work saved in D365FO from another device or an earlier install of this app.
            restoreCard(Modifier.padding(horizontal = FocusDimens.side, vertical = 8.dp))
            Column(Modifier.tourTarget("tiles")) {
                val selected = LocalHomeSelected.current
                rows.forEach { row ->
                    HomeRow(
                        modifier = (if (row.title == selected) Modifier.background(FocusColors.infoBg.copy(alpha = 0.6f)) else Modifier)
                            .tourTarget("row_${row.title}"),
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

/**
 * The header circle: [initials] on navy, a ring that says online (solid green) or offline (dashed
 * grey), and [tag] - the warehouse code - on an orange tag over its lower right corner.
 */
@Composable
// 46 dp, was 50 (user, 26 Nov 2026: smaller for the store name's width; 40 was too small).
internal fun StatusAvatar(initials: String, tag: String, online: Boolean, modifier: Modifier = Modifier) {
    val ok = FocusColors.okIcon
    val off = FocusColors.iconMuted
    Box(modifier = modifier.padding(end = 10.dp, bottom = 6.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(46.dp)
                .drawBehind {
                    val stroke = 2.dp.toPx()
                    val radius = size.minDimension / 2 - stroke / 2
                    drawCircle(
                        color = if (online) ok else off,
                        radius = radius,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke,
                            pathEffect = if (online) null else androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                        ),
                    )
                },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(FocusColors.navy),
            ) {
                Text(initials, style = FocusType.rowTitle.copy(fontSize = 14.sp), color = FocusColors.surface)
            }
        }
        if (tag.isNotBlank()) {
            Text(
                tag,
                style = FocusType.rowCode.copy(fontSize = 12.sp, lineHeight = 14.sp),
                color = FocusColors.surface,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 10.dp, y = 6.dp)
                    .border(2.dp, FocusColors.page, androidx.compose.foundation.shape.RoundedCornerShape(7.dp))
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(7.dp))
                    .background(FocusColors.action)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

/** Home with sample figures - previews and the layout test. [band]: the state after a bad scan. */
@Composable
internal fun FocusHomeSample(band: Boolean = false) {
    FocusHomeLayout(
        initials = "AR",
        warehouseCode = "AAC",
        warehouseName = "BSP - American Academy of Cosmetic Surgery Hospital",
        online = !band,
        band = if (band) BandState(BandKind.Bad, "Not recognised", "XJ-55-AB · no item, order or location") else null,
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

/** Home's rows in order: Receive, Ship, Count, Create, Activity, Enquiry - see MenuIconsTest. */
internal val HOME_ICONS = listOf(
    LineIcons.Receive, LineIcons.Ship, LineIcons.Count, LineIcons.Create, LineIcons.ActivityThin, LineIcons.SearchThin,
)

/**
 * Count control is on Home for a cycle count supervisor whose selected store is their default one
 * - flagged default, or their only store (as D365FO's HSMobileOpsUserWarehouse::findDefault reads it).
 */
internal fun showsCountControl(session: com.d365fo.mobileops.data.repository.WorkerSession?): Boolean {
    val selected = session?.selected ?: return false
    if (!session.isCycleCountSupervisor) return false
    val default = session.warehouses.firstOrNull { it.isDefault } ?: session.warehouses.singleOrNull()
    return default != null && default.warehouseId.equals(selected.warehouseId, ignoreCase = true)
}
