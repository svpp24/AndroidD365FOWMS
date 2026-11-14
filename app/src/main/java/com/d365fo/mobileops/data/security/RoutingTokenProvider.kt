package com.d365fo.mobileops.data.security

import com.d365fo.mobileops.util.NetworkResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's [TokenProvider]: a connection set up with a device setup code (it has a token
 * service address) gets its tokens from the token service ([DeviceTokenProvider]); one set up
 * with a connection QR code still uses its client secret ([ClientCredentialsTokenProvider])
 * while fleets change over.
 */
@Singleton
class RoutingTokenProvider @Inject constructor(
    private val connectionSettings: ConnectionSettings,
    private val device: DeviceTokenProvider,
    private val clientCredentials: ClientCredentialsTokenProvider,
) : TokenProvider {

    private fun current(): TokenProvider =
        if (connectionSettings.tenant.value.usesTokenService) device else clientCredentials

    override suspend fun acquireSilent(): NetworkResult<AccessToken> = current().acquireSilent()

    override suspend fun signOut() = current().signOut()

    override fun cachedTokenOrNull(): AccessToken? = current().cachedTokenOrNull()

    override fun invalidateCache() {
        device.invalidateCache()
        clientCredentials.invalidateCache()
    }
}
