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
 *
 * The token is also kept in the encrypted store until it expires (13 Nov, performance: every cold
 * start waited 1-3 s for Azure AD before the first D365FO call). The key names the tenant, the
 * D365FO URL and the client ID, so a changed connection never reuses another one's token.
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

    override fun cachedTokenOrNull(): AccessToken? = credential().let { cached[it.clientId] ?: stored(it) }

    /** Where this connection's token is kept between app starts - encrypted ("connection_" keys). */
    private fun storeKey(app: AppCredential): String {
        val config = connectionSettings.tenant.value
        return "connection_aadtoken::${config.tenantId}|${config.resourceUrl.trimEnd('/').lowercase()}|${app.clientId}"
    }

    /** The token kept from an earlier app start, still good; it goes into the memory cache. */
    private fun stored(app: AppCredential): AccessToken? {
        val raw = secureStore.getString(storeKey(app)) ?: return null
        val expiresAt = raw.substringBefore('|').toLongOrNull() ?: return null
        val token = AccessToken(raw.substringAfter('|'), expiresAt, userObjectId = null, userPrincipalName = null)
        if (token.value.isBlank() || token.isExpiring()) {
            secureStore.remove(storeKey(app))
            return null
        }
        cached[app.clientId] = token
        return token
    }

    override suspend fun acquireSilent(): NetworkResult<AccessToken> {
        val app = credential()
        cached[app.clientId]?.takeIf { !it.isExpiring() }?.let { return NetworkResult.Success(it) }
        stored(app)?.let { return NetworkResult.Success(it) }

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
            secureStore.putString(storeKey(app), "${token.expiresAtEpochMs}|${token.value}")
            NetworkResult.Success(token)
        }
    }

    override suspend fun signOut() {
        dropStored()
        cached.clear()
    }

    override fun invalidateCache() {
        dropStored()
        cached.clear()
    }

    /** The kept tokens of every registration of this connection - a 401 or a new connection means start over. */
    private fun dropStored() {
        runCatching {
            connectionSettings.tenant.value.credentials.forEach { secureStore.remove(storeKey(it)) }
        }
    }

    /** Reads and parses the error body exactly once - a second read on the same response fails. */
    private fun readAadError(response: Response<*>): AadErrorResponse? =
        runCatching {
            response.errorBody()?.string()?.let { json.decodeFromString<AadErrorResponse>(it) }
        }.getOrNull()
}
