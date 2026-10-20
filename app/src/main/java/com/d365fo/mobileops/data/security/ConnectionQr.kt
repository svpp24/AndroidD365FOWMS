package com.d365fo.mobileops.data.security

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The connection QR code: how a device is pointed at D365FO - environment URL, tenant,
 * company and every app registration (client ID and secret) - by scanning one code instead of
 * typing secrets on a handheld. Made with tools/make_connection_qr.py.
 *
 * Two forms:
 * - `HSMOCONN1:` + base64url(JSON) - plain. Anyone who photographs it has the secrets, so it is
 *   for a code shown on screen or printed for a controlled setup session only.
 * - `HSMOCONN1P:` + base64url(salt 16 | iv 12 | AES-256-GCM ciphertext) - encrypted with a key
 *   derived from a setup PIN (PBKDF2-HMAC-SHA256, [PIN_ITERATIONS]); the device asks for the
 *   PIN. A photographed code is then useless without it.
 *
 * - the JSON itself, unencoded - so any offline QR generator can make one from the JSON. As
 *   exposed as the plain form; never paste real secrets into an online QR generator.
 *
 * The prefix keeps it apart from the licence certificate QR (`WMSCERT1.`), which is scanned on
 * its own screen.
 */
object ConnectionQr {

    const val PREFIX_PLAIN = "HSMOCONN1:"
    const val PREFIX_PIN = "HSMOCONN1P:"
    const val PIN_ITERATIONS = 120_000

    @Serializable
    data class Payload(
        @SerialName("v") val version: Int = 1,
        @SerialName("label") val label: String = "",
        @SerialName("url") val url: String,
        @SerialName("tenant") val tenant: String,
        @SerialName("company") val company: String = "",
        @SerialName("apps") val apps: List<App>,
    )

    @Serializable
    data class App(
        @SerialName("id") val id: String,
        @SerialName("secret") val secret: String,
    )

    sealed interface Result {
        /** [company] is blank when the code does not set one. */
        data class Ready(val config: TenantConfig, val company: String) : Result
        data object NeedsPin : Result
        data class Invalid(val reason: String) : Result
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun isConnectionQr(raw: String): Boolean =
        raw.trim().let { it.startsWith(PREFIX_PLAIN) || it.startsWith(PREFIX_PIN) || isRawJson(it) }

    /** The JSON pasted straight into a QR generator - recognised by its own required keys. */
    private fun isRawJson(text: String): Boolean =
        text.startsWith("{") && "\"apps\"" in text && "\"url\"" in text

    fun read(raw: String, pin: String? = null): Result {
        val text = raw.trim()
        val payloadJson = when {
            text.startsWith(PREFIX_PLAIN) ->
                decode(text.removePrefix(PREFIX_PLAIN))?.toString(Charsets.UTF_8)
                    ?: return Result.Invalid("This connection code is damaged. Scan it again.")
            text.startsWith(PREFIX_PIN) -> {
                if (pin.isNullOrBlank()) return Result.NeedsPin
                val sealed = decode(text.removePrefix(PREFIX_PIN))
                    ?: return Result.Invalid("This connection code is damaged. Scan it again.")
                decrypt(sealed, pin) ?: return Result.Invalid("Wrong PIN, or the code is damaged.")
            }
            isRawJson(text) -> text
            else -> return Result.Invalid("This is not a connection code.")
        }

        val payload = runCatching { json.decodeFromString(Payload.serializer(), payloadJson) }.getOrNull()
            ?: return Result.Invalid("This connection code could not be read.")
        if (payload.version != 1) return Result.Invalid("This connection code is for a newer version of the app.")
        if (payload.apps.isEmpty()) return Result.Invalid("This connection code has no app registration.")

        val first = payload.apps.first()
        val config = TenantConfig(
            label = payload.label.trim().ifBlank { D365Environment.UAT.name },
            resourceUrl = payload.url.trim().trimEnd('/'),
            tenantId = payload.tenant.trim(),
            clientId = first.id.trim(),
            clientSecret = first.secret.trim(),
            extraCredentials = payload.apps.drop(1).map { AppCredential(it.id.trim(), it.secret.trim()) },
            company = payload.company.trim().uppercase(),
        )
        config.validationError()?.let { return Result.Invalid("This connection code is incomplete: $it.") }
        return Result.Ready(config, payload.company.trim().uppercase())
    }

    // java.util.Base64 (API 26+, this app's minimum) so the format is testable on the JVM.
    private fun decode(text: String): ByteArray? = runCatching {
        java.util.Base64.getUrlDecoder().decode(text.trim())
    }.getOrNull()

    /** salt (16) | iv (12) | ciphertext + GCM tag. Null on a wrong PIN - GCM refuses to open. */
    private fun decrypt(sealed: ByteArray, pin: String): String? = runCatching {
        require(sealed.size > 16 + 12 + 16)
        val salt = sealed.copyOfRange(0, 16)
        val iv = sealed.copyOfRange(16, 28)
        val body = sealed.copyOfRange(28, sealed.size)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, PIN_ITERATIONS, 256))
            .encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        cipher.doFinal(body).toString(Charsets.UTF_8)
    }.getOrNull()
}
