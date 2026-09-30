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
 * Restored (see [AuthTokenProviderRouter]) after device-code-only auth proved too much
 * operational friction to run with day to day - admin consent, the "Allow public client flows"
 * toggle, and slow AAD polling all had to be worked through live before a single API call could
 * be made. A fleet-wide secret has its own real cost (see the doc this class had before it was
 * briefly deleted: a rooted or backed-up handheld yields it), but that tradeoff is now the
 * device owner's explicit, informed choice rather than this app's only option.
 */
@Singleton
class ClientCredentialsTokenProvider @Inject constructor(
    private val api: AadTokenApi,
    private val connectionSettings: ConnectionSettings,
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

            val config = connectionSettings.tenant.value
            val problem = config.credentialsError()
            if (problem != null) return@withLock NetworkResult.Error(AppFailure.Authentication(problem))

            val response = try {
                api.clientCredentials(
                    tenantId = config.tenantId,
                    clientId = config.clientId,
                    clientSecret = config.clientSecret,
                    scope = "${config.resourceUrl.trimEnd('/')}/.default",
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
            cached = token
            NetworkResult.Success(token)
        }
    }

    override suspend fun signOut() {
        cached = null
    }

    override fun invalidateCache() {
        cached = null
    }

    /** Reads and parses the error body exactly once - a second read on the same response fails. */
    private fun readAadError(response: Response<*>): AadErrorResponse? =
        runCatching {
            response.errorBody()?.string()?.let { json.decodeFromString<AadErrorResponse>(it) }
        }.getOrNull()
}
