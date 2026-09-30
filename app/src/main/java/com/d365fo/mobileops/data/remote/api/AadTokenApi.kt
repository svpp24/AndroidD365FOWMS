package com.d365fo.mobileops.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * The Azure AD v2.0 token endpoint, called directly for the device-code, refresh-token and
 * client-credentials grants - see [com.d365fo.mobileops.data.security.AuthTokenProviderRouter]
 * for which one is active.
 *
 * MSAL is not used for this. MSAL's Android library is built around interactive public
 * clients, and the request is a single form POST with no redirect and no browser anyway once
 * the device-code exchange itself is done.
 */
interface AadTokenApi {

    /**
     * The app-only grant: a client secret proves the app registration itself, with no person
     * and no browser involved. No refresh token comes back from this grant - there is nothing
     * to refresh, the same secret just gets exchanged again next time.
     */
    @FormUrlEncoded
    @POST("{tenantId}/oauth2/v2.0/token")
    suspend fun clientCredentials(
        @Path("tenantId") tenantId: String,
        @Field("grant_type") grantType: String = "client_credentials",
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("scope") scope: String,
    ): Response<AadTokenResponse>

    /**
     * Starts the device-code grant. The app is a **public** client for this call — no secret,
     * which is the entire point (see DeviceCodeTokenProvider) — so this only works when the
     * app registration behind [clientId] has "Allow public client flows" turned on in Azure AD.
     * Without it, this returns an AADSTS7000218-style error.
     */
    @FormUrlEncoded
    @POST("{tenantId}/oauth2/v2.0/devicecode")
    suspend fun startDeviceCode(
        @Path("tenantId") tenantId: String,
        @Field("client_id") clientId: String,
        @Field("scope") scope: String,
    ): Response<AadDeviceCodeResponse>

    /**
     * Polls the token endpoint during a device-code sign-in. While the user has not yet finished
     * at [AadDeviceCodeResponse.verificationUri], this answers 400 with error "authorization_pending"
     * (see [AadErrorResponse.error]) rather than succeeding or failing outright - the caller polls
     * again after [AadDeviceCodeResponse.intervalSeconds].
     */
    @FormUrlEncoded
    @POST("{tenantId}/oauth2/v2.0/token")
    suspend fun pollDeviceCode(
        @Path("tenantId") tenantId: String,
        @Field("grant_type") grantType: String = "urn:ietf:params:oauth:grant-type:device_code",
        @Field("client_id") clientId: String,
        @Field("device_code") deviceCode: String,
    ): Response<AadTokenResponse>

    /** Silent re-acquisition once a device-code sign-in has already happened once. */
    @FormUrlEncoded
    @POST("{tenantId}/oauth2/v2.0/token")
    suspend fun refreshToken(
        @Path("tenantId") tenantId: String,
        @Field("grant_type") grantType: String = "refresh_token",
        @Field("client_id") clientId: String,
        @Field("refresh_token") refreshToken: String,
        @Field("scope") scope: String,
    ): Response<AadTokenResponse>
}

@Serializable
data class AadTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresInSeconds: Long = 0,
    @SerialName("ext_expires_in") val extExpiresInSeconds: Long = 0,
    /** Present on the device-code and refresh-token grants; absent on client-credentials. */
    @SerialName("refresh_token") val refreshToken: String? = null,
    /**
     * A JWT. Not verified client-side - the signature check that matters happens on D365FO's
     * own end when the access token is presented. Decoded only to read `upn`/`oid` off the
     * unverified payload for display in Settings, never as an authorization decision.
     */
    @SerialName("id_token") val idToken: String? = null,
)

/** POST {tenantId}/oauth2/v2.0/devicecode response. */
@Serializable
data class AadDeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String = "",
    /** Shown to whoever is commissioning the device — short, human-typeable. */
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_uri") val verificationUri: String = "",
    @SerialName("expires_in") val expiresInSeconds: Long = 0,
    /** Minimum seconds between polls. AAD answers "slow_down" if polled faster than this. */
    @SerialName("interval") val intervalSeconds: Long = 5,
    /** Pre-formatted "go to X and enter Y" - shown as-is when present. */
    @SerialName("message") val message: String? = null,
)

/** AAD returns errors as 400 with this shape rather than a plain message. */
@Serializable
data class AadErrorResponse(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("error_codes") val errorCodes: List<Long> = emptyList(),
    @SerialName("correlation_id") val correlationId: String? = null,
) {
    /**
     * AAD error descriptions open with the code and a correlation id before the sentence a
     * human can act on. The first line is the useful part.
     */
    fun readable(): String =
        errorDescription?.lineSequence()?.firstOrNull()?.trim()
            ?: error
            ?: "Azure AD rejected the request"
}
