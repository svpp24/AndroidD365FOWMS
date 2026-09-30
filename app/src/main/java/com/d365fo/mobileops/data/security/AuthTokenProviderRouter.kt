package com.d365fo.mobileops.data.security

import com.d365fo.mobileops.util.NetworkResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks which device credential is in force, per the active [TenantConfig.authType] - the one
 * `TokenProvider` the rest of the app injects never needs to know which grant is behind it.
 *
 * Re-reads [ConnectionSettings.tenant] on every call rather than caching the choice, so flipping
 * [AuthType] in Settings and saving takes effect on the very next token request, not after a
 * restart.
 */
@Singleton
class AuthTokenProviderRouter @Inject constructor(
    private val connectionSettings: ConnectionSettings,
    private val clientCredentials: ClientCredentialsTokenProvider,
    private val deviceCode: DeviceCodeTokenProvider,
) : TokenProvider {

    private fun active(): TokenProvider = when (connectionSettings.tenant.value.authType) {
        AuthType.CLIENT_CREDENTIALS -> clientCredentials
        AuthType.DEVICE_CODE -> deviceCode
    }

    override suspend fun acquireSilent(): NetworkResult<AccessToken> = active().acquireSilent()

    override suspend fun signOut() = active().signOut()

    override fun cachedTokenOrNull(): AccessToken? = active().cachedTokenOrNull()

    /**
     * Clears both providers' caches, not just whichever is active now - a profile switch can
     * change [AuthType] too (a device-code profile to a client-credentials one, say), and the
     * provider that was active a moment ago is exactly the one whose stale cache would otherwise
     * survive the switch.
     */
    override fun invalidateCache() {
        clientCredentials.invalidateCache()
        deviceCode.invalidateCache()
    }
}
