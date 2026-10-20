package com.d365fo.mobileops.presentation.common

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
import androidx.compose.material3.FilterChip
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
    val isTab = onSignOut != null && openSection == null

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Anything you posted is still sent to D365FO. Scans you have not posted yet stay on this device for when you sign back in.") },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = { confirmSignOut = false }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
                    confirm = { com.d365fo.mobileops.presentation.common.components.CancelButton(onClick = { confirmSignOut = false; onSignOut?.invoke() }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Sign out") } },
                )
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { if (isTab) HomeGreeting() else Text(openSection?.title ?: "Settings") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    if (!isTab) {
                        IconButton(onClick = { if (openSection != null) openSection = null else onBack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = WarehouseDimens.ScreenPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(WarehouseDimens.ItemSpacing),
        ) {
            Row(modifier = Modifier.padding(top = 4.dp)) { }

            if (openSection == null) {
                SettingsMenu(
                    postingEnabled = postingEnabled,
                    showConnectionProfile = onSignOut == null,
                    showSignedInSections = onSignOut != null,
                    onOpen = { if (it == SettingsMenuItem.Feedback) onFeedback?.invoke() else openSection = it },
                )
                if (onSignOut != null) {
                    com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton(
                        onClick = { confirmSignOut = true },
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, WarehouseStatusColors.red),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = WarehouseStatusColors.red),
                        modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget).padding(top = 4.dp),
                    ) { Text("Sign out", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                }
                // Which build this is - what support asks first, and what D365FO's device list records.
                Text(
                    text = com.d365fo.mobileops.util.AppVersion.full,
                    style = MaterialTheme.typography.bodySmall,
                    color = WarehouseStatusColors.text3,
                    modifier = Modifier.padding(top = 8.dp),
                )
                // The brand mark at the foot of More, like the reference app (video, 20 Oct).
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_app_logo),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(44.dp).padding(top = 4.dp),
                )
            }

            // Connection setup (environment URL, tenant ID, auth) only ever appears here when
            // this screen was reached from the sign-in gate - see the onSignOut == null check
            // below. A worker mid-shift, already signed in, has no business re-pointing the
            // device at a different D365FO environment from the Settings tab; that is fleet
            // commissioning, done once before anyone signs in, not a day-to-day setting.
            if (openSection == SettingsMenuItem.ConnectionProfile && onSignOut == null) {
                // One profile = one D365FO environment: its URL, company and app registrations,
                // all from that environment's connection QR code. Scanning a code adds the
                // profile (or updates it) and makes it the one in use.
                SettingsSection("Connection") {
                    Text(
                        text = "Each profile is one D365FO environment - its URL, company and app registrations " +
                            "come from its connection QR code. Scan a code to add a profile or update it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WarehouseStatusColors.text3,
                    )
                    ConnectionQrSetup()
                }

                ConnectionProfiles(
                    profiles = profiles,
                    activeLabel = tenant.label,
                    deviceCompany = worker.dataAreaId,
                    credentialStatus = credentialStatus,
                    test = test,
                    viewModel = viewModel,
                )

                // Moved here from the day-to-day Settings tab: whether captures post to
                // D365FO is a fleet-commissioning decision, made once by whoever sets the
                // device up, not something a worker should find and flip mid-shift. It stays
                // off by default during commissioning itself - see ConnectionSettings'
                // own doc comment on why a freshly connected device should hold captures
                // rather than post them before the AOS services are confirmed deployed.
                SettingsSection("Data posting" + tenant.label.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()) {
                    ToggleRow(
                        title = "Allow posting to D365FO",
                        detail = if (postingEnabled) {
                            "Captures post to D365FO. The custom AOS services must be deployed."
                        } else {
                            "Off: captures are saved and held on this device. Nothing is sent to D365FO until " +
                                "this is switched on - then everything held is sent."
                        },
                        checked = postingEnabled,
                        onToggle = { viewModel.setPostingEnabled(!postingEnabled) },
                    )
                }

                ConnectionReset(
                    hasCredentials = profiles.any { it.clientId.isNotBlank() || it.tenantId.isNotBlank() },
                    viewModel = viewModel,
                )
            }

            // Display scale and lockout timeout are both fleet-commissioning settings, on
            // request - reachable only from the pre-sign-in ("outside") screen, set once by
            // whoever configures the device rather than found and changed mid-shift. Same
            // reasoning as Connection & profile and Data posting just above them there.
            if (openSection == SettingsMenuItem.Appearance && onSignOut == null) {
                SettingsSection("Appearance") {
                    DisplayScaleRow(selected = displayScale, onSelect = viewModel::setDisplayScale)
                }
            }

            if (openSection == SettingsMenuItem.Lockout && onSignOut == null) {
                SettingsSection("Lockout settings") {
                    IdleTimeoutSlider(selected = idleTimeout, onSelect = viewModel::setIdleTimeout)
                }
            }

            if (openSection == SettingsMenuItem.Session && onSignOut != null) {
                // Read-only - a worker works the warehouse D365FO assigned them, and this exists
                // only so they can see what that is without having to guess from a scan failing.
                SettingsSection("Assigned warehouse") {
                    AssignedWarehouseText(fallbackId = worker.warehouseId)
                    // An outlined full-width button, like "Show the app tour again" below it.
                    OutlinedButton(onClick = onChangeWarehouse, modifier = Modifier.fillMaxWidth()) { Text("Change warehouse") }
                }

                // Sign-out itself lives one tap away on Home's own top bar now, not buried here -
                // this screen keeps only who is signed in, for the rare moment that matters.
                SettingsSection("Session") {
                    Text(
                        text = signedInAs,
                        style = MaterialTheme.typography.bodyMedium,
                        color = WarehouseStatusColors.text3,
                    )
                    // The navigation tour shows once per worker; this brings it back on Home.
                    val tour: com.d365fo.mobileops.presentation.common.tour.TourViewModel = hiltViewModel()
                    OutlinedButton(
                        onClick = {
                            tour.replay()
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Show the app tour again") }
                }

                OfflineSection(warehouseId = worker.warehouseId)
            }

            // Always reachable, regardless of sign-in or license state - see LogStore's own doc
            // on why this exists outside both gates.
            if (openSection == SettingsMenuItem.Logs) {
                LogsSection()
            }

            // Signed in, so it can be changed during a shift (feedback 301e5d0e); whether the
            // device posts at all stays a commissioning switch on the sign-in screen.
            if (openSection == SettingsMenuItem.Posting && onSignOut != null) {
                SettingsSection("Data posting") {
                    // Feedback 301e5d0e: synchronous or asynchronous posting.
                    ToggleRow(
                        title = "Post immediately",
                        detail = if (postImmediately) {
                            "On: each receipt or transfer is posted in D365FO while the worker waits, and a " +
                                "refusal shows at once with D365FO's reason. Slower on a busy D365FO."
                        } else {
                            "Off: D365FO takes each receipt or transfer at once and posts it in the background " +
                                "within a few minutes (or with Process pending on the Activity screen)."
                        },
                        checked = postImmediately,
                        onToggle = { viewModel.setPostImmediately(!postImmediately) },
                    )
                    // The commissioning switch lives on the sign-in screen; say so here when it is
                    // off - "Post immediately" On read as "posting is on" (11 Oct) while every
                    // capture was still being held.
                    if (!postingEnabled) {
                        Text(
                            text = "Posting to D365FO is switched off for this connection, so captures and counts " +
                                "are held on this device. Switch on \"Allow posting to D365FO\" under Connection " +
                                "settings on the sign-in screen.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = WarehouseStatusColors.amber,
                        )
                    }
                }
            }

            Row(modifier = Modifier.padding(bottom = 28.dp)) { }
        }
    }
}

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
    showConnectionProfile: Boolean,
    showSignedInSections: Boolean,
    onOpen: (SettingsMenuItem) -> Unit,
) {
    // Headed groups of rows in one white card each, icon and chevron per row (20 Oct restyle,
    // the mock's More screen). Appearance, Lockout and Connection stay pre-sign-in only - they
    // are set once by whoever commissions the device.
    // A row's current value on its right, as the reference app shows "English" (video, 20 Oct).
    val switcher: WarehouseSwitcherViewModel = hiltViewModel()
    val session by switcher.session.collectAsStateWithLifecycle()
    val values = mapOf(
        SettingsMenuItem.Session to session?.selected?.warehouseId.orEmpty(),
        SettingsMenuItem.Posting to if (postingEnabled) "On" else "Off",
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showConnectionProfile) {
            SettingsGroup("Device", listOf(SettingsMenuItem.ConnectionProfile, SettingsMenuItem.Appearance, SettingsMenuItem.Lockout), onOpen)
        }
        if (showSignedInSections) {
            SettingsGroup("Settings", listOf(SettingsMenuItem.Session, SettingsMenuItem.Posting), onOpen, values)
            SettingsGroup("Support", listOf(SettingsMenuItem.Feedback, SettingsMenuItem.Logs), onOpen)
        } else {
            SettingsGroup("Support", listOf(SettingsMenuItem.Logs), onOpen)
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    items: List<SettingsMenuItem>,
    onOpen: (SettingsMenuItem) -> Unit,
    values: Map<SettingsMenuItem, String> = emptyMap(),
) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
        modifier = Modifier.padding(top = 6.dp),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Color.White, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
    ) {
        items.forEachIndexed { i, item ->
            if (i > 0) androidx.compose.material3.HorizontalDivider(color = WarehouseStatusColors.border.copy(alpha = 0.45f))
            SettingsMenuRow(item, onOpen, values[item].orEmpty())
        }
    }
}

@Composable
private fun SettingsMenuRow(item: SettingsMenuItem, onOpen: (SettingsMenuItem) -> Unit, value: String = "") {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WarehouseDimens.MinTouchTarget)
            .clickable { onOpen(item) }
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (value.isNotBlank()) {
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
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

    Column(verticalArrangement = Arrangement.spacedBy(WarehouseDimens.ItemSpacing)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(WarehouseDimens.ItemSpacing),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(entries.joinToString("\n") { it.toLogLine() }))
                    Toast.makeText(context, "Copied ${entries.size} log lines", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
            ) { Text("Copy all") }

            CancelOutlinedButton(
                onClick = { viewModel.clear(); expandedId = null },
                modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
            ) { Text("Clear") }
        }

        if (entries.isEmpty()) {
            Text(
                text = "No log entries yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = WarehouseStatusColors.text3,
            )
        } else {
            LogTableHeader()
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
                OutlinedButton(
                    onClick = { shown += LOG_PAGE },
                    modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
                ) { Text("Show ${minOf(LOG_PAGE, entries.size - shown)} more (${entries.size - shown} older)") }
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
    val levelColor = when (entry.levelLabel) {
        "E", "A" -> WarehouseStatusColors.red
        "W" -> WarehouseStatusColors.amber
        else -> WarehouseStatusColors.text3
    }
    val rowBackground = if (expanded) WarehouseStatusColors.surface2 else MaterialTheme.colorScheme.surface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = LogTimeFormatter.format(entry.timestamp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = WarehouseStatusColors.text3,
                modifier = Modifier.width(72.dp),
            )
            Text(
                text = entry.levelLabel,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = levelColor,
                modifier = Modifier.width(28.dp),
            )
            Text(
                text = "${entry.tag}: ${entry.message}",
                style = MaterialTheme.typography.bodySmall,
                // Wrapped, never cut off with "…" (the app's rule); a tap adds the full time
                // and any stack trace. 72 dp fits HH:mm:ss without breaking the seconds.
                modifier = Modifier.weight(1f),
            )
        }

        if (expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 100.dp)) {
                Text(
                    text = entry.timestamp.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = WarehouseStatusColors.text3,
                )
                entry.throwable?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = WarehouseStatusColors.red,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    HorizontalDivider(color = WarehouseStatusColors.border)
}

private fun LogEntry.toLogLine(): String =
    "$timestamp $levelLabel/$tag: $message"

/**
 * The saved connection profiles (Dev / UAT / Prod, or any custom name), one [FilterChip] each -
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
@Composable
private fun ConnectionProfiles(
    profiles: List<TenantConfig>,
    activeLabel: String,
    deviceCompany: String,
    credentialStatus: com.d365fo.mobileops.data.security.CredentialStatus,
    test: ConnectionTest,
    viewModel: SettingsViewModel,
) {
    var switchTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    if (profiles.isEmpty()) {
        SettingsSection("Profiles") {
            Text(
                text = "No profile yet. Scan a connection QR code above to connect this device to D365FO.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    profiles.sortedByDescending { it.label == activeLabel }.forEach { profile ->
        val active = profile.label == activeLabel
        val registrations = profile.credentials.count { it.isComplete }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = profile.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = WarehouseStatusColors.accent,
                        modifier = Modifier.weight(1f),
                    )
                    if (active) {
                        com.d365fo.mobileops.presentation.common.components.StatusChip(
                            text = "In use",
                            color = WarehouseStatusColors.green,
                            dimColor = WarehouseStatusColors.greenDim,
                        )
                    }
                }
                InfoRow("Environment", profile.resourceUrl.ifBlank { "Not set" })
                InfoRow(
                    "Company",
                    profile.company.ifBlank { if (active) deviceCompany.ifBlank { "Not set" } else "Not set" },
                )
                if (registrations == 0) {
                    Text(
                        text = "No credentials - scan this environment's connection QR code.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WarehouseStatusColors.amber,
                    )
                } else {
                    InfoRow("App registrations", registrations.toString())
                    InfoRow("Tenant", profile.tenantId.ifBlank { "Not set" })
                    if (active) {
                        // This device's one, not all of them: the slot and the last few
                        // characters of its client ID, never a secret.
                        InfoRow(
                            "This device uses",
                            "Registration ${credentialStatus.slot} of ${credentialStatus.total}" +
                                credentialStatus.shortId.takeIf { it.isNotBlank() }?.let { " · …$it" }.orEmpty() +
                                credentialStatus.borrowedFromSlot?.let { " (moved from $it while it is throttled)" }.orEmpty(),
                        )
                        if (credentialStatus.throttledReplies > 0) {
                            InfoRow("Throttled replies since start", credentialStatus.throttledReplies.toString())
                        }
                    }
                }
                if (active) TestResult(test)
                Row(horizontalArrangement = Arrangement.spacedBy(WarehouseDimens.ItemSpacing), modifier = Modifier.fillMaxWidth()) {
                    if (active) {
                        OutlinedButton(
                            onClick = { viewModel.saveAndSignInWithSecret(profile) },
                            enabled = profile.isComplete,
                            modifier = Modifier.weight(1f),
                        ) { Text("Test connection") }
                    } else {
                        OutlinedButton(onClick = { switchTarget = profile.label }, modifier = Modifier.weight(1f)) {
                            Text("Use this profile")
                        }
                    }
                    CancelOutlinedButton(onClick = { deleteTarget = profile.label }, modifier = Modifier.weight(1f)) {
                        Text("Delete")
                    }
                }
            }
        }
    }

    switchTarget?.let { label ->
        AlertDialog(
            onDismissRequest = { switchTarget = null },
            title = { Text("Use $label?") },
            text = {
                Text(
                    "This device will work in $label from now on. Order and item data from $activeLabel on this " +
                        "device is cleared first - data from two environments must never mix.",
                )
            },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = { switchTarget = null }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
                    confirm = { com.d365fo.mobileops.presentation.common.components.ConfirmButton(onClick = {
                    switchTarget = null
                    viewModel.switchProfile(label)
                }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Use $label") } },
                )
            },
        )
    }

    deleteTarget?.let { label ->
        val active = label == activeLabel
        val next = profiles.firstOrNull { it.label != label }?.label
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete $label?") },
            text = {
                Text(
                    "Removes $label's environment URL, company and app registrations from this device." +
                        if (active) {
                            " " + (next?.let { "The device switches to $it" } ?: "The device has no connection until a QR code is scanned") +
                                ", and order and item data on it is cleared."
                        } else {
                            ""
                        },
                )
            },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = { deleteTarget = null }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
                    confirm = { com.d365fo.mobileops.presentation.common.components.CancelButton(onClick = {
                    deleteTarget = null
                    viewModel.deleteProfile(label)
                }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Delete") } },
                )
            },
        )
    }
}

/** Clears the credentials of every profile - see SettingsViewModel.resetConnection. Data stays. */
@Composable
private fun ConnectionReset(hasCredentials: Boolean, viewModel: SettingsViewModel) {
    var confirm by remember { mutableStateOf(false) }
    var unsent by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(confirm) { unsent = if (confirm) viewModel.unsentDescription() else null }

    SettingsSection("Reset") {
        Text(
            text = "Clears the client IDs and secrets of every profile, and the offline sign-ins. Profiles, company " +
                "and all data on this device stay.",
            style = MaterialTheme.typography.bodyMedium,
            color = WarehouseStatusColors.text3,
        )
        OutlinedButton(onClick = { confirm = true }, enabled = hasCredentials, modifier = Modifier.fillMaxWidth()) {
            Text("Reset connection")
        }
    }
    if (!confirm) return
    AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Reset connection?") },
        text = {
            Text(
                "Removes the tenant, client IDs and secrets of every profile, and the offline sign-ins. Scan a " +
                    "connection QR code to use the device again." +
                    (unsent?.let { " This device has $it - it stays here and is sent once a connection is set up again." } ?: ""),
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
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        DisplayScale.entries.forEach { option ->
            if (option == selected) {
                Button(
                    onClick = { onSelect(option) },
                    modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                ) { Text(option.label) }
            } else {
                OutlinedButton(
                    onClick = { onSelect(option) },
                    modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                ) { Text(option.label) }
            }
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

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Lock out after ${sliderValue.toInt()} min of inactivity",
            style = MaterialTheme.typography.bodyMedium,
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
                        .background(WarehouseStatusColors.accent, RoundedCornerShape(6.dp))
                        .border(2.dp, Color.White, RoundedCornerShape(6.dp)),
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
                        .background(WarehouseStatusColors.border, RoundedCornerShape(5.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(10.dp)
                            .background(WarehouseStatusColors.accent, RoundedCornerShape(5.dp)),
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
/** What works without a connection, and what is saved for it. See OfflinePrefetcher. */
@Composable
private fun OfflineSection(warehouseId: String?, viewModel: OfflineReadyViewModel = hiltViewModel()) {
    val readiness by viewModel.readiness.collectAsStateWithLifecycle()
    SettingsSection("Offline use") {
        Text(
            text = "If D365FO can't be reached, workers who signed in on this device in the last " +
                "${viewModel.offlineSignInDays} days can still sign in. Receipts, transfers and counts " +
                "are saved on the device and sent when the connection is back.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = when {
                readiness.running -> "Saving today's orders for offline use..."
                readiness.savedAt != null && readiness.warehouseId == warehouseId ->
                    "Saved for offline: ${readiness.purchaseOrders} purchase " +
                        (if (readiness.purchaseOrders == 1) "order" else "orders") +
                        " for today, ${readiness.transferOrders} transfer " +
                        (if (readiness.transferOrders == 1) "order" else "orders") +
                        " · ${com.d365fo.mobileops.util.Formatters.clockTime(readiness.savedAt!!)}"
                else -> "Today's orders are saved automatically while you are connected."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = WarehouseStatusColors.text3,
        )
        readiness.error?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.red)
        }
        // Item data for offline scanning: what the last sync brought, how long it took and what
        // it cost the device (feedback: record count, time taken, app memory).
        val sku = androidx.compose.runtime.remember { viewModel.lastSkuSync() }
        Text(
            text = sku?.let { s ->
                val n = java.text.NumberFormat.getIntegerInstance()
                "Item data (${if (s.full) "full" else "changes"} sync ${com.d365fo.mobileops.util.Formatters.clockTime(s.finishedAt)}): " +
                    "${n.format(s.itemCount)} items, ${n.format(s.barcodeCount)} barcodes, ${n.format(s.variantCount)} variants · " +
                    "${s.activeSeconds} s" + (if (s.seconds > s.activeSeconds + 5) " (${s.seconds} s with pauses)" else "") +
                    " · peak memory ${s.peakMemoryBytes / (1024 * 1024)} MB · stored ${s.storedBytes / (1024 * 1024)} MB"
            } ?: "Item data: not synced on this device yet - it syncs daily on Wi-Fi while charging.",
            style = MaterialTheme.typography.bodyMedium,
            color = WarehouseStatusColors.text3,
        )
        val online = com.d365fo.mobileops.presentation.common.components.LocalSyncStatus.current?.fullyOnline != false
        if (warehouseId != null) {
            com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton(
                onClick = { viewModel.saveNow(warehouseId) },
                enabled = !readiness.running && online,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (online) "Save today's orders now" else "Saving needs a connection") }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = WarehouseStatusColors.accent,
            )
            content()
        }
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
            .heightIn(min = WarehouseDimens.MinTouchTarget)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WarehouseDimens.ItemSpacing),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = WarehouseStatusColors.text3,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A read-only label and value, the label above in the muted colour. */
@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = WarehouseStatusColors.text3)
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}
