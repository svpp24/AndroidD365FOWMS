package com.d365fo.mobileops.data.security

import com.d365fo.mobileops.data.remote.api.AadErrorResponse
import com.d365fo.mobileops.data.remote.api.AadTokenApi
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import retrofit2.Response
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client-credentials token source - a client secret compiled into the build or typed into
 * Settings acts as the device's whole identity, with no per-device interactive step at all.
 *
 * There is no [userObjectId]/[userPrincipalName] here in any meaningful sense: the app
 * registration itself is the caller, not a person, so [AccessToken.userObjectId] and
 * [AccessToken.userPrincipalName] are always null for a token this class issues. Worker
 * identity in this app comes entirely from HSMobileOpsUser sign-in, never from this token.
 *
 * The app's only device credential - device-code sign-in was removed. The connection (and the
 * secrets) arrive by scanning the connection QR code (see [ConnectionQr]).
 *
 * With several app registrations configured, [CredentialRouter] says which one this device uses
 * right now, and a token is cached per registration, so moving off a throttled one and back
 * needs no extra token requests.
 */
@Singleton
class ClientCredentialsTokenProvider @Inject constructor(
    private val api: AadTokenApi,
    private val connectionSettings: ConnectionSettings,
    private val json: Json,
    private val router: CredentialRouter,
    private val secureStore: SecureStore,
) : TokenProvider {

    private val refreshMutex = Mutex()

    /** One token per client ID - see the class doc. */
    private val cached = java.util.concurrent.ConcurrentHashMap<String, AccessToken>()

    private fun credential(): AppCredential =
        router.current(connectionSettings.tenant.value.credentials, secureStore.deviceId())

    override fun cachedTokenOrNull(): AccessToken? = cached[credential().clientId]

    override suspend fun acquireSilent(): NetworkResult<AccessToken> {
        val app = credential()
        cached[app.clientId]?.takeIf { !it.isExpiring() }?.let { return NetworkResult.Success(it) }

        return refreshMutex.withLock {
            cached[app.clientId]?.takeIf { !it.isExpiring() }?.let { return@withLock NetworkResult.Success(it) }

            val config = connectionSettings.tenant.value
            val problem = config.credentialsError()
            if (problem != null) return@withLock NetworkResult.Error(AppFailure.Authentication(problem))

            val response = try {
                api.clientCredentials(
                    tenantId = config.tenantId,
                    clientId = app.clientId,
                    clientSecret = app.clientSecret,
                    scope = "${config.resourceUrl.trimEnd('/')}/.default",
                )
            } catch (e: java.io.IOException) {
                // No answer from Azure AD at all is a connection problem, not a credential one -
                // the device may still work offline (offline sign-in, saved lookups).
                Timber.w(e, "client-credentials token request: no connection")
                return@withLock NetworkResult.Error(
                    if (e is java.net.UnknownHostException || e is java.net.ConnectException) {
                        AppFailure.Offline(e.message ?: "no connection")
                    } else {
                        AppFailure.Transient(e.message ?: "token request failed")
                    },
                )
            } catch (e: Throwable) {
                Timber.e(e, "client-credentials token request threw")
                return@withLock NetworkResult.Error(AppFailure.Authentication(e.message ?: "token request failed"))
            }

            if (!response.isSuccessful) {
                val aadError = readAadError(response)
                return@withLock NetworkResult.Error(
                    AppFailure.Authentication(aadError?.readable() ?: "Azure AD returned ${response.code()}"),
                )
            }

            val body = response.body()
                ?: return@withLock NetworkResult.Error(AppFailure.Authentication("empty token response"))

            val token = AccessToken(
                value = body.accessToken,
                expiresAtEpochMs = System.currentTimeMillis() + body.expiresInSeconds * 1_000,
                userObjectId = null,
                userPrincipalName = null,
            )
            cached[app.clientId] = token
            NetworkResult.Success(token)
        }
    }

    override suspend fun signOut() {
        cached.clear()
    }

    override fun invalidateCache() {
        cached.clear()
    }

    /** Reads and parses the error body exactly once - a second read on the same response fails. */
    private fun readAadError(response: Response<*>): AadErrorResponse? =
        runCatching {
            response.errorBody()?.string()?.let { json.decodeFromString<AadErrorResponse>(it) }
        }.getOrNull()
}
