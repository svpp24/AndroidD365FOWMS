package com.d365fo.mobileops.data.security

import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult

data class AccessToken(
    val value: String,
    val expiresAtEpochMs: Long,
    /** AAD object id of the signed-in worker - device-code sign-in is the only grant this app
     *  uses, so this is only ever null if the id_token itself is missing the claim (B2B/guest
     *  scenarios). */
    val userObjectId: String?,
    val userPrincipalName: String?,
) {
    /** Refresh five minutes early so a long scan session never stalls mid-post. */
    fun isExpiring(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs >= expiresAtEpochMs - REFRESH_SKEW_MS

    companion object {
        const val REFRESH_SKEW_MS = 5 * 60 * 1000L
    }
}

interface TokenProvider {
    /** Returns a valid token, refreshing silently if needed. Never prompts. */
    suspend fun acquireSilent(): NetworkResult<AccessToken>

    suspend fun signOut()

    fun cachedTokenOrNull(): AccessToken?

    /**
     * Drops the in-memory cached access token only - never the persisted refresh token or
     * client secret, unlike [signOut]. Must be called whenever the active [TenantConfig]
     * changes (see [ConnectionSettings]'s profiles), because [acquireSilent]'s cache is not
     * itself scoped to a profile: a token cached while one environment was active still looks
     * "not expiring" by wall-clock time alone after switching to another, and would otherwise be
     * reused for a resource it was never issued for - confirmed live, this is exactly what
     * produced a persistent 401 from AOS after switching from UAT to DEV, even though every
     * credential involved (client ID, D365FO-side AAD app registration, the resulting token
     * itself, tested directly) was correct. Calling this forces the next [acquireSilent] to
     * refresh against whichever profile is active *then*, using its own stored refresh token or
     * secret - never a fresh interactive step.
     */
    fun invalidateCache()
}
