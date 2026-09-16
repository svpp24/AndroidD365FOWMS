package com.d365fo.mobileops.data.security

import com.d365fo.mobileops.data.remote.api.AadErrorResponse
import com.d365fo.mobileops.data.remote.api.AadTokenApi
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client-credentials token source.
 *
 * The app authenticates as itself using a client secret, with no user involved. Chosen
 * deliberately for this deployment; two consequences are worth stating plainly because they
 * are invisible once it works:
 *
 *  - **Every posting carries the service identity, not the worker's.** D365FO will stamp the
 *    same CreatedBy on every product receipt from every handheld. Receiving disputes lose the
 *    "who scanned this" trail that a warehouse normally relies on. The device id is still
 *    sent in the posting payload, so it is recoverable from the app's own audit rows, but it
 *    will not be on the D365FO transaction.
 *  - **A secret on a device is a fleet-wide credential.** It lives in EncryptedSharedPreferences
 *    rather than the APK, so it is rotatable without a rebuild and is not extractable by
 *    unzipping the package — but a rooted or backed-up handheld still yields it, and it is the
 *    same secret on every device.
 *
 * This is the only token source. Interactive Microsoft sign-in was removed: a shared warehouse
 * handheld has no individual Microsoft account behind it, and the worker identity that matters
 * comes from HSMobileOpsUser instead.
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
            // Re-check under the lock: a burst of queued postings would otherwise each
            // trigger their own token request against AAD.
            cached?.takeIf { !it.isExpiring() }?.let { return@withLock NetworkResult.Success(it) }
            fetch()
        }
    }

    /**
     * Identical to [acquireSilent].
     *
     * There is nothing interactive about this grant — no browser, no user. The login screen
     * is skipped entirely in this mode, and this override exists only so the interface stays
     * honest for callers that do not know which provider they hold.
     */
    override suspend fun signOut() {
        cached = null
    }

    private suspend fun fetch(): NetworkResult<AccessToken> {
        val config = connectionSettings.tenant.value
        val problem = config.credentialsError()
        if (problem != null) return NetworkResult.Error(AppFailure.Authentication(problem))

        return try {
            val response = api.clientCredentials(
                tenantId = config.tenantId,
                clientId = config.clientId,
                clientSecret = config.clientSecret,
                scope = "${config.resourceUrl.trimEnd('/')}/.default",
            )

            if (!response.isSuccessful) {
                val detail = runCatching {
                    response.errorBody()?.string()
                        ?.let { json.decodeFromString<AadErrorResponse>(it).readable() }
                }.getOrNull()
                Timber.w("AAD token request failed: %s", detail)
                return NetworkResult.Error(
                    AppFailure.Authentication(detail ?: "Azure AD returned ${response.code()}"),
                )
            }

            val body = response.body()
                ?: return NetworkResult.Error(AppFailure.Authentication("empty token response"))

            val token = AccessToken(
                value = body.accessToken,
                expiresAtEpochMs = System.currentTimeMillis() + body.expiresInSeconds * 1_000,
                // No user in this flow. The service identity is the caller, which is exactly
                // the trade-off documented on this class.
                userObjectId = null,
                userPrincipalName = null,
                authType = AuthType.CLIENT_CREDENTIALS,
            )
            cached = token
            Timber.i("acquired service token, valid for %d s", body.expiresInSeconds)
            NetworkResult.Success(token)
        } catch (e: Throwable) {
            Timber.e(e, "AAD token request threw")
            NetworkResult.Error(AppFailure.Authentication(e.message ?: "token request failed"))
        }
    }
}

