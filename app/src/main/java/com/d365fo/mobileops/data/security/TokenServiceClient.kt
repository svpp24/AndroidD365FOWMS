package com.d365fo.mobileops.data.security

import com.d365fo.mobileops.di.AadClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The customer's token service (an Azure Function in their own tenant - docs/device-setup.md):
 * - `POST {token}/api/register`: setup code + the device's public key, signed with that key.
 *   The service passes it on to D365FO (HSMobileOpsDeviceSetupSvc/registerDeviceKey).
 * - `POST {token}/api/token`: a request signed with the device key; the service checks it
 *   against the key D365FO holds and that the device is active, and answers with an access
 *   token for D365FO from its own app registrations. The device never holds a client secret.
 *
 * The plain AAD client: no D365FO bearer token and no AOS certificate pins on these calls.
 */
@Singleton
class TokenServiceClient @Inject constructor(
    @AadClient private val http: OkHttpClient,
    private val json: Json,
) {

    @Serializable
    data class RegisterRequest(
        val code: String,
        val deviceId: String,
        val publicKey: String,
        /** Over [registerText] with the device key - the service checks the device holds it. */
        val signature: String,
        val deviceBrand: String,
        val deviceModel: String,
        val platform: String,
        val platformVersion: String,
        val appVersionName: String,
        val d365Url: String,
        val company: String,
    )

    @Serializable
    data class RegisterResponse(
        val success: Boolean = false,
        val errorCode: String = "",
        val message: String = "",
        val company: String = "",
        val deviceName: String = "",
        val activeDevices: Int = 0,
        val maxDevices: Int = 0,
    )

    @Serializable
    data class TokenRequest(
        val deviceId: String,
        val resource: String,
        /** Unix seconds; the service takes requests within 5 minutes of its own clock. */
        val timestamp: Long,
        val nonce: String,
        /** Over [tokenText] with the device key. */
        val signature: String,
    )

    @Serializable
    data class TokenResponse(
        @SerialName("access_token") val accessToken: String = "",
        @SerialName("expires_in") val expiresIn: Long = 0,
        val errorCode: String = "",
        val message: String = "",
    )

    sealed interface Answer<out T> {
        data class Ok<T>(val body: T) : Answer<T>
        /** The service answered with an error: [code] its errorCode (or HTTP_<status>). */
        data class Refused(val httpStatus: Int, val code: String, val message: String, val retryAfterSeconds: Long?) : Answer<Nothing>
        data class NoConnection(val error: java.io.IOException) : Answer<Nothing>
    }

    suspend fun register(serviceUrl: String, request: RegisterRequest): Answer<RegisterResponse> =
        post(serviceUrl, "register", json.encodeToString(RegisterRequest.serializer(), request)) {
            json.decodeFromString(RegisterResponse.serializer(), it)
        }.let { answer ->
            if (answer is Answer.Ok && !answer.body.success) {
                Answer.Refused(200, answer.body.errorCode.ifBlank { "REFUSED" }, answer.body.message, null)
            } else {
                answer
            }
        }

    suspend fun token(serviceUrl: String, request: TokenRequest): Answer<TokenResponse> =
        post(serviceUrl, "token", json.encodeToString(TokenRequest.serializer(), request)) {
            json.decodeFromString(TokenResponse.serializer(), it)
        }

    private suspend fun <T> post(serviceUrl: String, path: String, body: String, parse: (String) -> T): Answer<T> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${serviceUrl.trimEnd('/')}/api/$path")
                .post(body.toRequestBody(JSON))
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        runCatching { Answer.Ok(parse(text)) }.getOrElse {
                            Answer.Refused(response.code, "BAD_ANSWER", "The token service sent an answer the app cannot read.", null)
                        }
                    } else {
                        val error = runCatching { json.decodeFromString(TokenResponse.serializer(), text) }.getOrNull()
                        Answer.Refused(
                            httpStatus = response.code,
                            code = error?.errorCode?.ifBlank { null } ?: "HTTP_${response.code}",
                            message = error?.message?.ifBlank { null } ?: "The token service answered ${response.code}.",
                            retryAfterSeconds = response.header("Retry-After")?.toLongOrNull(),
                        )
                    }
                }
            } catch (e: java.io.IOException) {
                Answer.NoConnection(e)
            }
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun registerText(code: String, deviceId: String, publicKey: String): String =
            "HSMODEV1-REGISTER\n${code.trim().uppercase()}\n$deviceId\n$publicKey"

        fun tokenText(deviceId: String, resource: String, timestamp: Long, nonce: String): String =
            "HSMODEV1-TOKEN\n$deviceId\n$resource\n$timestamp\n$nonce"
    }
}
