package com.d365fo.mobileops.data.security

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The device setup QR code (D365FO: Mobile devices > Device setup codes > New device setup code):
 * `HSMODEV1:` + base64url(JSON). Unlike the connection QR code ([ConnectionQr]) it carries no
 * client secret - only where D365FO and the customer's token service are, the company, and a
 * one-off setup code. The device makes its own key pair and registers it with the setup code;
 * from then on the token service gives it access tokens. See docs/device-setup.md.
 */
object DeviceSetupQr {

    const val PREFIX = "HSMODEV1:"

    @Serializable
    data class Payload(
        @SerialName("v") val version: Int = 1,
        @SerialName("label") val label: String = "",
        @SerialName("url") val url: String,
        @SerialName("company") val company: String = "",
        @SerialName("token") val tokenServiceUrl: String,
        @SerialName("code") val code: String,
    )

    sealed interface Result {
        data class Ready(val payload: Payload, val config: TenantConfig) : Result
        data class Invalid(val reason: String) : Result
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun isDeviceSetupQr(raw: String): Boolean = raw.trim().startsWith(PREFIX)

    fun read(raw: String): Result {
        val text = raw.trim()
        if (!text.startsWith(PREFIX)) return Result.Invalid("This is not a device setup code.")
        val body = runCatching {
            java.util.Base64.getUrlDecoder().decode(text.removePrefix(PREFIX).trim()).toString(Charsets.UTF_8)
        }.getOrNull() ?: return Result.Invalid("This setup code is damaged. Scan it again.")
        val payload = runCatching { json.decodeFromString(Payload.serializer(), body) }.getOrNull()
            ?: return Result.Invalid("This setup code could not be read.")
        if (payload.version != 1) return Result.Invalid("This setup code is for a newer version of the app.")
        if (payload.code.isBlank()) return Result.Invalid("This setup code has no code in it.")
        if (!payload.tokenServiceUrl.trim().startsWith("https://")) {
            return Result.Invalid("This setup code has no valid token service address.")
        }
        val config = TenantConfig(
            label = payload.label.trim().ifBlank { "Production" },
            resourceUrl = payload.url.trim().trimEnd('/'),
            company = payload.company.trim().uppercase(),
            tokenServiceUrl = payload.tokenServiceUrl.trim().trimEnd('/'),
        )
        config.validationError()?.let { return Result.Invalid("This setup code is incomplete: $it.") }
        return Result.Ready(payload, config)
    }
}
