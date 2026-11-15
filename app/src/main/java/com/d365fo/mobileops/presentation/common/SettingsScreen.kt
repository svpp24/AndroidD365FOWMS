package com.d365fo.mobileops.presentation.common

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Dns
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import com.d365fo.mobileops.presentation.common.components.bleed
import com.d365fo.mobileops.presentation.common.components.LineIcons
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Warehouse
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.LaunchedEffect
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton as OutlinedButton
import com.d365fo.mobileops.presentation.common.components.SquareTextButton as TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.BuildConfig
import com.d365fo.mobileops.R
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.d365fo.mobileops.data.local.entity.WorkerDirectoryEntity
import com.d365fo.mobileops.data.local.entity.WorkerPermissionEntity
import com.d365fo.mobileops.data.logging.LogEntry
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import kotlinx.coroutines.flow.Flow
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.d365fo.mobileops.data.security.DisplayScale
import com.d365fo.mobileops.data.security.IdleTimeout
import com.d365fo.mobileops.data.security.TenantConfig
import com.d365fo.mobileops.presentation.common.components.CancelButton
import com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton
import com.d365fo.mobileops.presentation.common.components.ConfirmButton
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.theme.ColorPalette
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * Connection and demo-bench settings.
 *
 * The tenant is entered here rather than compiled in. Commissioning a fleet means pointing
 * handhelds at UAT, then Production, then occasionally back; baking that into the APK turns
 * every change into a rebuild, a re-sign and an MDM redeploy.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSignOut: (() -> Unit)? = null,
    onChangeWarehouse: () -> Unit = {},
    /** Opens Send feedback - signed in only. */
    onFeedback: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val tenant by viewModel.tenant.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    // A connection change that would wipe work not yet in D365FO asks first - see guardUnsent.
    val unsentBlock by viewModel.unsentBlock.collectAsStateWithLifecycle()
    unsentBlock?.let { block ->
        com.d365fo.mobileops.presentation.common.components.UnsentWorkDialog(
            description = block.description,
            onKeep = viewModel::keepUnsent,
            onDelete = viewModel::deleteUnsentAndProceed,
        )
    }
    val postingEnabled by viewModel.postingEnabled.collectAsStateWithLifecycle()
    val postImmediately by viewModel.postImmediately.collectAsStateWithLifecycle()
    val worker by viewModel.worker.collectAsStateWithLifecycle()
    val signedInAs by viewModel.signedInAs.collectAsStateWithLifecycle()
    val test by viewModel.test.collectAsStateWithLifecycle()
    val credentialStatus by viewModel.credentialStatus.collectAsStateWithLifecycle()
    val displayScale by viewModel.displayScale.collectAsStateWithLifecycle()
    val idleTimeout by viewModel.idleTimeout.collectAsStateWithLifecycle()

    // Which submenu is open, or null for the top-level list. Local Compose state, not a nav
    // route - Settings is reached two different ways (AppRoot's pre-sign-in gate and the
    // signed-in nav graph) and this way neither caller needs to know submenus exist.
    var openSection by remember { mutableStateOf<SettingsMenuItem?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    // Signed in, the top level is the More tab (20 Oct restyle): the greeting bar, no back arrow.
    // Reached from Home's menu (Focus design), never a tab: always a back arrow.
    val isTab = false

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Posted work still goes to D365FO. Unposted scans stay here.") },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = { confirmSignOut = false }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
                    confirm = { com.d365fo.mobileops.presentation.common.components.CancelButton(onClick = { confirmSignOut = false; onSignOut?.invoke() }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Sign out") } },
                )
            },
        )
    }

    var confirmDeleteAll by remember { mutableStateOf(false) }
    if (confirmDeleteAll) {
        var unsent by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { unsent = viewModel.unsentDescription() }
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all app data?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Orders, counts, the item master, offline sign-ins and sync history are " +
                            "removed from this device, and you are signed out. Nothing in D365FO is " +
                            "changed. The connection setup is kept.",
                    )
                    unsent?.let {
                        Text(
                            "Not sent to D365FO yet, and lost: $it",
                            color = WarehouseStatusColors.red,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        )
                    }
                }
            },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = { confirmDeleteAll = false }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Keep") } },
                    confirm = {
                        com.d365fo.mobileops.presentation.common.components.CancelButton(
                            onClick = {
                                confirmDeleteAll = false
                                viewModel.deleteAllData { onSignOut?.invoke() }
                            },
                            modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                        ) { Text("Delete") }
                    },
                )
            },
        )
    }

    // A section's back arrow returns to the Settings list; the list's to where it was opened.
    androidx.activity.compose.BackHandler(enabled = openSection != null) { openSection = null }
    // Focus list style throughout (user, 15 Nov 2026): captions, rows with icons and chevrons,
    // results in a band at the top. Connection & profile has its Scan button pinned at the bottom.
    var scanBand by remember { mutableStateOf<com.d365fo.mobileops.presentation.common.focus.BandState?>(null) }
    var labelsBeforeScan by remember { mutableStateOf<Set<String>>(emptySet()) }
    val connectionOpen = openSection == SettingsMenuItem.ConnectionProfile && onSignOut == null
    com.d365fo.mobileops.presentation.common.focus.FocusScreen(
        title = openSection?.title ?: "Settings",
        onBack = if (isTab && openSection == null) null else { { if (openSection != null) openSection = null else onBack() } },
        counter = if (connectionOpen) profiles.size.takeIf { it > 0 }?.toString() else null,
        band = if (connectionOpen) scanBand else null,
        actions = if (connectionOpen) {
            {
                LaunchedEffect(Unit) { labelsBeforeScan = profiles.map { it.label }.toSet() }
                ConnectionQrSetup(
                    modifier = Modifier.weight(1f),
                    onConnected = {
                        val now = viewModel.tenant.value
                        scanBand = com.d365fo.mobileops.presentation.common.focus.BandState(
                            com.d365fo.mobileops.presentation.common.focus.BandKind.Ok,
                            "${now.label} " + (if (now.label in labelsBeforeScan) "updated" else "added") + " · in use",
                            profileLine(now),
                            autoHideMs = 4_000L,
                        )
                        labelsBeforeScan = viewModel.profiles.value.map { it.label }.toSet()
                    },
                )
            }
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            when (openSection) {
                null -> {
                    SettingsMenu(
                        postingEnabled = postingEnabled,
                        postImmediately = postImmediately,
                        tenant = tenant,
                        displayScale = displayScale,
                        idleTimeout = idleTimeout,
                        showConnectionProfile = onSignOut == null,
                        showSignedInSections = onSignOut != null,
                        onOpen = { if (it == SettingsMenuItem.Feedback) onFeedback?.invoke() else openSection = it },
                    )
                    if (onSignOut != null) {
                        RedOutlineButton(
                            "Sign out",
                            onClick = { confirmSignOut = true },
                            modifier = Modifier.padding(start = SIDE, end = SIDE, top = 20.dp),
                        )
                        // Wipes this device's work data, then signs out (user, 9 Nov). Asks first and
                        // says what unsent work would be lost.
                        Text(
                            "Delete all app data",
                            style = com.d365fo.mobileops.presentation.common.theme.FocusType.rowTitle,
                            color = com.d365fo.mobileops.presentation.common.theme.FocusColors.badIcon,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = WarehouseDimens.MinTouchTarget)
                                .clickable(role = Role.Button) { confirmDeleteAll = true }
                                .wrapContentHeight(Alignment.CenterVertically),
                        )
                    }
                    // Which build this is - what support asks first, and what D365FO's device list records.
                    Text(
                        text = com.d365fo.mobileops.util.AppVersion.full,
                        style = com.d365fo.mobileops.presentation.common.theme.FocusType.rowDetail,
                        color = com.d365fo.mobileops.presentation.common.theme.FocusColors.iconMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_app_logo),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(44.dp).padding(top = 4.dp),
                    )
                }

                // Connection setup only ever appears when this screen was reached from the
                // sign-in gate: re-pointing a device is commissioning, not a mid-shift setting.
                SettingsMenuItem.ConnectionProfile -> if (onSignOut == null) {
                    ConnectionProfiles(
                        profiles = profiles,
                        activeLabel = tenant.label,
                        deviceCompany = worker.dataAreaId,
                        test = test,
                        viewModel = viewModel,
                    )
                    // Commissioning switches for this device (moved here from the Settings tab).
                    Caption("This device")
                    ToggleRow(
                        title = "Allow posting to D365FO" + tenant.label.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                        detail = if (postingEnabled) "Work is sent to D365FO." else "Off: work is held here until switched on.",
                        checked = postingEnabled,
                        onToggle = { viewModel.setPostingEnabled(!postingEnabled) },
                    )
                    ConnectionReset(
                        hasCredentials = profiles.any { it.clientId.isNotBlank() || it.tenantId.isNotBlank() || it.usesTokenService },
                        viewModel = viewModel,
                    )
                }

                SettingsMenuItem.Appearance -> if (onSignOut == null) {
                    Caption("Text size")
                    DisplayScaleRow(selected = displayScale, onSelect = viewModel::setDisplayScale)
                    Note("Applies straight away, for this worker.")
                }

                SettingsMenuItem.Lockout -> if (onSignOut == null) {
                    Caption("Lock after")
                    IdleTimeoutSlider(selected = idleTimeout, onSelect = viewModel::setIdleTimeout)
                }

                SettingsMenuItem.Session -> if (onSignOut != null) {
                    // Read-only - a worker works the warehouse D365FO assigned them.
                    val switcher: WarehouseSwitcherViewModel = hiltViewModel()
                    val session by switcher.session.collectAsStateWithLifecycle()
                    Caption("Assigned warehouse")
                    session?.selected?.let { wh ->
                        WarehouseRow(warehouse = wh, current = false, pill = null, onClick = onChangeWarehouse)
                    } ?: Note(worker.warehouseId.ifBlank { "No warehouse - ask a supervisor" })
                    Caption("Session")
                    com.d365fo.mobileops.presentation.common.focus.FocusListRow(
                        title = signedInAs.ifBlank { "Signed in" },
                        icon = androidx.compose.material.icons.Icons.Filled.Person,
                    )
                    // The navigation tour shows once per worker; this brings it back on Home.
                    val tour: com.d365fo.mobileops.presentation.common.tour.TourViewModel = hiltViewModel()
                    com.d365fo.mobileops.presentation.common.focus.FocusListRow(
                        title = "Show the app tour again",
                        icon = androidx.compose.material.icons.Icons.Filled.Route,
                        chevron = true,
                        onClick = { tour.replay(); onBack() },
                    )
                    OfflineSection(warehouseId = worker.warehouseId)
                }

                // Always reachable, regardless of sign-in or license state - see LogStore.
                SettingsMenuItem.Logs -> LogsSection()

                // Before or after sign-in: a copy in D365FO, or a PIN-protected file (29 Oct).
                SettingsMenuItem.UnsentWork -> com.d365fo.mobileops.presentation.recovery.UnsentWorkPanel()

                // Signed in, so it can be changed during a shift (feedback 301e5d0e).
                SettingsMenuItem.Posting -> if (onSignOut != null) {
                    Caption("Posting")
                    ToggleRow(
                        title = "Post immediately",
                        detail = if (postImmediately) "On: posts while you wait; a refusal shows at once." else "Off: posts in the background within minutes.",
                        checked = postImmediately,
                        onToggle = { viewModel.setPostImmediately(!postImmediately) },
                    )
                    if (!postingEnabled) {
                        com.d365fo.mobileops.presentation.common.focus.ResultBand(
                            com.d365fo.mobileops.presentation.common.focus.BandState(
                                com.d365fo.mobileops.presentation.common.focus.BandKind.Warn,
                                "Posting is off",
                                "Work is held here. Turn it on in Connection settings.",
                            ),
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }

                SettingsMenuItem.Feedback -> Unit
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

private val SIDE = 20.dp

/** An upper-case caption over a group of rows. */
@Composable
private fun Caption(text: String) {
    com.d365fo.mobileops.presentation.common.focus.FocusCaption(
        text,
        modifier = Modifier.padding(start = SIDE, end = SIDE, top = 18.dp, bottom = 6.dp),
    )
}

/** A short grey line under a group. */
@Composable
private fun Note(text: String) {
    Text(
        text,
        style = com.d365fo.mobileops.presentation.common.theme.FocusType.rowDetail,
        color = com.d365fo.mobileops.presentation.common.theme.FocusColors.muted,
        modifier = Modifier.padding(horizontal = SIDE, vertical = 8.dp),
    )
}

/** Red text in a red outline - Sign out. */
@Composable
private fun RedOutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val red = com.d365fo.mobileops.presentation.common.theme.FocusColors.badIcon
    Text(
        text,
        style = com.d365fo.mobileops.presentation.common.theme.FocusType.button,
        color = red,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = com.d365fo.mobileops.presentation.common.focus.FocusDimens.button)
            .clip(com.d365fo.mobileops.presentation.common.theme.FocusShapes.button)
            .border(2.dp, red, com.d365fo.mobileops.presentation.common.theme.FocusShapes.button)
            .background(com.d365fo.mobileops.presentation.common.theme.FocusColors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .wrapContentHeight(Alignment.CenterVertically),
    )
}

/** "contoso-dev · BSP · Device key" - a profile in one line. */
internal fun profileLine(profile: TenantConfig, deviceCompany: String = ""): String = listOfNotNull(
    profile.host.substringBefore('.').ifBlank { null },
    profile.company.ifBlank { deviceCompany }.ifBlank { null },
    when {
        profile.usesTokenService -> "Device key"
        profile.credentials.any { it.isComplete } -> "App registration"
        else -> null
    },
).joinToString(" · ")

/**
 * One entry in [SettingsMenu] - deliberately just a title, not an icon or a description. This
 * app's Settings screen is small enough (four or five entries, fewer while signed out) that a
 * plain labelled row reads faster than a grid of icons would.
 */
private enum class SettingsMenuItem(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    ConnectionProfile("Connection & profile", LineIcons.Link),
    Appearance("Appearance", LineIcons.Palette),
    Session("My warehouse & sign-in", LineIcons.Warehouse),
    Lockout("Lockout settings", LineIcons.Lock),
    Logs("Logs", LineIcons.About),
    Posting("Data posting", LineIcons.Posting),
    Feedback("Send feedback", LineIcons.Feedback),
    UnsentWork("Unsent work", LineIcons.Upload),
}

/**
 * The top level of Settings - one row per [SettingsMenuItem], filtered to what's actually
 * reachable in the current sign-in state (see each `if` in [SettingsScreen] for why a given
 * item only shows before or after sign-in). Logs is the one entry never filtered out - see
 * [LogStore][com.d365fo.mobileops.data.logging.LogStore]'s own doc on why it has to stay reachable
 * even when everything else on this screen is exactly what someone is trying to diagnose.
 */
@Composable
private fun SettingsMenu(
    postingEnabled: Boolean,
    postImmediately: Boolean,
    tenant: TenantConfig,
    displayScale: DisplayScale,
    idleTimeout: IdleTimeout,
    showConnectionProfile: Boolean,
    showSignedInSections: Boolean,
    onOpen: (SettingsMenuItem) -> Unit,
) {
    // Rows with a navy icon, what each is set to under it, and a chevron (user, 15 Nov 2026).
    val switcher: WarehouseSwitcherViewModel = hiltViewModel()
    val session by switcher.session.collectAsStateWithLifecycle()
    val work: com.d365fo.mobileops.presentation.recovery.WorkSaveViewModel = hiltViewModel()
    val workState by work.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { work.refreshSummary() }
    val values = mapOf(
        SettingsMenuItem.ConnectionProfile to listOfNotNull(tenant.label.ifBlank { null }, tenant.company.ifBlank { null }).joinToString(" · "),
        SettingsMenuItem.Appearance to "Text size ${displayScale.label}",
        SettingsMenuItem.Lockout to "After ${idleTimeout.label}",
        SettingsMenuItem.Session to listOfNotNull(session?.selected?.warehouseId, "offline use").joinToString(" · "),
        SettingsMenuItem.Posting to if (!postingEnabled) "Posting is off" else "Post immediately: " + if (postImmediately) "on" else "off",
        SettingsMenuItem.UnsentWork to (workState.unsent ?: "Nothing unsent"),
    )
    val items = buildList {
        if (showConnectionProfile) addAll(listOf(SettingsMenuItem.ConnectionProfile, SettingsMenuItem.Appearance, SettingsMenuItem.Lockout))
        if (showSignedInSections) {
            addAll(listOf(SettingsMenuItem.Session, SettingsMenuItem.Posting, SettingsMenuItem.UnsentWork, SettingsMenuItem.Feedback, SettingsMenuItem.Logs))
        } else {
            addAll(listOf(SettingsMenuItem.UnsentWork, SettingsMenuItem.Logs))
        }
    }
    // Who and where - only when someone is signed in.
    val who = listOfNotNull(
        session?.userName?.takeIf { it.isNotBlank() && showSignedInSections },
        session?.selected?.warehouseId?.takeIf { showSignedInSections },
    ).joinToString(" · ")
    if (who.isNotBlank()) Caption(who) else Spacer(Modifier.height(12.dp))
    androidx.compose.material3.HorizontalDivider(color = com.d365fo.mobileops.presentation.common.theme.FocusColors.divider)
    items.forEach { item ->
        com.d365fo.mobileops.presentation.common.focus.FocusListRow(
            title = item.title,
            icon = item.icon,
            subtitle = values[item]?.takeIf { it.isNotBlank() },
            chevron = true,
            onClick = { onOpen(item) },
        )
    }
}

/**
 * The log viewer content - see [com.d365fo.mobileops.data.logging.LogStore] for what's actually
 * captured. Newest entry at the top: reading "what just happened" is the usual reason to open
 * this, not replaying a session from the start.
 *
 * A table, not a wall of monospace text - a fixed time/level column plus a one-line message
 * summary, tapped open for the full message and stack trace. Skimming a hundred lines for the
 * one that matters is the actual use case; a table of short rows supports that the way a block
 * of wrapped paragraphs does not.
 */
@Composable
private fun LogsSection(viewModel: LogViewerViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var shown by remember { mutableStateOf(LOG_PAGE) }

    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(start = SIDE, end = SIDE, top = 12.dp, bottom = 8.dp),
        ) {
            com.d365fo.mobileops.presentation.common.focus.FocusButton(
                "Copy all",
                onClick = {
                    clipboard.setText(AnnotatedString(entries.joinToString("\n") { it.toLogLine() }))
                    Toast.makeText(context, "Copied ${entries.size} log lines", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
            )
            RedOutlineButton("Clear", onClick = { viewModel.clear(); expandedId = null }, modifier = Modifier.weight(1f))
        }
        Caption("Newest first · ${entries.size}")
        androidx.compose.material3.HorizontalDivider(color = com.d365fo.mobileops.presentation.common.theme.FocusColors.divider)

        if (entries.isEmpty()) {
            Note("No log entries yet.")
        } else {
            // Newest first, and not virtualized (LazyColumn) - this whole screen already sits
            // inside SettingsScreen's own verticalScroll, and nesting a scrollable list inside
            // another with unbounded height needs exactly the height constraints a LazyColumn
            // does not get for free here. So only a page is composed at a time: LogStore keeps
            // 2 MB (~7000 entries), and composing all of them froze the handheld for 46 s
            // ("isn't responding", 10 Oct).
            // Copy all still copies everything.
            entries.asReversed().take(shown).forEach { entry ->
                LogRow(
                    entry = entry,
                    expanded = entry.id == expandedId,
                    onToggle = { expandedId = if (expandedId == entry.id) null else entry.id },
                )
            }
            if (entries.size > shown) {
                com.d365fo.mobileops.presentation.common.focus.FocusButton(
                    "Show ${minOf(LOG_PAGE, entries.size - shown)} more (${entries.size - shown} older)",
                    onClick = { shown += LOG_PAGE },
                    modifier = Modifier.fillMaxWidth().padding(SIDE),
                )
            }
        }
    }
}

/** Log rows composed per page - see LogsSection. */
private const val LOG_PAGE = 200

private val LogTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

@Composable
private fun LogTableHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    ) {
        Text(
            text = "Time",
            style = MaterialTheme.typography.labelSmall,
            color = WarehouseStatusColors.text3,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = "Lvl",
            style = MaterialTheme.typography.labelSmall,
            color = WarehouseStatusColors.text3,
            modifier = Modifier.width(28.dp),
        )
        Text(
            text = "Message",
            style = MaterialTheme.typography.labelSmall,
            color = WarehouseStatusColors.text3,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * One table row: always-visible summary (time, level, tag + first line of the message), tapped
 * to reveal the full detail below it in place - no navigation, no separate screen, since the
 * point is comparing a row against the ones around it while scanning.
 */
@Composable
private fun LogRow(entry: LogEntry, expanded: Boolean, onToggle: () -> Unit) {
    val colors = com.d365fo.mobileops.presentation.common.theme.FocusColors
    val type = com.d365fo.mobileops.presentation.common.theme.FocusType
    val (word, color) = when (entry.levelLabel) {
        "E", "A" -> "ERROR" to colors.badIcon
        "W" -> "WARN" to colors.warnText
        "I" -> "INFO" to colors.okText
        else -> "DEBUG" to colors.muted
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (expanded) colors.infoBg.copy(alpha = 0.4f) else colors.page)
            .clickable(onClick = onToggle)
            .padding(horizontal = SIDE, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                text = LogTimeFormatter.format(entry.timestamp),
                style = type.rowDetail.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                color = colors.muted,
                modifier = Modifier.width(72.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(word, style = type.caption.copy(fontSize = 11.sp), color = color)
                // Wrapped, never cut off (the app's rule); a tap adds the full time and stack trace.
                Text("${entry.tag}: ${entry.message}", style = type.rowDetail, color = colors.ink)
            }
        }
        if (expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 84.dp)) {
                Text(entry.timestamp.toString(), style = type.rowDetail.copy(fontFamily = FontFamily.Monospace), color = colors.muted)
                entry.throwable?.let {
                    Text(it, style = type.rowDetail.copy(fontFamily = FontFamily.Monospace), color = colors.badIcon, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
    androidx.compose.material3.HorizontalDivider(color = colors.divider)
}

private fun LogEntry.toLogLine(): String =
    "$timestamp $levelLabel/$tag: $message"

/**
 * The saved connection profiles (Dev / UAT / Prod, or any custom name), one chip each -
 * selected = the currently active one. Tapping a different one asks to confirm before switching,
 * since a switch wipes local data; tapping the active one does nothing, there is nothing to
 * confirm about staying put. A trailing "+" chip opens a small dialog to name and add a new
 * profile. Horizontally scrollable rather than wrapped: a handheld's width comfortably fits
 * three or four chips before it needs to, and a fixed single row keeps this from pushing the
 * rest of the form down every time someone adds a profile.
 */
/**
 * Every saved profile as a card, the one in use first. The in-use card also shows which of its
 * app registrations this device uses and can test the connection; any card can be deleted, and
 * an unused one can be switched to. Switching or deleting the one in use clears order and item
 * data, so both ask first (and the view model checks for unsent work - see guardUnsent).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionProfiles(
    profiles: List<TenantConfig>,
    activeLabel: String,
    deviceCompany: String,
    test: ConnectionTest,
    viewModel: SettingsViewModel,
) {
    // Hazard-count style rows (user, 15 Nov 2026); a row opens a sheet with that profile's actions.
    var open by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    val colors = com.d365fo.mobileops.presentation.common.theme.FocusColors

    if (profiles.isEmpty()) {
        com.d365fo.mobileops.presentation.common.focus.ResultBand(
            com.d365fo.mobileops.presentation.common.focus.BandState(
                com.d365fo.mobileops.presentation.common.focus.BandKind.Info,
                "No profile yet",
                "Scan a connection QR code.",
            ),
            modifier = Modifier.padding(top = 16.dp),
        )
    }
    val active = profiles.firstOrNull { it.label == activeLabel }
    val others = profiles.filter { it.label != activeLabel }
    fun hasSignIn(p: TenantConfig) = p.usesTokenService || p.credentials.any { it.isComplete }
    active?.let { profile ->
        Caption("In use")
        androidx.compose.material3.HorizontalDivider(color = colors.divider)
        com.d365fo.mobileops.presentation.common.focus.FocusListRow(
            title = profile.label,
            titleIsCode = true,
            state = if (hasSignIn(profile)) com.d365fo.mobileops.presentation.common.focus.RowState.Done else com.d365fo.mobileops.presentation.common.focus.RowState.Warn,
            subtitle = profileLine(profile, deviceCompany) + when (test) {
                is ConnectionTest.Ok -> "\nConnected"
                is ConnectionTest.Failed -> "\nNot connected"
                else -> ""
            },
            chevron = true,
            onClick = { open = profile.label },
        )
    }
    if (others.isNotEmpty()) {
        Caption("Other profiles")
        androidx.compose.material3.HorizontalDivider(color = colors.divider)
        others.forEach { profile ->
            com.d365fo.mobileops.presentation.common.focus.FocusListRow(
                title = profile.label,
                titleIsCode = true,
                state = if (hasSignIn(profile)) com.d365fo.mobileops.presentation.common.focus.RowState.Plain else com.d365fo.mobileops.presentation.common.focus.RowState.Warn,
                icon = if (hasSignIn(profile)) androidx.compose.material.icons.Icons.Filled.Dns else null,
                subtitle = profileLine(profile) + if (hasSignIn(profile)) "" else "\nNo sign-in details. Scan its QR code.",
                chevron = true,
                onClick = { open = profile.label },
            )
        }
    }

    open?.let { label ->
        val profile = profiles.firstOrNull { it.label == label }
        if (profile == null) {
            open = null
            return@let
        }
        val inUse = label == activeLabel
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { open = null },
            containerColor = colors.surface,
        ) {
            ProfileSheet(
                profile = profile,
                inUse = inUse,
                activeLabel = activeLabel,
                deviceCompany = deviceCompany,
                test = test,
                signInMissing = !hasSignIn(profile),
                onTest = { viewModel.testConnection(profile) },
                onUse = { open = null; viewModel.switchProfile(label) },
                onDelete = { deleteTarget = label },
            )
        }
    }

    deleteTarget?.let { label ->
        val isActive = label == activeLabel
        val next = profiles.firstOrNull { it.label != label }?.label
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete $label?") },
            text = {
                Text(
                    "Removes $label from this device." +
                        if (isActive) {
                            " " + (next?.let { "Switches to $it" } ?: "No connection until a QR code is scanned") +
                                "; data here is cleared."
                        } else {
                            ""
                        },
                )
            },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = { deleteTarget = null }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Keep") } },
                    confirm = { com.d365fo.mobileops.presentation.common.components.CancelButton(onClick = {
                        deleteTarget = null
                        open = null
                        viewModel.deleteProfile(label)
                    }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Delete") } },
                )
            },
        )
    }
}

/** A profile's sheet: its details, the last test, and what can be done with it. */
@Composable
private fun ProfileSheet(
    profile: TenantConfig,
    inUse: Boolean,
    activeLabel: String,
    deviceCompany: String,
    test: ConnectionTest,
    signInMissing: Boolean,
    onTest: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = com.d365fo.mobileops.presentation.common.theme.FocusColors
    val type = com.d365fo.mobileops.presentation.common.theme.FocusType
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = SIDE, end = SIDE, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(profile.label, style = type.rowCode.copy(fontSize = 28.sp), color = colors.ink, modifier = Modifier.weight(1f))
            if (inUse) {
                Text(
                    "IN USE",
                    style = type.caption.copy(fontSize = 11.sp),
                    color = colors.okText,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(colors.okBg).padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
        }
        SheetValue("Environment", profile.host.ifBlank { "Not set" })
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            SheetValue("Legal entity", profile.company.ifBlank { if (inUse) deviceCompany.ifBlank { "Not set" } else "Not set" })
            SheetValue(
                "Sign-in",
                when {
                    profile.usesTokenService -> "Device key"
                    profile.credentials.any { it.isComplete } -> "App registration"
                    else -> "None"
                },
            )
        }
        if (signInMissing) {
            com.d365fo.mobileops.presentation.common.focus.ResultBand(
                com.d365fo.mobileops.presentation.common.focus.BandState(
                    com.d365fo.mobileops.presentation.common.focus.BandKind.Warn,
                    "No sign-in details",
                    "Scan a connection QR code for ${profile.host.substringBefore('.')}.",
                ),
                modifier = Modifier.padding(horizontal = -SIDE),
            )
        } else if (inUse) {
            when (test) {
                ConnectionTest.Idle -> Unit
                ConnectionTest.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("Contacting D365FO…", style = type.rowDetail, color = colors.muted)
                }
                is ConnectionTest.Ok -> com.d365fo.mobileops.presentation.common.focus.ResultBand(
                    com.d365fo.mobileops.presentation.common.focus.BandState(com.d365fo.mobileops.presentation.common.focus.BandKind.Ok, "Connected", test.detail),
                    modifier = Modifier.padding(horizontal = -SIDE),
                )
                is ConnectionTest.Failed -> com.d365fo.mobileops.presentation.common.focus.ResultBand(
                    com.d365fo.mobileops.presentation.common.focus.BandState(com.d365fo.mobileops.presentation.common.focus.BandKind.Bad, "Not connected", test.reason),
                    modifier = Modifier.padding(horizontal = -SIDE),
                )
            }
        } else {
            Text(
                "Switching saves unsent work, then clears this device's $activeLabel data.",
                style = type.rowDetail,
                color = colors.muted,
            )
        }
        Spacer(Modifier.height(4.dp))
        when {
            inUse && !signInMissing -> com.d365fo.mobileops.presentation.common.focus.FocusButton(
                "Test connection",
                onClick = onTest,
                enabled = test != ConnectionTest.Running,
                modifier = Modifier.fillMaxWidth(),
            )
            signInMissing -> ConnectionQrSetup(modifier = Modifier.fillMaxWidth())
            else -> com.d365fo.mobileops.presentation.common.focus.FocusButton("Use this profile", onClick = onUse, primary = true, modifier = Modifier.fillMaxWidth())
        }
        RedOutlineButton("Delete", onClick = onDelete)
    }
}

@Composable
private fun SheetValue(label: String, value: String) {
    Column {
        Text(label, style = com.d365fo.mobileops.presentation.common.theme.FocusType.rowDetail, color = com.d365fo.mobileops.presentation.common.theme.FocusColors.muted)
        Text(
            value.replace(".", ".\u200B"),
            style = com.d365fo.mobileops.presentation.common.theme.FocusType.rowTitle,
            color = com.d365fo.mobileops.presentation.common.theme.FocusColors.ink,
        )
    }
}

/** Clears the credentials of every profile - see SettingsViewModel.resetConnection. Data stays. */
@Composable
private fun ConnectionReset(hasCredentials: Boolean, viewModel: SettingsViewModel) {
    var confirm by remember { mutableStateOf(false) }
    var unsent by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(confirm) { unsent = if (confirm) viewModel.unsentDescription() else null }

    com.d365fo.mobileops.presentation.common.focus.FocusListRow(
        title = "Reset connection",
        subtitle = "Clears secrets and offline sign-ins. Profiles and data stay.",
        icon = androidx.compose.material.icons.Icons.Filled.RestartAlt,
        onClick = if (hasCredentials) ({ confirm = true }) else null,
    )
    if (!confirm) return
    AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Reset connection?") },
        text = {
            Text(
                "Scan a connection QR code afterwards to use the device again." +
                    (unsent?.let { " $it stays here and is sent later." } ?: ""),
            )
        },
        confirmButton = {
            com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = { confirm = false }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
                confirm = { com.d365fo.mobileops.presentation.common.components.CancelButton(onClick = {
                confirm = false
                viewModel.resetConnection()
            }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Reset") } },
            )
        },
    )
}

/**
 * Three options fit one row comfortably - selected = filled / unselected = outlined, the same
 * visual language this app uses for any small fixed choice.
 */
@Composable
private fun DisplayScaleRow(
    selected: DisplayScale,
    onSelect: (DisplayScale) -> Unit,
) {
    val colors = com.d365fo.mobileops.presentation.common.theme.FocusColors
    Row(
        modifier = Modifier
            .padding(horizontal = SIDE)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.divider)
            .padding(4.dp),
    ) {
        DisplayScale.entries.forEach { option ->
            val on = option == selected
            Text(
                option.label,
                style = com.d365fo.mobileops.presentation.common.theme.FocusType.rowTitle,
                color = if (on) colors.navy else colors.muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = WarehouseDimens.MinTouchTarget)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (on) colors.surface else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(option) }
                    .wrapContentHeight(Alignment.CenterVertically),
            )
        }
    }
}

/**
 * [IdleTimeout]'s six values are evenly spaced 5-minute steps, exactly what a [Slider]'s discrete
 * `steps` is for - a drag snaps to the nearest of the six rather than landing on an odd number of
 * minutes no [IdleTimeout] entry represents.
 *
 * Local float state, committed via `onValueChangeFinished` rather than on every
 * `onValueChange` - dragging fires many times a second, and writing to SecureStore (and
 * restarting nothing, since the ViewModel only reads this on the next timer arm - see
 * WorkerSessionViewModel.armIdleTimer) on every frame would be pure waste for a value that only
 * matters once the thumb settles.
 */
@Composable
private fun IdleTimeoutSlider(
    selected: IdleTimeout,
    onSelect: (IdleTimeout) -> Unit,
) {
    var sliderValue by remember(selected) { mutableFloatStateOf(selected.minutes.toFloat()) }
    val minV = IdleTimeout.entries.first().minutes.toFloat()
    val maxV = IdleTimeout.entries.last().minutes.toFloat()
    val fraction = ((sliderValue - minV) / (maxV - minV)).coerceIn(0f, 1f)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = SIDE)) {
        Text(
            text = "${sliderValue.toInt()} min",
            style = com.d365fo.mobileops.presentation.common.theme.FocusType.rowCode.copy(fontSize = 44.sp, lineHeight = 48.sp),
            color = com.d365fo.mobileops.presentation.common.theme.FocusColors.navy,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "of no activity",
            style = com.d365fo.mobileops.presentation.common.theme.FocusType.rowDetail,
            color = com.d365fo.mobileops.presentation.common.theme.FocusColors.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = {
                val nearest = IdleTimeout.entries.minBy { kotlin.math.abs(it.minutes - sliderValue.toLong()) }
                sliderValue = nearest.minutes.toFloat()
                onSelect(nearest)
            },
            valueRange = minV..maxV,
            steps = IdleTimeout.entries.size - 2,
            // Square, filled, bordered - this app's own "square, not a stock circle" identity
            // (see SquareButtons.kt) applied to the one stock-Material control left using the
            // default. Bigger than the stock thumb too, easier to find and drag with gloves on.
            thumb = {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(com.d365fo.mobileops.presentation.common.theme.FocusColors.navy, CircleShape)
                        .border(3.dp, Color.White, CircleShape),
                )
            },
            // A segmented bar - a solid fill up to the current value plus a visible divider at
            // each of the six stops - rather than Material's thin pill with barely-visible dots,
            // so the six discrete values this control actually snaps to read as six segments,
            // not an illusion of a continuous range.
            track = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .background(com.d365fo.mobileops.presentation.common.theme.FocusColors.divider, RoundedCornerShape(5.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(10.dp)
                            .background(com.d365fo.mobileops.presentation.common.theme.FocusColors.navy, RoundedCornerShape(5.dp)),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        repeat(IdleTimeout.entries.size) {
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(10.dp)
                                    .background(Color.White.copy(alpha = 0.55f)),
                            )
                        }
                    }
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IdleTimeout.entries.forEach { option ->
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (option == selected) WarehouseStatusColors.accent else WarehouseStatusColors.text3,
                )
            }
        }
    }
}

@Composable
private fun TestResult(test: ConnectionTest) {
    when (test) {
        ConnectionTest.Idle -> Unit

        ConnectionTest.Running -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Text("Contacting D365FO…", style = MaterialTheme.typography.bodyMedium)
        }

        is ConnectionTest.Ok -> Card(
            colors = CardDefaults.cardColors(containerColor = WarehouseStatusColors.greenDim),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = test.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = WarehouseStatusColors.green,
                modifier = Modifier.padding(12.dp),
            )
        }

        is ConnectionTest.Failed -> ErrorBanner(message = test.reason)
    }
}

/**
 * One visually distinct block per settings group - a bordered white card standing out against
 * the screen's own light-grey background, replacing the flat list-with-dividers layout that
 * read as plain Material defaults rather than this app's own identity. Square corners (this
 * theme's own [com.d365fo.mobileops.presentation.common.theme.D365MobileTheme] shapes, not a
 * one-off here), flat fill, a real border for definition rather than a shadow - the same
 * language [WarehouseStatusColors.border] already draws card/field edges in everywhere else.
 */
/**
 * What works without a connection, and what is saved for it (see OfflinePrefetcher): Item data and
 * Open orders and counts, each a row with a refresh button; what happened shows in a band.
 */
@Composable
private fun OfflineSection(warehouseId: String?, viewModel: OfflineReadyViewModel = hiltViewModel()) {
    val readiness by viewModel.readiness.collectAsStateWithLifecycle()
    val mastersSync by viewModel.mastersSync.collectAsStateWithLifecycle()
    val online = com.d365fo.mobileops.presentation.common.components.LocalSyncStatus.current?.fullyOnline != false
    val syncing = mastersSync is com.d365fo.mobileops.data.sync.MasterSyncState.Running
    var tried by remember { mutableStateOf<String?>(null) }
    var wasSyncing by remember { mutableStateOf(false) }
    var synced by remember { mutableStateOf(false) }
    LaunchedEffect(syncing) {
        if (wasSyncing && !syncing && mastersSync !is com.d365fo.mobileops.data.sync.MasterSyncState.Failed) synced = true
        wasSyncing = syncing
    }
    val band = when {
        tried != null && !online -> com.d365fo.mobileops.presentation.common.focus.BandState(
            com.d365fo.mobileops.presentation.common.focus.BandKind.Bad, "$tried needs a connection",
        )
        syncing -> com.d365fo.mobileops.presentation.common.focus.BandState(
            com.d365fo.mobileops.presentation.common.focus.BandKind.Info, "Syncing item data…", "It carries on if you leave this screen.",
        )
        mastersSync is com.d365fo.mobileops.data.sync.MasterSyncState.Failed -> com.d365fo.mobileops.presentation.common.focus.BandState(
            com.d365fo.mobileops.presentation.common.focus.BandKind.Bad, "Item data not synced",
            (mastersSync as com.d365fo.mobileops.data.sync.MasterSyncState.Failed).reason,
        )
        synced -> com.d365fo.mobileops.presentation.common.focus.BandState(
            com.d365fo.mobileops.presentation.common.focus.BandKind.Ok, "Item data synced", autoHideMs = 4_000L,
        )
        readiness.error != null -> com.d365fo.mobileops.presentation.common.focus.BandState(
            com.d365fo.mobileops.presentation.common.focus.BandKind.Bad, "Open orders and counts not saved", readiness.error,
        )
        else -> null
    }
    Caption("Offline use")
    band?.let {
        if (it.autoHideMs != null) com.d365fo.mobileops.presentation.common.focus.TimedResultBand(it, modifier = Modifier.padding(bottom = 10.dp))
        else com.d365fo.mobileops.presentation.common.focus.ResultBand(it, modifier = Modifier.padding(bottom = 10.dp))
    }
    androidx.compose.material3.HorizontalDivider(color = com.d365fo.mobileops.presentation.common.theme.FocusColors.divider)
    com.d365fo.mobileops.presentation.modules.cyclecounting.ItemDataRow(
        syncing = syncing,
        onSync = { tried = "Syncing"; if (online) viewModel.syncMastersNow(full = false) },
        onFullSync = { tried = "Syncing"; if (online) viewModel.syncMastersNow(full = true) },
    )
    val saved = readiness.savedAt?.takeIf { readiness.warehouseId == warehouseId }
    com.d365fo.mobileops.presentation.common.focus.FocusListRow(
        title = "Open orders and counts",
        icon = androidx.compose.material.icons.Icons.Filled.Assignment,
        subtitle = when {
            readiness.running -> "Saving…"
            saved != null -> "${readiness.purchaseOrders} POs · ${readiness.transferOrders} TOs · ${readiness.counts} " +
                (if (readiness.counts == 1) "count" else "counts") + " · " + com.d365fo.mobileops.util.Formatters.clockTime(saved)
            else -> "Save while online."
        },
        trailing = if (warehouseId != null) {
            {
                IconButton(onClick = { tried = "Saving"; if (online) viewModel.saveNow(warehouseId) }, enabled = !readiness.running) {
                    Icon(androidx.compose.material.icons.Icons.Filled.Refresh, contentDescription = "Save open orders and counts now", tint = com.d365fo.mobileops.presentation.common.theme.FocusColors.navy)
                }
            }
        } else {
            null
        },
    )
    Note("Sign-ins from the last ${viewModel.offlineSignInDays} days work offline. Work is sent when back online.")
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Focus design: an uppercase caption, then the section on a white 16dp panel.
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title.uppercase(),
            style = com.d365fo.mobileops.presentation.common.theme.FocusType.caption,
            color = com.d365fo.mobileops.presentation.common.theme.FocusColors.caption,
            modifier = Modifier.padding(top = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(com.d365fo.mobileops.presentation.common.theme.FocusColors.surface, com.d365fo.mobileops.presentation.common.theme.FocusShapes.band)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() })
            .heightIn(min = com.d365fo.mobileops.presentation.common.focus.FocusDimens.listRow)
            .padding(horizontal = SIDE, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WarehouseDimens.ItemSpacing),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = com.d365fo.mobileops.presentation.common.theme.FocusType.rowTitle, color = com.d365fo.mobileops.presentation.common.theme.FocusColors.ink)
            Text(
                detail,
                style = com.d365fo.mobileops.presentation.common.theme.FocusType.rowDetail,
                color = com.d365fo.mobileops.presentation.common.theme.FocusColors.muted,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedTrackColor = com.d365fo.mobileops.presentation.common.theme.FocusColors.navy,
            ),
        )
    }
    androidx.compose.material3.HorizontalDivider(color = com.d365fo.mobileops.presentation.common.theme.FocusColors.divider)
}

/** A read-only label and value, the label above in the muted colour. */
@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = WarehouseStatusColors.text3)
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}
