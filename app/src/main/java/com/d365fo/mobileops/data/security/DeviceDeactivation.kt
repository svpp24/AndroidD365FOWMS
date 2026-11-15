package com.d365fo.mobileops.data.security

import com.d365fo.mobileops.data.local.D365Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a device does when the token service says D365FO has deactivated it (Mobile devices,
 * Activate / deactivate): a lost or stolen handheld must stop holding company data. The local
 * database, the device key and the connection's credentials go; the device can be set up again
 * only with a new device setup code.
 */
@Singleton
class DeviceDeactivation @Inject constructor(
    private val database: D365Database,
    private val deviceKey: DeviceKey,
    private val connectionSettings: ConnectionSettings,
    private val secureStore: SecureStore,
    private val workBackup: dagger.Lazy<com.d365fo.mobileops.data.sync.WorkBackup>,
    private val workerSessions: dagger.Lazy<com.d365fo.mobileops.data.repository.WorkerSessionRepository>,
) {
    private val _deactivated = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * True once this device has been wiped for deactivation (also after a restart, until a new
     * setup code is accepted). The device gate watches it: the open app leaves for the start
     * screen with [MESSAGE] at once - not "D365FO can't be reached, keep working" over a device
     * that can no longer send anything (15 Nov 2026).
     */
    val deactivated: kotlinx.coroutines.flow.StateFlow<Boolean> get() = _deactivated.also {
        if (!it.value && wasDeactivated()) it.value = true
    }

    /**
     * [saveFirst]: false when the token service just refused this device - with no token the
     * save can't reach D365FO (and would wait on the token lock it is called from). The work
     * then goes through the token service's backup endpoint, signed with the device key,
     * before the key is deleted - so work captured offline is not lost.
     */
    suspend fun wipe(saveFirst: Boolean = true) = withContext(Dispatchers.IO) {
        Timber.w("device deactivated in D365FO: removing local data")
        // The device's data goes; its D365FO copy stays for a supervisor (WorkBackup.keepBeforeWipe).
        // No D365FO token (the token service refused it): the last set goes through the token service.
        runCatching { workBackup.get().keepBeforeWipe(saveFirst, viaTokenService = !saveFirst) }
        runCatching { database.clearAllTables() }.onFailure { Timber.e(it, "deactivation: database wipe failed") }
        deviceKey.delete()
        connectionSettings.clearAllCredentials()
        secureStore.putBoolean(KEY_DEACTIVATED, true)
        runCatching { workerSessions.get().signOut(reason = "Device deactivated") }
        _deactivated.value = true
    }

    fun wasDeactivated(): Boolean = secureStore.getBoolean(KEY_DEACTIVATED)

    /** A new setup code was scanned and accepted. */
    fun clear() {
        secureStore.remove(KEY_DEACTIVATED)
        _deactivated.value = false
    }

    companion object {
        const val MESSAGE = "This device has been deactivated in D365FO. Its local data was removed. " +
            "Ask your administrator for a new device setup code."
        private const val KEY_DEACTIVATED = "device_deactivated"
    }
}
