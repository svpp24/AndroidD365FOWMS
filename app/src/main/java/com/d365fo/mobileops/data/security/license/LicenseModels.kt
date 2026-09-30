package com.d365fo.mobileops.data.security.license

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The JSON payload inside a `WMSCERT1...` certificate string, signed by the vendor's root
 * private key - see `licensing/wms_license_tool.py`'s `sign-certificate` command, which is
 * the only thing that ever produces one. Field names and shapes must match that tool exactly;
 * this is not a contract either side gets to renegotiate independently.
 */
@Serializable
data class CertificatePayload(
    @SerialName("v") val version: Int = 1,
    @SerialName("kid") val kid: String = "",
    @SerialName("customerId") val customerId: String = "",
    @SerialName("tenantId") val tenantId: String = "",
    /**
     * RSAKeyValue XML, not PEM - the only encoding this certificate carries. The original
     * design carried both, which roughly doubled the payload and produced a QR too dense to
     * scan reliably on a real device (confirmed live: version 35, near the practical maximum).
     * AOS already verifies against this same XML form (`RSACryptoServiceProvider.FromXmlString`,
     * confirmed working); [LicenseCrypto.parseRsaKeyValueXml] is Android's own reader for it -
     * see that function for why a second, PEM encoding buys nothing `X509EncodedKeySpec` needs
     * that this doesn't already have.
     */
    @SerialName("customerPublicKeyXml") val customerPublicKeyXml: String = "",
    /** ISO 8601 UTC, "yyyy-MM-ddTHH:mm:ssZ". */
    @SerialName("notBefore") val notBefore: String = "",
    @SerialName("notAfter") val notAfter: String = "",
)

/**
 * The JSON payload inside a `WMSLIC1...` license string, signed by the CERTIFICATE's own
 * customer private key - never the root key. See `licensing/wms_license_tool.py`'s
 * `sign-license` command.
 */
@Serializable
data class LicensePayload(
    @SerialName("v") val version: Int = 1,
    @SerialName("customerId") val customerId: String = "",
    @SerialName("tenantId") val tenantId: String = "",
    /** ISO 8601 UTC, "yyyy-MM-ddTHH:mm:ssZ". */
    @SerialName("expiryUtc") val expiryUtc: String = "",
    @SerialName("maxDevices") val maxDevices: Int = 0,
    @SerialName("issuedUtc") val issuedUtc: String = "",
)
