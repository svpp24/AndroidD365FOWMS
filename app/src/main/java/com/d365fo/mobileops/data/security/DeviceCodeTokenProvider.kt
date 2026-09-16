package com.d365fo.mobileops.data.security

import com.d365fo.mobileops.data.remote.api.AadDeviceCodeResponse
import com.d365fo.mobileops.data.remote.api.AadErrorResponse
import com.d365fo.mobileops.data.remote.api.AadTokenApi
import com.d365fo.mobileops.data.remote.api.AadTokenResponse
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.Response
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-code token source - the alternative to [ClientCredentialsTokenProvider] that needs no
 * secret on the device at all.
 *
 * The trade runs the other way from client credentials: instead of a fleet-wide secret that a
 * rooted or backed-up handheld yields (see that class's own doc comment), commissioning this
 * device asks one person to sign in once, in a browser, on any device with one - the handheld
 * itself just shows a short code. What it gets back is a refresh token, cached in
 * [SecureStore], that this class re-exchanges for a fresh access token on every subsequent
 * launch without asking anyone to do that again. Signing this device out, or the refresh token
 * expiring or being revoked in Azure AD, is what sends it back through the interactive step.
 *
 * Split in two for the same reason [TokenProvider.acquireSilent] promises never to prompt:
 * [acquireSilent] only ever uses a cached or refreshable token, and the interactive
 * device-code exchange lives in [startDeviceCodeFlow]/[pollForToken] instead, called only from
 * the Settings screen where a human is actually looking at it.
 */
@Singleton
class DeviceCodeTokenProvider @Inject constructor(
    private val api: AadTokenApi,
    private val connectionSettings: ConnectionSettings,
    private val secureStore: SecureStore,
    private val json: Json,
) : TokenProvider {

    private val refreshMutex = Mutex()

    @Volatile
    private var cached: AccessToken? = null

    override fun cachedTokenOrNull(): AccessToken? = cached

    override suspend fun acquireSilent(): NetworkResult<AccessToken> {
        cached?.takeIf { !it.isExpiring() }?.let { return NetworkResult.Success(it) }

        return refreshMutex.withLock {
            cached?.takeIf { !it.isExpiring() }?.let { return@withLock NetworkResult.Success(it) }

            val refreshToken = secureStore.getString(SecureStore.KEY_DEVICE_CODE_REFRESH_TOKEN)
                ?: return@withLock NetworkResult.Error(
                    AppFailure.Authentication(
                        "Device sign-in required. Open Settings and sign in with Microsoft.",
                    ),
                )

            val config = connectionSettings.tenant.value
            val problem = config.credentialsError()
            if (problem != null) return@withLock NetworkResult.Error(AppFailure.Authentication(problem))

            handleTokenResponse(
                runCatching {
                    api.refreshToken(
                        tenantId = config.tenantId,
                        clientId = config.clientId,
                        refreshToken = refreshToken,
                        scope = "${config.resourceUrl.trimEnd('/')}/.default",
                    )
                },
                onInvalidGrant = {
                    // The refresh token itself was rejected - revoked, expired, or the user's
                    // password changed. Clearing it here rather than leaving it to be retried
                    // forever is what actually sends this back through the interactive step.
                    secureStore.remove(SecureStore.KEY_DEVICE_CODE_REFRESH_TOKEN)
                },
            )
        }
    }

    override suspend fun signOut() {
        cached = null
        secureStore.remove(SecureStore.KEY_DEVICE_CODE_REFRESH_TOKEN)
        secureStore.remove(SecureStore.KEY_DEVICE_CODE_UPN)
        secureStore.remove(SecureStore.KEY_DEVICE_CODE_OBJECT_ID)
    }

    /** The identity last established by a successful device-code sign-in, for display only. */
    fun signedInUpn(): String? = secureStore.getString(SecureStore.KEY_DEVICE_CODE_UPN)

    /** Starts a device-code sign-in: the code and URL to show whoever is commissioning this device. */
    suspend fun startDeviceCodeFlow(): NetworkResult<AadDeviceCodeResponse> {
        val config = connectionSettings.tenant.value
        if (config.tenantId.isBlank() || config.clientId.isBlank()) {
            return NetworkResult.Error(
                AppFailure.Authentication("Directory (tenant) ID and client ID are required first"),
            )
        }

        return try {
            val response = api.startDeviceCode(
                tenantId = config.tenantId,
                clientId = config.clientId,
                scope = "${config.resourceUrl.trimEnd('/')}/.default offline_access",
            )
            if (!response.isSuccessful) {
                val aadError = readAadError(response)
                NetworkResult.Error(
                    AppFailure.Authentication(
                        aadError?.readable() ?: "Azure AD returned ${response.code()}",
                    ),
                )
            } else {
                val body = response.body()
                    ?: return NetworkResult.Error(AppFailure.Authentication("empty response"))
                NetworkResult.Success(body)
            }
        } catch (e: Throwable) {
            Timber.e(e, "device code request threw")
            NetworkResult.Error(AppFailure.Authentication(e.message ?: "device code request failed"))
        }
    }

    /**
     * Polls until the person at [AadDeviceCodeResponse.verificationUri] finishes, the code
     * expires, or they decline. Emits at most one terminal state; the caller (Settings) cancels
     * this by cancelling the coroutine it was collected on, which is why nothing here needs its
     * own cancel method.
     */
    fun pollForToken(challenge: AadDeviceCodeResponse): Flow<DeviceCodePollState> = flow {
        emit(DeviceCodePollState.Pending)
        val config = connectionSettings.tenant.value
        var intervalMs = challenge.intervalSeconds.coerceAtLeast(1) * 1_000
        val deadline = System.currentTimeMillis() + challenge.expiresInSeconds * 1_000

        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)

            val response = runCatching {
                api.pollDeviceCode(
                    tenantId = config.tenantId,
                    clientId = config.clientId,
                    deviceCode = challenge.deviceCode,
                )
            }.getOrElse {
                emit(DeviceCodePollState.Error(it.message ?: "network error"))
                return@flow
            }

            if (response.isSuccessful) {
                val body = response.body()
                if (body == null) {
                    emit(DeviceCodePollState.Error("empty response"))
                    return@flow
                }
                val upn = store(body)
                emit(DeviceCodePollState.Success(upn))
                return@flow
            }

            val aadError = readAadError(response)
            when (aadError?.error) {
                "authorization_pending" -> Unit // keep polling at the same interval
                "slow_down" -> intervalMs += 5_000
                "expired_token" -> {
                    emit(DeviceCodePollState.Expired)
                    return@flow
                }
                "authorization_declined" -> {
                    emit(DeviceCodePollState.Declined)
                    return@flow
                }
                else -> {
                    emit(DeviceCodePollState.Error(aadError?.readable() ?: "Azure AD returned ${response.code()}"))
                    return@flow
                }
            }
        }
        emit(DeviceCodePollState.Expired)
    }

    private fun handleTokenResponse(
        result: Result<Response<AadTokenResponse>>,
        onInvalidGrant: () -> Unit,
    ): NetworkResult<AccessToken> {
        val response = result.getOrElse {
            Timber.e(it, "device-code token refresh threw")
            return NetworkResult.Error(AppFailure.Authentication(it.message ?: "refresh failed"))
        }

        if (!response.isSuccessful) {
            val aadError = readAadError(response)
            if (aadError?.error == "invalid_grant") onInvalidGrant()
            return NetworkResult.Error(
                AppFailure.Authentication(aadError?.readable() ?: "Azure AD returned ${response.code()}"),
            )
        }

        val body = response.body()
            ?: return NetworkResult.Error(AppFailure.Authentication("empty token response"))

        val upn = store(body)
        val token = AccessToken(
            value = body.accessToken,
            expiresAtEpochMs = System.currentTimeMillis() + body.expiresInSeconds * 1_000,
            userObjectId = secureStore.getString(SecureStore.KEY_DEVICE_CODE_OBJECT_ID),
            userPrincipalName = upn,
            authType = AuthType.DEVICE_CODE,
        )
        cached = token
        return NetworkResult.Success(token)
    }

    /** Persists the refresh token and decodes the id_token for display; returns the UPN. */
    private fun store(body: AadTokenResponse): String? {
        body.refreshToken?.let { secureStore.putString(SecureStore.KEY_DEVICE_CODE_REFRESH_TOKEN, it) }
        val claims = body.idToken?.let { decodeJwtPayload(it) }
        val upn = claims?.get("preferred_username")?.jsonPrimitiveOrNull()
            ?: claims?.get("upn")?.jsonPrimitiveOrNull()
        val oid = claims?.get("oid")?.jsonPrimitiveOrNull()
        upn?.let { secureStore.putString(SecureStore.KEY_DEVICE_CODE_UPN, it) }
        oid?.let { secureStore.putString(SecureStore.KEY_DEVICE_CODE_OBJECT_ID, it) }
        return upn ?: secureStore.getString(SecureStore.KEY_DEVICE_CODE_UPN)
    }

    /**
     * Reads the unverified claims off a JWT's middle segment. Never used to authorize anything
     * - the token that matters is the opaque access token D365FO itself validates; this is
     * display only, so an untrusted decode here is not a security decision.
     */
    private fun decodeJwtPayload(jwt: String): JsonObject? = runCatching {
        val segment = jwt.split(".").getOrNull(1) ?: return null
        val padded = segment.padEnd((segment.length + 3) / 4 * 4, '=')
        val bytes = java.util.Base64.getUrlDecoder().decode(padded)
        json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
    }.getOrNull()

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull(): String? =
        runCatching { jsonPrimitive.content }.getOrNull()

    /** Reads and parses the error body exactly once - a second read on the same response fails. */
    private fun readAadError(response: Response<*>): AadErrorResponse? =
        runCatching {
            response.errorBody()?.string()?.let { json.decodeFromString<AadErrorResponse>(it) }
        }.getOrNull()
}

/** State of an in-progress device-code sign-in, driven by [DeviceCodeTokenProvider.pollForToken]. */
sealed interface DeviceCodePollState {
    data object Pending : DeviceCodePollState
    data class Success(val userPrincipalName: String?) : DeviceCodePollState
    data object Expired : DeviceCodePollState
    data object Declined : DeviceCodePollState
    data class Error(val message: String) : DeviceCodePollState
}
