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
) {
    suspend fun wipe() = withContext(Dispatchers.IO) {
        Timber.w("device deactivated in D365FO: removing local data")
        runCatching { database.clearAllTables() }.onFailure { Timber.e(it, "deactivation: database wipe failed") }
        deviceKey.delete()
        connectionSettings.clearAllCredentials()
        secureStore.putBoolean(KEY_DEACTIVATED, true)
    }

    fun wasDeactivated(): Boolean = secureStore.getBoolean(KEY_DEACTIVATED)

    /** A new setup code was scanned and accepted. */
    fun clear() = secureStore.remove(KEY_DEACTIVATED)

    companion object {
        const val MESSAGE = "This device has been deactivated in D365FO. Its local data was removed. " +
            "Ask your administrator for a new device setup code."
        private const val KEY_DEACTIVATED = "device_deactivated"
    }
}
