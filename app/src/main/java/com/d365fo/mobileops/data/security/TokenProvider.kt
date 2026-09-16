package com.d365fo.mobileops.data.security

import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult

data class AccessToken(
    val value: String,
    val expiresAtEpochMs: Long,
    /** AAD object id of the signed-in worker, or null for a daemon/client-credentials token. */
    val userObjectId: String?,
    val userPrincipalName: String?,
    /**
     * Which grant produced this token. Kept explicit rather than inferred from
     * [userObjectId]/[userPrincipalName] nullability: those two already mean "no user behind a
     * client-credentials token", and a device-code token's id_token can itself be missing a
     * claim (B2B/guest scenarios), which would make that nullability ambiguous the moment a
     * caller needed to tell the two grants apart rather than just "is there a user or not".
     */
    val authType: AuthType,
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
}
