package com.d365fo.mobileops.data.demo

import android.content.Context
import com.d365fo.mobileops.data.local.D365Database
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.ClientCredentialsTokenProvider
import com.d365fo.mobileops.data.security.ConnectionSettings
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.data.security.TenantConfig
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts and ends demo mode (see [DemoMode]). Starting saves a separate "Demo" connection profile
 * pointing nowhere real, turns the demo answers on and signs in as [DemoBackend.USER] - no
 * password the user has to know. Ending removes that profile, so a device later set up for a
 * real company starts clean. Local data is wiped both ways, so demo records and real ones never
 * meet in the device database.
 */
@Singleton
class DemoLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionSettings: ConnectionSettings,
    private val workerSessions: WorkerSessionRepository,
    private val database: D365Database,
    private val tokenProvider: ClientCredentialsTokenProvider,
    private val secureStore: SecureStore,
    private val skuSync: com.d365fo.mobileops.data.repository.SkuSyncRepository,
) {
    /** True while [start] runs: the sign-out watcher must not end a demo that is still signing in. */
    @Volatile var starting: Boolean = false
        private set

    suspend fun start(): NetworkResult<Unit> {
        starting = true
        try {
            withContext(Dispatchers.IO) { database.clearAllTables() }
            DemoBackend.reset()
            DemoMode.set(context, true)
            connectionSettings.saveTenant(
                TenantConfig(
                    label = DemoMode.CONNECTION_LABEL,
                    resourceUrl = DemoMode.CONNECTION_URL,
                    tenantId = "demo",
                    clientId = "demo",
                    clientSecret = "demo",
                    company = DemoBackend.COMPANY,
                ),
            )
            connectionSettings.setPostingEnabled(true)
            tokenProvider.invalidateCache()
            return when (val r = workerSessions.signIn(DemoBackend.USER, "demo", deviceId = secureStore.deviceId())) {
                is NetworkResult.Success -> {
                    // The demo's item list now: Home's scans look items up on the device, and the
                    // background download waits for the handheld to be on charge.
                    runCatching { skuSync.runStep(20_000, com.d365fo.mobileops.data.repository.ManualSync.FULL) }
                        .onFailure { Timber.w(it, "demo item list") }
                    NetworkResult.Success(Unit)
                }
                is NetworkResult.Error -> { end(); r }
                NetworkResult.Loading -> NetworkResult.Success(Unit)
            }
        } finally {
            starting = false
        }
    }

    /** Back to an unconfigured device. Safe to call when the demo is not on. */
    suspend fun end() {
        if (!DemoMode.isActive && connectionSettings.tenant.value.label != DemoMode.CONNECTION_LABEL) return
        Timber.i("demo mode ended")
        workerSessions.signOut("Demo ended")
        connectionSettings.deleteProfile(DemoMode.CONNECTION_LABEL)
        DemoMode.set(context, false)
        tokenProvider.invalidateCache()
        withContext(Dispatchers.IO) { database.clearAllTables() }
    }
}
