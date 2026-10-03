package com.d365fo.mobileops.presentation.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.d365fo.mobileops.data.repository.LicenseState
import com.d365fo.mobileops.data.sync.SyncStatus
import com.d365fo.mobileops.presentation.auth.LicenseActivationScreen
import com.d365fo.mobileops.presentation.auth.LicenseInvalidScreen
import com.d365fo.mobileops.presentation.auth.LicenseViewModel
import com.d365fo.mobileops.presentation.auth.NotConfiguredScreen
import com.d365fo.mobileops.presentation.auth.OnboardingScreen
import com.d365fo.mobileops.presentation.auth.OnboardingViewModel
import com.d365fo.mobileops.presentation.auth.SessionState
import com.d365fo.mobileops.presentation.auth.SessionViewModel
import com.d365fo.mobileops.presentation.auth.WorkerLoginScreen
import com.d365fo.mobileops.presentation.auth.WorkerSessionViewModel
import com.d365fo.mobileops.presentation.common.components.WarningBanner

/**
 * The gate between signed-out and operational.
 *
 * Sits above the NavHost rather than being a start destination inside it. A login route
 * inside the graph can be reached with the back button, and a worker who backs out of the
 * worklist into a login screen they already passed will reasonably conclude the app has
 * logged them out mid-shift.
 *
 * **Two gates, in order.** The device gate proves this handheld may talk to D365FO at all: a
 * configured tenant, and a token the app obtains as itself. The worker gate then asks
 * who is holding it. Both are needed: the first is shared by every device in the fleet, so on its
 * own every posting would carry the same CreatedBy and a GRN could never say who received the
 * pallet.
 */
@Composable
fun AppRoot(
    syncStatus: SyncStatus,
    sessionViewModel: SessionViewModel = hiltViewModel(),
    workerSessionViewModel: WorkerSessionViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    onboardingViewModel: OnboardingViewModel = hiltViewModel(),
    licenseViewModel: LicenseViewModel = hiltViewModel(),
    integrityViewModel: IntegrityViewModel = hiltViewModel(),
) {
    // Checked before anything else in this whole gate, including Settings - a resigned APK
    // (see SelfIntegrityCheck) gets no path forward at all, not even to reconfigure a
    // connection. Debug builds are never flagged - see that class's own doc.
    if (integrityViewModel.tampered) {
        TamperedScreen()
        return
    }

    val session by sessionViewModel.state.collectAsStateWithLifecycle()
    val worker by workerSessionViewModel.session.collectAsStateWithLifecycle()
    val licenseWarning by workerSessionViewModel.licenseWarning.collectAsStateWithLifecycle()
    val licenseState by licenseViewModel.state.collectAsStateWithLifecycle()
    val tenant by settingsViewModel.tenant.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    // The device gate (AAD token) has to clear before this can call any AOS service at all -
    // re-runs every time the device gate itself flips back to Ready (a fresh launch, or a
    // reconnect after Settings was fixed), never on every recomposition.
    LaunchedEffect(session is SessionState.Ready) {
        if (session is SessionState.Ready) licenseViewModel.validate()
    }

    // Settings has to be reachable *before* sign-in — an unconfigured tenant is exactly why
    // sign-in would be unavailable, so hiding the fix behind the gate would deadlock setup.
    var settingsWhileSignedOut by remember { mutableStateOf(false) }

    if (settingsWhileSignedOut) {
        BackHandler { settingsWhileSignedOut = false }
        SettingsScreen(
            onBack = {
                settingsWhileSignedOut = false
                // Re-evaluate: the tenant may have just been filled in, which turns
                // NotConfigured into SignedOut and makes the sign-in button live.
                sessionViewModel.refresh()
            },
        )
        return
    }

    when {
        // The licensing gate - between the device gate above and everything below it. A device
        // that cannot reach D365FO at all never gets this far (falls through to
        // NotConfiguredScreen below); one that can, but has never been activated or whose
        // license does not check out, stops here rather than reaching onboarding or sign-in.
        // See LicenseRepository's own doc for the full chain this state comes from.
        session is SessionState.Ready && licenseState is LicenseState.NeedsActivation ->
            LicenseActivationScreen()

        session is SessionState.Ready && licenseState is LicenseState.Blocked ->
            LicenseInvalidScreen(
                message = (licenseState as LicenseState.Blocked).message,
                onRetry = licenseViewModel::validate,
            )

        // Checking gets its own spinner rather than falling through to WorkerLoginScreen and
        // then possibly yanking the worker straight to LicenseActivationScreen/
        // LicenseInvalidScreen a moment later - same "don't flash the wrong screen" reasoning
        // NotConfiguredScreen already applies to SessionState.Checking internally.
        session is SessionState.Ready && licenseState is LicenseState.Checking ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

        // The very first thing anyone sees on a fresh install - ahead of sign-in, since one of
        // its own pages explains what signing in is for. Gated on the device reaching Ready
        // (not shown while still resolving a token/tenant) so it never flashes ahead of a real
        // NotConfiguredScreen a first-run device might actually need first.
        session is SessionState.Ready && worker == null && !onboardingViewModel.hasSeenOnboarding ->
            OnboardingScreen(onDone = onboardingViewModel::markSeen)

        // The device is through, but nobody has said who they are yet.
        session is SessionState.Ready && worker == null -> WorkerLoginScreen(
            onOpenSettings = { settingsWhileSignedOut = true },
        )

        // Several warehouses in D365FO and none is the default: ask once, before Home.
        session is SessionState.Ready && worker?.selected == null && (worker?.warehouses?.size ?: 0) > 1 ->
            PickWarehouseScreen(
                warehouses = worker?.warehouses.orEmpty(),
                onSignOut = workerSessionViewModel::signOut,
            )

        session is SessionState.Ready -> Column(modifier = Modifier.fillMaxSize()) {
            // Shown once, right where a worker lands after signing in, for as long as the
            // license's renewal-warning window lasts - dismissed here, not auto-hidden, so it
            // doesn't disappear before anyone reads it. See WorkerSessionRepository.signIn.
            licenseWarning?.let { message ->
                WarningBanner(
                    message = message,
                    onDismiss = workerSessionViewModel::dismissLicenseWarning,
                )
            }
            // weight(1f), not left to wrap-content - AppNavGraph's own Scaffold expects to fill
            // whatever space it's given, and an unbounded Column would otherwise hand it zero.
            //
            // pointerInput below observes touches at the Initial pass without consuming them -
            // every tap still reaches whatever button/field is under it exactly as before. This
            // is purely "is anyone touching the screen at all", feeding WorkerSessionViewModel's
            // 30-minute idle sign-out (see its own doc) - not a click handler in its own right.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(pass = PointerEventPass.Initial)
                            workerSessionViewModel.notifyActivity()
                        }
                    },
            ) {
                AppNavGraph(
                    syncStatus = syncStatus,
                    warehouseId = worker?.selected?.warehouseId,
                    navController = navController,
                    onSignOut = {
                        // Both layers drop together. Signing out of the worker alone would leave
                        // the next person on the device with a live D365FO connection and no
                        // identity.
                        workerSessionViewModel.signOut()
                        sessionViewModel.signOut()
                    },
                )
            }
        }

        // Not configured, or the device token could not be obtained. There is no sign-in to
        // offer - the fix is in Settings, so send them there rather than to a dead button.
        else -> NotConfiguredScreen(
            state = session,
            tenant = tenant,
            onOpenSettings = { settingsWhileSignedOut = true },
        )
    }
}

/**
 * The dead end when [SelfIntegrityCheck.tampered] is true - deliberately offers nothing to
 * tap, not even Settings. Reinstalling the genuine, vendor-signed build is the only fix, and
 * nothing on this screen can do that for the person looking at it.
 */
@Composable
private fun TamperedScreen() {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "This app cannot run. Reinstall it from the official source.",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}
