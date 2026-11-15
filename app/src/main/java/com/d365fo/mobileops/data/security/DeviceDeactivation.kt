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
    suspend fun wipe(saveFirst: Boolean = true) {
        // Once: the token refusal and the session check can both see it.
        if (!wiping.compareAndSet(false, true)) return
        try {
            wipeOnce(saveFirst)
        } finally {
            wiping.set(false)
        }
    }

    private val wiping = java.util.concurrent.atomic.AtomicBoolean(false)

    private suspend fun wipeOnce(saveFirst: Boolean) = withContext(Dispatchers.IO) {
        Timber.w("device deactivated in D365FO: removing local data")
        // The device's data goes; its D365FO copy stays for a supervisor (WorkBackup.keepBeforeWipe).
        // No D365FO token (the token service refused it): the last set goes through the token service.
        runCatching { workBackup.get().keepBeforeWipe(saveFirst, viaTokenService = !saveFirst) }
        // The database holds this environment's data only (switching environments empties it).
        // Its key and connection go; the device's keys and connections for the other
        // environments stay (user, 15 Nov 2026).
        val host = connectionSettings.tenant.value.host
        secureStore.putString(KEY_DEACTIVATED_LABEL, connectionSettings.tenant.value.label)
        runCatching { database.clearAllTables() }.onFailure { Timber.e(it, "deactivation: database wipe failed") }
        deviceKey.delete(host)
        connectionSettings.clearActiveCredentials()
        secureStore.putBoolean(KEY_DEACTIVATED, true)
        runCatching { workerSessions.get().signOut(reason = "Device deactivated") }
        _deactivated.value = true
    }

    fun wasDeactivated(): Boolean = secureStore.getBoolean(KEY_DEACTIVATED)

    /**
     * The start screen's message (user, 15 Nov 2026): short, names the profile, no address -
     * "Active profile Dev has deactivated this device."
     */
    fun message(): String = secureStore.getString(KEY_DEACTIVATED_LABEL)?.takeIf { it.isNotBlank() }
        ?.let { "Active profile $it has deactivated this device." } ?: MESSAGE

    /** A new setup code was scanned and accepted. */
    fun clear() {
        secureStore.remove(KEY_DEACTIVATED)
        secureStore.remove(KEY_DEACTIVATED_LABEL)
        _deactivated.value = false
    }

    companion object {
        const val MESSAGE = "This device has been deactivated in this D365FO environment. Its local data was removed. " +
            "Ask your administrator for a new device setup code."
        private const val KEY_DEACTIVATED = "device_deactivated"
        private const val KEY_DEACTIVATED_LABEL = "device_deactivated_label"

        /** Whether [reason] is a deactivation message - the start screen shows no address then. */
        fun isDeactivation(reason: String?): Boolean =
            reason != null && (reason == MESSAGE || (reason.startsWith("Active profile ") && reason.endsWith(" has deactivated this device.")))
    }
}
