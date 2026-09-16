package com.d365fo.mobileops.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import androidx.compose.material3.Card
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.BuildConfig
import com.d365fo.mobileops.R
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.d365fo.mobileops.data.security.AuthType
import com.d365fo.mobileops.data.security.RequestTimeout
import com.d365fo.mobileops.data.security.TenantConfig
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
    val postingEnabled by viewModel.postingEnabled.collectAsStateWithLifecycle()
    val worker by viewModel.worker.collectAsStateWithLifecycle()
    val test by viewModel.test.collectAsStateWithLifecycle()
    val deviceCode by viewModel.deviceCode.collectAsStateWithLifecycle()
    val palette by viewModel.palette.collectAsStateWithLifecycle()
    val requestTimeout by viewModel.requestTimeout.collectAsStateWithLifecycle()

    var url by remember(tenant.resourceUrl) { mutableStateOf(tenant.resourceUrl) }
    var tenantId by remember(tenant.tenantId) { mutableStateOf(tenant.tenantId) }
    var clientId by remember(tenant.clientId) { mutableStateOf(tenant.clientId) }
    var clientSecret by remember(tenant.clientSecret) { mutableStateOf(tenant.clientSecret) }
    var authType by remember(tenant.authType) { mutableStateOf(tenant.authType) }

    var dataArea by remember(worker.dataAreaId) { mutableStateOf(worker.dataAreaId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Connection setup (environment URL, tenant ID, auth) only ever appears here when
            // this screen was reached from the sign-in gate - see the onSignOut == null check
            // below. A worker mid-shift, already signed in, has no business re-pointing the
            // device at a different D365FO environment from the Settings tab; that is fleet
            // commissioning, done once before anyone signs in, not a day-to-day setting.
            if (onSignOut == null) {
                SectionHeader("D365FO environment")

                SettingField(
                    label = "Environment URL",
                    value = url,
                    placeholder = "https://xxx.operations.dynamics.com",
                    keyboard = KeyboardType.Uri,
                ) { url = it }

                SettingField(
                    label = "Directory (tenant) ID",
                    value = tenantId,
                    placeholder = "00000000-0000-0000-0000-000000000000",
                ) { tenantId = it }

                // The device connects to one company, chosen before anyone signs in - this is
                // what decides the X-Company header on every call this device ever makes,
                // including the sign-in call itself. Independent of warehouse: which warehouses
                // exist within this company, and which one a worker is assigned, is a backoffice
                // decision made per worker, not something set here. See "Assigned warehouse"
                // below.
                SettingField(
                    label = "Legal entity",
                    value = dataArea,
                    placeholder = "USMF",
                    capitalize = true,
                ) { dataArea = it }
                Button(
                    onClick = { viewModel.saveLegalEntity(dataArea) },
                    enabled = test !is ConnectionTest.Running && dataArea.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = WarehouseDimens.MinTouchTarget),
                ) {
                    Text("Save legal entity")
                }

                // The application (client) ID and the client secret are compiled in and are
                // deliberately not shown or editable here. Displaying a credential on a screen
                // anyone on the warehouse floor can pick up and read is the thing this avoids;
                // both come from local.properties at build time. See app/build.gradle.kts.

                SectionHeader("Authentication")

                // Two ways for the DEVICE to prove itself to Azure AD - still not a worker
                // sign-in. Whichever is chosen here, the person still authenticates separately
                // on the worker sign-in screen against HSMobileOpsUser; offering a Microsoft
                // sign-in there would ask a warehouse worker for an account the fleet does not
                // have. This picker is about the fleet's own shared identity, decided once
                // during commissioning, same as everything else in this section.
                AuthTypeRow(
                    selected = authType,
                    onSelect = { authType = it },
                )

                if (authType == AuthType.CLIENT_CREDENTIALS) {
                    Text(
                        text = if (clientSecret.isBlank()) {
                            "No client secret is compiled into this build. Add AAD_CLIENT_SECRET " +
                                "to local.properties and rebuild."
                        } else {
                            "This device connects using the client secret compiled into this build. " +
                                "Workers sign in separately with their own user name and password."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            viewModel.saveAndTest(
                                TenantConfig(
                                    label = tenant.label,
                                    resourceUrl = url.trim(),
                                    tenantId = tenantId.trim(),
                                    clientId = clientId.trim(),
                                    clientSecret = clientSecret.trim(),
                                    authType = authType,
                                ),
                            )
                        },
                        enabled = test !is ConnectionTest.Running,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) {
                        Text(if (test is ConnectionTest.Running) "Testing…" else "Save and test connection")
                    }

                    TestResult(test)
                } else {
                    // Device-code needs no secret at all - the whole reason to offer it, see
                    // ClientCredentialsTokenProvider's own doc comment on the cost of a shared
                    // one. Saving here just stores the tenant/client ID and the chosen mode;
                    // the interactive step is a separate button, not folded into "save".
                    Text(
                        text = "This device signs in with a Microsoft account instead of a " +
                            "shared secret. One person signs in once, on any device with a " +
                            "browser - this handheld only shows a short code.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            viewModel.saveTenant(
                                TenantConfig(
                                    label = tenant.label,
                                    resourceUrl = url.trim(),
                                    tenantId = tenantId.trim(),
                                    clientId = clientId.trim(),
                                    clientSecret = clientSecret.trim(),
                                    authType = authType,
                                ),
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) {
                        Text("Save tenant and client ID")
                    }

                    DeviceCodeSection(
                        state = deviceCode,
                        onStart = viewModel::startDeviceCodeSignIn,
                        onCancel = viewModel::cancelDeviceCodeSignIn,
                    )
                }

                HorizontalDivider()

                // Moved here from the day-to-day Settings tab: whether captures post to
                // D365FO is a fleet-commissioning decision, made once by whoever sets the
                // device up, not something a worker should find and flip mid-shift. It stays
                // off by default during commissioning itself - see ConnectionSettings'
                // own doc comment on why a freshly connected device should hold captures
                // rather than post them before the AOS services are confirmed deployed.
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

                HorizontalDivider()

                // How long a D365FO call is given before it's treated as failed. A device-level,
                // restart-caliber setting like everything else in this section - see
                // NetworkModule's own comment on why the OkHttpClient this feeds can't pick up a
                // change until the app is relaunched.
                SectionHeader("Network")
                TimeoutRow(selected = requestTimeout, onSelect = viewModel::setRequestTimeout)
                Text(
                    text = "Applies the next time the app starts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()
            }

            // Read-only, and only ever shown signed in - there is nothing to display before
            // sign-in, since the assignment lives on the worker's own session, not the device.
            // No picker, no text field: a worker works the warehouse D365FO assigned them, and
            // this exists only so they can see what that is without having to guess from a
            // scan failing.
            if (onSignOut != null) {
                SectionHeader("Assigned warehouse")
                Text(
                    text = worker.warehouseId.ifBlank {
                        "No warehouse is assigned to you. Ask a supervisor."
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                )
                HorizontalDivider()
            }

            // A worker's own preference, not a fleet-commissioning decision - shown only once
            // signed in, alongside the other per-worker things on this screen.
            if (onSignOut != null) {
                SectionHeader("Appearance")
                PaletteRow(selected = palette, onSelect = viewModel::setPalette)
                HorizontalDivider()
            }

            // Sign-out itself lives one tap away on Home's own top bar now, not buried here -
            // this screen keeps only who is signed in, for the rare moment that matters.
            if (onSignOut != null) {
                SectionHeader("Session")
                Text(
                    text = worker.displayName.ifBlank { "Signed in" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(modifier = Modifier.padding(bottom = 28.dp)) { }
        }
    }
}

/**
 * Two rows, selected = filled / unselected = outlined - the same visual language the rest of
 * this app uses for a binary choice, rather than a Material `SegmentedButton` this theme has
 * never otherwise used.
 */
@Composable
private fun AuthTypeRow(
    selected: AuthType,
    onSelect: (AuthType) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        AuthType.entries.forEach { option ->
            val label = when (option) {
                AuthType.CLIENT_CREDENTIALS -> "Client secret"
                AuthType.DEVICE_CODE -> "Device sign-in"
            }
            if (option == selected) {
                Button(
                    onClick = { onSelect(option) },
                    modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                ) { Text(label) }
            } else {
                OutlinedButton(
                    onClick = { onSelect(option) },
                    modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                ) { Text(label) }
            }
        }
    }
}

/**
 * Four options, so laid out as two rows of two rather than [AuthTypeRow]'s single row - same
 * selected = filled / unselected = outlined language, just doubled up to keep each button a
 * comfortable tap width on a handheld screen.
 */
@Composable
private fun TimeoutRow(
    selected: RequestTimeout,
    onSelect: (RequestTimeout) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RequestTimeout.entries.chunked(2).forEach { pair ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                pair.forEach { option ->
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

        is DeviceCodeUiState.WaitingForUser -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Go to ${state.verificationUri} on any device and enter:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = state.userCode,
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Waiting for authorization…", style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        }

        is DeviceCodeUiState.SignedIn -> Card(modifier = Modifier.fillMaxWidth()) {
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = state.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp),
                )
            }
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
 * A row of square accent swatches, one per [ColorPalette] - a tap changes
 * [WarehouseStatusColors.accent] app-wide (see [com.d365fo.mobileops.presentation.common.theme.D365MobileTheme]),
 * everything else about the theme untouched. Swatches rather than labelled buttons: the choice
 * here is entirely visual, so showing the colour says more than its name would.
 */
@Composable
private fun PaletteRow(
    selected: ColorPalette,
    onSelect: (ColorPalette) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ColorPalette.entries.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(option.accent)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else WarehouseStatusColors.border,
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

        is ConnectionTest.Ok -> Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = test.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(12.dp),
            )
        }

        is ConnectionTest.Failed -> Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = test.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp),
    )
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
