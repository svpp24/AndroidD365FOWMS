package com.d365fo.mobileops.presentation.common

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.d365fo.mobileops.data.security.AuthType
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
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val tenant by viewModel.tenant.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val postingEnabled by viewModel.postingEnabled.collectAsStateWithLifecycle()
    val worker by viewModel.worker.collectAsStateWithLifecycle()
    val test by viewModel.test.collectAsStateWithLifecycle()
    val deviceCode by viewModel.deviceCode.collectAsStateWithLifecycle()
    val palette by viewModel.palette.collectAsStateWithLifecycle()
    val displayScale by viewModel.displayScale.collectAsStateWithLifecycle()
    val idleTimeout by viewModel.idleTimeout.collectAsStateWithLifecycle()
    val isAdmin by viewModel.isAdmin.collectAsStateWithLifecycle()

    var url by remember(tenant.resourceUrl) { mutableStateOf(tenant.resourceUrl) }
    var tenantId by remember(tenant.tenantId) { mutableStateOf(tenant.tenantId) }
    var clientId by remember(tenant.clientId) { mutableStateOf(tenant.clientId) }

    var dataArea by remember(worker.dataAreaId) { mutableStateOf(worker.dataAreaId) }

    // Which submenu is open, or null for the top-level list. Local Compose state, not a nav
    // route - Settings is reached two different ways (AppRoot's pre-sign-in gate and the
    // signed-in nav graph) and this way neither caller needs to know submenus exist.
    var openSection by remember { mutableStateOf<SettingsMenuItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(openSection?.title ?: "Settings") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = { if (openSection != null) openSection = null else onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
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
                    showConnectionProfile = onSignOut == null,
                    showSignedInSections = onSignOut != null,
                    showAdminCenter = onSignOut != null && isAdmin,
                    onOpen = { openSection = it },
                )
            }

            // Connection setup (environment URL, tenant ID, auth) only ever appears here when
            // this screen was reached from the sign-in gate - see the onSignOut == null check
            // below. A worker mid-shift, already signed in, has no business re-pointing the
            // device at a different D365FO environment from the Settings tab; that is fleet
            // commissioning, done once before anyone signs in, not a day-to-day setting.
            if (openSection == SettingsMenuItem.ConnectionProfile && onSignOut == null) {
                SettingsSection("D365FO environment") {
                    // The fields below always edit the profile selected here - switching to a
                    // different saved profile (Dev/UAT/Prod, or any custom name) replaces them
                    // with that profile's own values. Switching, like saving a change to the
                    // active profile's URL, wipes local data - see SettingsViewModel's
                    // wipeLocalData - because order and item data from two environments must
                    // never mix. Directory (tenant) ID lives in Authentication below, not here -
                    // one Azure AD tenant hosts every D365FO environment an org runs (DEV, UAT,
                    // PROD all resolve the same worker sign-ins against it), so it does not vary
                    // per environment the way the URL does.
                    ProfileRow(
                        profiles = profiles,
                        activeLabel = tenant.label,
                        onSelect = viewModel::switchProfile,
                        onAdd = viewModel::addProfile,
                        onDelete = viewModel::deleteProfile,
                    )

                    SettingField(
                        label = "Environment URL",
                        value = url,
                        placeholder = "https://xxx.operations.dynamics.com",
                        keyboard = KeyboardType.Uri,
                    ) { url = it }

                    // The device connects to one company, chosen before anyone signs in - this
                    // is what decides the X-Company header on every call this device ever
                    // makes, including the sign-in call itself. Independent of warehouse: which
                    // warehouses exist within this company, and which one a worker is assigned,
                    // is a backoffice decision made per worker, not something set here. See
                    // "Assigned warehouse" below.
                    SettingField(
                        label = "Legal entity",
                        value = dataArea,
                        placeholder = "USMF",
                        capitalize = true,
                    ) { dataArea = it }
                }

                SettingsSection("Authentication") {
                    // Directory (tenant) ID and Client ID together say which Azure AD app
                    // registration, in which tenant, this device signs in against - neither one
                    // is environment-specific the way "D365FO environment" above is, so they
                    // live in their own section rather than beside the URL/legal entity fields.
                    SettingField(
                        label = "Directory (tenant) ID",
                        value = tenantId,
                        placeholder = "00000000-0000-0000-0000-000000000000",
                    ) { tenantId = it }

                    // Editable now rather than compiled in - a client ID is a public identifier
                    // (an app registration's own name, not a secret), so there's nothing
                    // sensitive about it being visible on a handheld. Lets a device be pointed
                    // at a different app registration without a rebuild.
                    SettingField(
                        label = "Client ID",
                        value = clientId,
                        placeholder = "00000000-0000-0000-0000-000000000000",
                    ) { clientId = it }

                    // This device's own identity to Azure AD - still not a worker sign-in. The
                    // person authenticates separately on the worker sign-in screen against
                    // HSMobileOpsUser; offering a Microsoft sign-in there would ask a warehouse
                    // worker for an account the fleet does not have. Device-code only now - no
                    // long-lived secret sits on any handheld, at the cost of an interactive step
                    // and Azure AD's own admin-consent/public-client-flow setup.
                    Text(
                        text = "One person signs in once, on any device with a browser - " +
                            "this handheld only shows a short code.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WarehouseStatusColors.text3,
                    )

                    TestResult(test)

                    // No separate save step - signing in saves whatever is currently typed. See
                    // SettingsViewModel.startDeviceCodeSignIn's own doc comment for why.
                    DeviceCodeSection(
                        state = deviceCode,
                        onStart = {
                            viewModel.startDeviceCodeSignIn(
                                TenantConfig(
                                    label = tenant.label,
                                    resourceUrl = url.trim(),
                                    tenantId = tenantId.trim(),
                                    clientId = clientId.trim(),
                                    authType = AuthType.DEVICE_CODE,
                                ),
                            )
                        },
                        onCancel = viewModel::cancelDeviceCodeSignIn,
                    )
                }

                // Plain persistence, no sign-in attached - see SettingsViewModel.saveConnection's
                // own doc comment for why this exists on top of the "save and sign in"/"save and
                // test" buttons above: those are the *only* save path otherwise, and a typed edit
                // is silently lost the moment this screen is left (or the process dies) without
                // tapping one of them - confirmed live. Sits below both connection sections since
                // it commits everything in both at once (environment URL/legal entity as well as
                // tenant/client ID), not just Authentication's own fields.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(WarehouseDimens.ItemSpacing),
                ) {
                    CancelOutlinedButton(
                        onClick = {
                            url = tenant.resourceUrl
                            tenantId = tenant.tenantId
                            clientId = tenant.clientId
                            dataArea = worker.dataAreaId
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    ) {
                        Text("Cancel")
                    }
                    ConfirmButton(
                        onClick = {
                            viewModel.saveConnection(
                                TenantConfig(
                                    label = tenant.label,
                                    resourceUrl = url.trim(),
                                    tenantId = tenantId.trim(),
                                    clientId = clientId.trim(),
                                    authType = tenant.authType,
                                ),
                                dataAreaId = dataArea,
                            )
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    ) {
                        Text("Save")
                    }
                }

                // Moved here from the day-to-day Settings tab: whether captures post to
                // D365FO is a fleet-commissioning decision, made once by whoever sets the
                // device up, not something a worker should find and flip mid-shift. It stays
                // off by default during commissioning itself - see ConnectionSettings'
                // own doc comment on why a freshly connected device should hold captures
                // rather than post them before the AOS services are confirmed deployed.
                SettingsSection("Data posting") {
                    ToggleRow(
                        title = "Allow posting to D365FO",
                        detail = if (postingEnabled) {
                            "Captures will post. The custom AOS services must be deployed."
                        } else {
                            "Reads only. Captures are held on the device and never posted."
                        },
                        checked = postingEnabled,
                        onToggle = { viewModel.setPostingEnabled(!postingEnabled) },
                    )
                }
            }

            // Accent colour and lockout timeout are both fleet-commissioning settings, on
            // request - reachable only from the pre-sign-in ("outside") screen, set once by
            // whoever configures the device rather than found and changed mid-shift. Same
            // reasoning as Connection & profile and Data posting just above them there.
            if (openSection == SettingsMenuItem.Appearance && onSignOut == null) {
                SettingsSection("Appearance") {
                    PaletteRow(selected = palette, onSelect = viewModel::setPalette)
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
                }

                // Sign-out itself lives one tap away on Home's own top bar now, not buried here -
                // this screen keeps only who is signed in, for the rare moment that matters.
                SettingsSection("Session") {
                    Text(
                        text = worker.displayName.ifBlank { "Signed in" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = WarehouseStatusColors.text3,
                    )
                }
            }

            // Always reachable, regardless of sign-in or license state - see LogStore's own doc
            // on why this exists outside both gates.
            if (openSection == SettingsMenuItem.Logs) {
                LogsSection()
            }

            // Menu entry itself is already gated on isAdmin (see SettingsMenu's showAdminCenter
            // param) - the onSignOut != null check here is only defence against the unreachable
            // case of openSection somehow still pointing at AdminCenter after signing out.
            if (openSection == SettingsMenuItem.AdminCenter && onSignOut != null && isAdmin) {
                AdminCenterSection()
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
private enum class SettingsMenuItem(val title: String) {
    ConnectionProfile("Connection & profile"),
    Appearance("Appearance"),
    Session("Session"),
    Lockout("Lockout settings"),
    Logs("Logs"),
    AdminCenter("Admin Center"),
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
    showConnectionProfile: Boolean,
    showSignedInSections: Boolean,
    /** Only ever true alongside [showSignedInSections] - see [WorkerRole]. */
    showAdminCenter: Boolean,
    onOpen: (SettingsMenuItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(WarehouseDimens.ItemSpacing)) {
        // Appearance and Lockout are both fleet-commissioning settings now, on request - set
        // once by whoever configures the device before handing it to a worker, not something
        // to find and change mid-shift. Reachable only from the pre-sign-in ("outside") screen,
        // same reasoning as Connection & profile and Data posting right above them there.
        if (showConnectionProfile) {
            SettingsMenuRow(SettingsMenuItem.ConnectionProfile, onOpen)
            SettingsMenuRow(SettingsMenuItem.Appearance, onOpen)
            SettingsMenuRow(SettingsMenuItem.Lockout, onOpen)
        }
        if (showSignedInSections) {
            SettingsMenuRow(SettingsMenuItem.Session, onOpen)
        }
        if (showAdminCenter) {
            SettingsMenuRow(SettingsMenuItem.AdminCenter, onOpen)
        }
        SettingsMenuRow(SettingsMenuItem.Logs, onOpen)
    }
}

@Composable
private fun SettingsMenuRow(item: SettingsMenuItem, onOpen: (SettingsMenuItem) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(item) },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(min = WarehouseDimens.MinTouchTarget),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = WarehouseStatusColors.text3,
            )
        }
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
            // does not get for free here. LogStore's own cap (3000 entries) keeps this from
            // becoming a real problem.
            entries.asReversed().forEach { entry ->
                LogRow(
                    entry = entry,
                    expanded = entry.id == expandedId,
                    onToggle = { expandedId = if (expandedId == entry.id) null else entry.id },
                )
            }
        }
    }
}

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
            modifier = Modifier.width(56.dp),
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
                modifier = Modifier.width(56.dp),
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
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        if (expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 84.dp)) {
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
 * The Admin Center content - the worker directory, refreshed from AOS on open (see
 * [AdminCenterRepository]'s own doc on why that read is network-required while the toggle below
 * it is not), each worker expandable to its four module switches.
 */
@Composable
private fun AdminCenterSection(viewModel: AdminCenterViewModel = hiltViewModel()) {
    val workers by viewModel.workers.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var expandedUserName by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(WarehouseDimens.ItemSpacing)) {
        error?.let {
            ErrorBanner(message = it, onDismiss = viewModel::dismissError)
        }

        OutlinedButton(
            onClick = viewModel::refresh,
            enabled = !refreshing,
            modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
        ) {
            Text(if (refreshing) "Refreshing…" else "Refresh worker list")
        }

        if (workers.isEmpty() && !refreshing) {
            Text(
                text = "No workers loaded yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = WarehouseStatusColors.text3,
            )
        }

        workers.forEach { worker ->
            AdminWorkerCard(
                worker = worker,
                expanded = expandedUserName == worker.userName,
                onToggleExpanded = {
                    expandedUserName =
                        if (expandedUserName == worker.userName) null else worker.userName
                },
                permissions = viewModel.permissionsFor(worker.userName),
                onTogglePermission = { workflow, allowed ->
                    viewModel.togglePermission(worker.userName, workflow, allowed)
                },
            )
        }
    }
}

/**
 * One worker: a summary row that expands, on tap, into a switch per
 * [com.d365fo.mobileops.domain.model.WarehouseWorkflow]. Absence-means-allowed applies here the
 * same way it does everywhere else this cache is read - a workflow with no row yet shows as
 * allowed, not as a state the admin has to first turn on.
 */
@Composable
private fun AdminWorkerCard(
    worker: WorkerDirectoryEntity,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    permissions: Flow<List<WorkerPermissionEntity>>,
    onTogglePermission: (WarehouseWorkflow, Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = worker.displayName.ifBlank { worker.userName },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (worker.isSupervisor) {
                            "Supervisor · ${worker.userName}"
                        } else {
                            worker.userName
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = WarehouseStatusColors.text3,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = WarehouseStatusColors.text3,
                )
            }

            if (expanded) {
                val permissionState by permissions.collectAsStateWithLifecycle(initialValue = emptyList())
                HorizontalDivider(
                    color = WarehouseStatusColors.border,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Column {
                    WarehouseWorkflow.entries.forEach { workflow ->
                        val allowed = permissionState.find { it.workflow == workflow }?.allowed ?: true
                        ToggleRow(
                            title = workflow.label,
                            detail = if (allowed) "Allowed" else "Blocked",
                            checked = allowed,
                            onToggle = { onTogglePermission(workflow, !allowed) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The saved connection profiles (Dev / UAT / Prod, or any custom name), one [FilterChip] each -
 * selected = the currently active one. Tapping a different one asks to confirm before switching,
 * since a switch wipes local data; tapping the active one does nothing, there is nothing to
 * confirm about staying put. A trailing "+" chip opens a small dialog to name and add a new
 * profile. Horizontally scrollable rather than wrapped: a handheld's width comfortably fits
 * three or four chips before it needs to, and a fixed single row keeps this from pushing the
 * rest of the form down every time someone adds a profile.
 */
@Composable
private fun ProfileRow(
    profiles: List<TenantConfig>,
    activeLabel: String,
    onSelect: (String) -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var switchTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var addingName by remember { mutableStateOf<String?>(null) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        profiles.forEach { profile ->
            val isActive = profile.label == activeLabel
            FilterChip(
                selected = isActive,
                onClick = { if (!isActive) switchTarget = profile.label },
                label = { Text(profile.label) },
                trailingIcon = if (!isActive) {
                    {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Delete ${profile.label}",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { deleteTarget = profile.label },
                        )
                    }
                } else {
                    null
                },
            )
        }
        FilterChip(
            selected = false,
            onClick = { addingName = "" },
            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
            label = { Text("New") },
        )
    }

    switchTarget?.let { label ->
        AlertDialog(
            onDismissRequest = { switchTarget = null },
            title = { Text("Switch to $label?") },
            text = {
                Text(
                    "This device will now talk to the $label environment. Data from " +
                        "$activeLabel currently on this device will be cleared first - " +
                        "order and item data from two environments must never mix.",
                )
            },
            confirmButton = {
                ConfirmButton(
                    onClick = {
                        onSelect(label)
                        switchTarget = null
                    },
                ) { Text("Switch") }
            },
            dismissButton = {
                CancelOutlinedButton(onClick = { switchTarget = null }) { Text("Cancel") }
            },
        )
    }

    deleteTarget?.let { label ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete $label?") },
            text = { Text("This removes the saved connection details for $label. This cannot be undone.") },
            confirmButton = {
                CancelButton(onClick = {
                    onDelete(label)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = {
                CancelOutlinedButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    addingName?.let { name ->
        AlertDialog(
            onDismissRequest = { addingName = null },
            title = { Text("New profile") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { addingName = it },
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Staging") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                ConfirmButton(
                    onClick = {
                        onAdd(name)
                        addingName = null
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Add") }
            },
            dismissButton = {
                CancelOutlinedButton(onClick = { addingName = null }) { Text("Cancel") }
            },
        )
    }
}

/** The interactive half of device-code sign-in - the code, the URL, and the wait. */
@Composable
private fun DeviceCodeSection(
    state: DeviceCodeUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    when (state) {
        DeviceCodeUiState.Idle -> {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
            ) {
                Text("Sign in with Microsoft")
            }
        }

        DeviceCodeUiState.Starting -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Text("Requesting a code…", style = MaterialTheme.typography.bodyMedium)
        }

        is DeviceCodeUiState.WaitingForUser -> Card(
            colors = CardDefaults.cardColors(containerColor = WarehouseStatusColors.surface2),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val context = LocalContext.current
            val clipboard = LocalClipboardManager.current
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Go to this address on any device and enter the code:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Tappable rather than plain text a worker has to retype by hand onto a second
                // device - opens straight in a browser, same URI the text names.
                Text(
                    text = state.verificationUri,
                    style = MaterialTheme.typography.bodyMedium,
                    color = WarehouseStatusColors.accent,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, state.verificationUri.toUri()),
                            )
                        }
                    },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = state.userCode,
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(state.userCode))
                            Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy code")
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Waiting for authorization…", style = MaterialTheme.typography.bodyMedium)
                }
                CancelOutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        }

        is DeviceCodeUiState.SignedIn -> Card(
            colors = CardDefaults.cardColors(containerColor = WarehouseStatusColors.surface2),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(12.dp),
            ) {
                Text(
                    text = state.userPrincipalName?.let { "Signed in as $it" } ?: "Signed in",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WarehouseStatusColors.green,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onStart) { Text("Sign in again") }
            }
        }

        is DeviceCodeUiState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ErrorBanner(message = state.reason)
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
            ) {
                Text("Try again")
            }
        }
    }
}

/**
 * A wrapped grid of round accent swatches, one per [ColorPalette] - a tap changes
 * [WarehouseStatusColors.accent] app-wide (see [com.d365fo.mobileops.presentation.common.theme.D365MobileTheme]),
 * everything else about the theme untouched. Swatches rather than labelled buttons: the choice
 * here is entirely visual, so showing the colour says more than its name would. A gradient entry
 * ([ColorPalette.isGradient]) shows its real two-hue [ColorPalette.gradientBrush] rather than a
 * flat fill, since that swatch is exactly what [WarehouseStatusColors.accentGradient] renders
 * once picked - the swatch would otherwise misrepresent the one thing that entry is for.
 *
 * Chunked into fixed-size rows rather than a single scrolling row or a `FlowRow` - same
 * multi-row technique this file already uses for [DisplayScaleRow], now needed here too since the
 * palette has grown past what fits one row (see [ColorPalette]'s own doc comment for the count).
 */
@Composable
private fun PaletteRow(
    selected: ColorPalette,
    onSelect: (ColorPalette) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ColorPalette.entries.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { option ->
                    val isSelected = option == selected
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            // Drawn as an actual circle primitive, not a rectangle clipped/
                            // background-shaped to one - `clip(CircleShape)` (with or without a
                            // brush background before or after it) confirmed live to still leave
                            // some swatches square-cornered on this device, a
                            // RenderNode-outline-clip quirk `drawCircle` doesn't go through at
                            // all, since it rasterises the circle directly instead of clipping a
                            // rectangular fill to one.
                            .drawBehind {
                                drawCircle(
                                    brush = if (option.isGradient) option.gradientBrush else SolidColor(option.accent),
                                    radius = size.minDimension / 2f,
                                )
                            }
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else WarehouseStatusColors.border,
                                shape = CircleShape,
                            )
                            .toggleable(
                                value = isSelected,
                                role = Role.RadioButton,
                                onValueChange = { onSelect(option) },
                            ),
                    )
                }
            }
        }
    }
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
private fun SettingField(
    label: String,
    value: String,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    capitalize: Boolean = false,
    masked: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (placeholder.isNotBlank()) {
            {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        } else {
            null
        },
        singleLine = true,
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (masked) KeyboardType.Password else keyboard,
            capitalization = if (capitalize) {
                KeyboardCapitalization.Characters
            } else {
                KeyboardCapitalization.None
            },
            autoCorrectEnabled = false,
        ),
        // Monospaced because these are guids and URLs — proportional type makes an O and a 0
        // hard to tell apart, and a mistyped guid fails with an error that names neither.
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WarehouseDimens.MinTouchTarget),
    )
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
