package com.d365fo.mobileops.data.security

import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Access tokens from the customer's token service, for a device set up with a device setup code
 * ([DeviceSetupQr]) - no client secret on the device. Each request is signed with the device key
 * ([DeviceKey]); the token is kept in memory and in the encrypted store until it expires, as
 * [ClientCredentialsTokenProvider] does.
 *
 * A device deactivated in D365FO (Mobile devices) gets DEVICE_INACTIVE: its local data, key and
 * connection are removed ([DeviceDeactivation]) - the point of deactivating a lost handheld.
 */
@Singleton
class DeviceTokenProvider @Inject constructor(
    private val client: TokenServiceClient,
    private val connectionSettings: ConnectionSettings,
    private val deviceKey: DeviceKey,
    private val secureStore: SecureStore,
    private val deactivation: Provider<DeviceDeactivation>,
) : TokenProvider {

    private val mutex = Mutex()
    @Volatile private var cached: AccessToken? = null

    private fun storeKey(): String {
        val config = connectionSettings.tenant.value
        return "connection_devicetoken::${config.tokenServiceUrl.lowercase()}|${config.resourceUrl.trimEnd('/').lowercase()}"
    }

    override fun cachedTokenOrNull(): AccessToken? = cached?.takeIf { !it.isExpiring() } ?: stored()

    private fun stored(): AccessToken? {
        val raw = secureStore.getString(storeKey()) ?: return null
        val token = AccessToken(raw.substringAfter('|'), raw.substringBefore('|').toLongOrNull() ?: 0, null, null)
        if (token.value.isBlank() || token.isExpiring()) {
            secureStore.remove(storeKey())
            return null
        }
        cached = token
        return token
    }

    override suspend fun acquireSilent(): NetworkResult<AccessToken> {
        cachedTokenOrNull()?.let { return NetworkResult.Success(it) }
        return mutex.withLock {
            cachedTokenOrNull()?.let { return@withLock NetworkResult.Success(it) }
            val config = connectionSettings.tenant.value
            if (config.tokenServiceUrl.isBlank()) {
                return@withLock NetworkResult.Error(AppFailure.Authentication("No token service configured. Scan a device setup code."))
            }
            if (!deviceKey.exists()) {
                return@withLock NetworkResult.Error(AppFailure.Authentication("This device has no device key. Scan a device setup code."))
            }
            val deviceId = secureStore.deviceId()
            val resource = config.resourceUrl.trimEnd('/')
            val timestamp = System.currentTimeMillis() / 1000
            val nonce = java.util.UUID.randomUUID().toString().replace("-", "")
            val signature = runCatching {
                deviceKey.sign(TokenServiceClient.tokenText(deviceId, resource, timestamp, nonce))
            }.getOrElse {
                Timber.e(it, "device token: signing failed")
                return@withLock NetworkResult.Error(AppFailure.Authentication("The device key could not sign: ${it.message}"))
            }
            when (val answer = client.token(config.tokenServiceUrl, TokenServiceClient.TokenRequest(deviceId, resource, timestamp, nonce, signature))) {
                is TokenServiceClient.Answer.Ok -> {
                    if (answer.body.accessToken.isBlank()) {
                        return@withLock NetworkResult.Error(AppFailure.Authentication("The token service sent no token."))
                    }
                    val token = AccessToken(
                        value = answer.body.accessToken,
                        expiresAtEpochMs = System.currentTimeMillis() + answer.body.expiresIn * 1_000,
                        userObjectId = null,
                        userPrincipalName = null,
                    )
                    cached = token
                    secureStore.putString(storeKey(), "${token.expiresAtEpochMs}|${token.value}")
                    NetworkResult.Success(token)
                }
                is TokenServiceClient.Answer.NoConnection -> NetworkResult.Error(
                    if (answer.error is java.net.UnknownHostException || answer.error is java.net.ConnectException) {
                        AppFailure.Offline(answer.error.message ?: "no connection")
                    } else {
                        AppFailure.Transient(answer.error.message ?: "token request failed")
                    },
                )
                is TokenServiceClient.Answer.Refused -> refused(answer)
            }
        }
    }

    private suspend fun refused(answer: TokenServiceClient.Answer.Refused): NetworkResult<AccessToken> {
        Timber.w("device token: refused %s %s", answer.httpStatus, answer.code)
        return when {
            answer.code == "DEVICE_INACTIVE" -> {
                deactivation.get().wipe()
                NetworkResult.Error(AppFailure.Authentication(DeviceDeactivation.MESSAGE))
            }
            answer.httpStatus == 429 || answer.httpStatus >= 500 -> NetworkResult.Error(
                AppFailure.Transient(answer.message, answer.httpStatus, answer.retryAfterSeconds),
            )
            else -> NetworkResult.Error(AppFailure.Authentication(answer.message))
        }
    }

    override suspend fun signOut() = invalidateCache()

    override fun invalidateCache() {
        cached = null
        runCatching { secureStore.remove(storeKey()) }
    }
}
