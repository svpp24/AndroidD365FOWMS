package com.d365fo.mobileops.presentation.common

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.d365fo.mobileops.data.sync.SyncStatus
import com.d365fo.mobileops.presentation.auth.NotConfiguredScreen
import com.d365fo.mobileops.presentation.auth.SessionState
import com.d365fo.mobileops.presentation.auth.SessionViewModel
import com.d365fo.mobileops.presentation.auth.WorkerLoginScreen
import com.d365fo.mobileops.presentation.auth.WorkerSessionViewModel

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
) {
    val session by sessionViewModel.state.collectAsStateWithLifecycle()
    val worker by workerSessionViewModel.session.collectAsStateWithLifecycle()
    val tenant by settingsViewModel.tenant.collectAsStateWithLifecycle()
    val navController = rememberNavController()

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
        // The device is through, but nobody has said who they are yet.
        session is SessionState.Ready && worker == null -> WorkerLoginScreen(
            onOpenSettings = { settingsWhileSignedOut = true },
        )

        session is SessionState.Ready -> AppNavGraph(
            syncStatus = syncStatus,
            warehouseId = worker?.selected?.warehouseId,
            navController = navController,
            onSignOut = {
                // Both layers drop together. Signing out of the worker alone would leave the
                // next person on the device with a live D365FO connection and no identity.
                workerSessionViewModel.signOut()
                sessionViewModel.signOut()
            },
        )

        // Not configured, or the device token could not be obtained. There is no sign-in to
        // offer - the fix is in Settings, so send them there rather than to a dead button.
        else -> NotConfiguredScreen(
            state = session,
            tenant = tenant,
            onOpenSettings = { settingsWhileSignedOut = true },
        )
    }
}
