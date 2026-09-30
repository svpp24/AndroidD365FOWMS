package com.d365fo.mobileops.data.security.license

import android.util.Base64
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.time.Instant

/**
 * Every failure - malformed input, a bad signature, an unparseable payload, an expired window -
 * folds into [Invalid]. Nothing in this file throws outward; this sealed type is the literal
 * implementation of "any parse or verification exception means invalid, never fail open."
 */
sealed interface LicenseVerifyResult<out T> {
    data class Valid<T>(val payload: T) : LicenseVerifyResult<T>
    data class Invalid(val reason: String) : LicenseVerifyResult<Nothing>
}

/**
 * Verifies the two signed-string formats this app's licensing design uses - `WMSCERT1...`
 * (root-signed) and `WMSLIC1...` (customer-signed) - and nothing else. See
 * `licensing/wms_license_tool.py` for the Python side that produces these strings and
 * `HSMobileOpsLicense.xml` for the AOS side that verifies them identically.
 *
 * RSA-2048, SHA256withRSA/PKCS#1 v1.5. Signed over the base64url payload TEXT, not the decoded
 * JSON bytes - matches the vendor tool and the AOS side exactly, so there is exactly one
 * "split on the two dots, verify the first segment's own bytes" shape shared everywhere.
 */
object LicenseCrypto {

    private const val CERT_PREFIX = "WMSCERT1"
    private const val LICENSE_PREFIX = "WMSLIC1"

    private val json = Json { ignoreUnknownKeys = true }

    fun verifyCertificate(raw: String): LicenseVerifyResult<CertificatePayload> = runCatching {
        val rootKey = LicenseRootTrustAnchor.publicKey
            ?: return LicenseVerifyResult.Invalid("no root trust anchor compiled into this build")

        val (payloadB64, signatureB64) = split(raw, CERT_PREFIX)
            ?: return LicenseVerifyResult.Invalid("malformed certificate")

        if (!verifySignature(payloadB64, signatureB64, rootKey)) {
            return LicenseVerifyResult.Invalid("certificate failed signature verification against the root key")
        }

        val payload = decodePayload<CertificatePayload>(payloadB64)
            ?: return LicenseVerifyResult.Invalid("malformed certificate payload")

        val notBefore = parseUtc(payload.notBefore)
            ?: return LicenseVerifyResult.Invalid("certificate has an unreadable notBefore date")
        val notAfter = parseUtc(payload.notAfter)
            ?: return LicenseVerifyResult.Invalid("certificate has an unreadable notAfter date")

        val now = Instant.now()
        if (now.isBefore(notBefore) || now.isAfter(notAfter)) {
            return LicenseVerifyResult.Invalid("certificate is outside its validity window")
        }

        LicenseVerifyResult.Valid(payload)
    }.getOrElse { LicenseVerifyResult.Invalid("certificate verification threw: ${it.message}") }

    /**
     * [customerPublicKeyXml] must come from an ALREADY-VERIFIED [CertificatePayload] - never
     * from anywhere else. This function does not itself re-verify the certificate; callers are
     * expected to call [verifyCertificate] first (see `LicenseRepository`, the one real caller).
     */
    fun verifyLicense(raw: String, customerPublicKeyXml: String): LicenseVerifyResult<LicensePayload> = runCatching {
        val customerKey = parseRsaKeyValueXml(customerPublicKeyXml)
            ?: return LicenseVerifyResult.Invalid("certificate's customer public key is malformed")

        val (payloadB64, signatureB64) = split(raw, LICENSE_PREFIX)
            ?: return LicenseVerifyResult.Invalid("malformed license")

        if (!verifySignature(payloadB64, signatureB64, customerKey)) {
            return LicenseVerifyResult.Invalid("license failed signature verification against the certificate's customer key")
        }

        val payload = decodePayload<LicensePayload>(payloadB64)
            ?: return LicenseVerifyResult.Invalid("malformed license payload")

        LicenseVerifyResult.Valid(payload)
    }.getOrElse { LicenseVerifyResult.Invalid("license verification threw: ${it.message}") }

    // -- Shared primitives, mirroring HSMobileOpsLicense.xml and wms_license_tool.py exactly --

    private fun split(signed: String, expectedPrefix: String): Pair<String, String>? {
        if (!signed.startsWith("$expectedPrefix.")) return null
        val rest = signed.substring(expectedPrefix.length + 1)
        val dot = rest.indexOf('.')
        if (dot < 0 || rest.indexOf('.', dot + 1) >= 0) return null
        val payloadB64 = rest.substring(0, dot)
        val signatureB64 = rest.substring(dot + 1)
        if (payloadB64.isEmpty() || signatureB64.isEmpty()) return null
        return payloadB64 to signatureB64
    }

    private fun verifySignature(payloadB64Url: String, signatureB64Url: String, publicKey: PublicKey): Boolean =
        runCatching {
            val signatureBytes = b64UrlDecode(signatureB64Url)
            Signature.getInstance("SHA256withRSA").apply {
                initVerify(publicKey)
                update(payloadB64Url.toByteArray(Charsets.US_ASCII))
            }.verify(signatureBytes)
        }.getOrDefault(false)

    private inline fun <reified T> decodePayload(payloadB64Url: String): T? = runCatching {
        val bytes = b64UrlDecode(payloadB64Url)
        json.decodeFromString<T>(bytes.toString(Charsets.UTF_8))
    }.getOrNull()

    /** "yyyy-MM-ddTHH:mm:ssZ" - Instant.parse accepts this exact ISO-8601 UTC shape natively. */
    private fun parseUtc(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()

    /** RFC 4648 section 5, unpadded - restores standard padding before decoding. */
    private fun b64UrlDecode(text: String): ByteArray {
        val padded = text + "=".repeat((4 - text.length % 4) % 4)
        return Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    /**
     * `<RSAKeyValue><Modulus>base64</Modulus><Exponent>base64</Exponent></RSAKeyValue>` - .NET's
     * `RSACryptoServiceProvider.ToXmlString(false)` format, what `wms_license_tool.py` writes
     * and what `HSMobileOpsLicense.xml` already verifies against server-side via
     * `FromXmlString`. Read with plain string search rather than a real XML parser - this is
     * exactly two known, fixed tag pairs from a source this app itself controls the format of
     * (`wms_license_tool.py`), not third-party XML, so a full parser buys nothing a
     * `substringAfter`/`substringBefore` pair doesn't already give with far less surface area.
     * Modulus/Exponent are standard base64 (not base64url) - matches
     * `rsa_public_key_to_xml`'s own `base64.b64encode` in the Python tool.
     */
    private fun parseRsaKeyValueXml(xml: String): PublicKey? = runCatching {
        val modulusB64 = xml.substringAfter("<Modulus>").substringBefore("</Modulus>")
        val exponentB64 = xml.substringAfter("<Exponent>").substringBefore("</Exponent>")
        if (modulusB64.isEmpty() || exponentB64.isEmpty()) return@runCatching null

        val modulus = BigInteger(1, Base64.decode(modulusB64, Base64.DEFAULT))
        val exponent = BigInteger(1, Base64.decode(exponentB64, Base64.DEFAULT))

        KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
    }.getOrNull()
}
