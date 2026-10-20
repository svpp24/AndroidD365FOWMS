package com.d365fo.mobileops.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * The Azure AD v2.0 token endpoint, for the client-credentials grant - the app's only device
 * credential (device-code sign-in was removed). A single form POST; MSAL is not needed for it.
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
}

@Serializable
data class AadTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresInSeconds: Long = 0,
    @SerialName("ext_expires_in") val extExpiresInSeconds: Long = 0,
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
