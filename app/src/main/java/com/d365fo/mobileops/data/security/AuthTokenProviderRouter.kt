package com.d365fo.mobileops.data.security

import com.d365fo.mobileops.util.NetworkResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [TokenProvider] Hilt actually wires up - see [di.AppModule][com.d365fo.mobileops.di.AppModule].
 *
 * Hilt's `@Binds` picks one concrete implementation at compile time, but which grant this
 * device uses is a runtime setting a supervisor can change from Settings, the same way the
 * target environment (Dev/UAT/Prod) already is. This class is the seam that makes that work:
 * it holds both real providers and reads [ConnectionSettings.tenant] fresh on every call, never
 * caching which one is active, so a mode switch in Settings takes effect on the very next call
 * rather than needing an app restart.
 */
@Singleton
class AuthTokenProviderRouter @Inject constructor(
    private val clientCredentials: ClientCredentialsTokenProvider,
    private val deviceCode: DeviceCodeTokenProvider,
    private val connectionSettings: ConnectionSettings,
) : TokenProvider {

    private fun active(): TokenProvider = when (connectionSettings.tenant.value.authType) {
        AuthType.CLIENT_CREDENTIALS -> clientCredentials
        AuthType.DEVICE_CODE -> deviceCode
    }

    override suspend fun acquireSilent(): NetworkResult<AccessToken> = active().acquireSilent()

    override suspend fun signOut() = active().signOut()

    override fun cachedTokenOrNull(): AccessToken? = active().cachedTokenOrNull()
}
