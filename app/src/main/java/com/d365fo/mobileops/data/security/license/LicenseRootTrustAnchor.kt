package com.d365fo.mobileops.data.security.license

import android.util.Base64
import com.d365fo.mobileops.BuildConfig
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * The vendor's root public key, compiled into every build - the trust anchor the entire
 * licensing design rests on. See `licensing/README.md` for the full chain and
 * `HSMobileOpsLicense.rootPublicKeyXml()` for the AOS-side copy of this exact same key.
 *
 * **Never fetched, never stored in an editable table.** That is the whole point: a customer's
 * own D365FO admin can edit any table in their own tenant, so a database-stored key can never
 * be a real root of trust. A compiled constant cannot be edited that way - the only way to
 * change it is a new APK build, shipped by the vendor.
 *
 * **Raw base64 SubjectPublicKeyInfo DER, not PEM.** `KeyFactory`/`X509EncodedKeySpec` consume
 * DER bytes directly - PEM's `-----BEGIN/END-----` armor and line-wrapping are a text
 * presentation convention with nothing [X509EncodedKeySpec] needs, so parsing it is skipped
 * entirely rather than writing a PEM stripper for no benefit.
 */
object LicenseRootTrustAnchor {

    /**
     * The root public key. `runCatching`-wrapped even though a compiled constant should never
     * fail to parse - if [BuildConfig.LICENSE_ROOT_PUBLIC_KEY_B64] is ever blank (a build that
     * forgot to set it) or malformed, every certificate verification must fail closed, not
     * throw past this point uncaught. See [LicenseCrypto]'s own doc on "never fail open."
     */
    val publicKey: PublicKey? by lazy {
        runCatching {
            val der = Base64.decode(BuildConfig.LICENSE_ROOT_PUBLIC_KEY_B64, Base64.DEFAULT)
            val spec = X509EncodedKeySpec(der)
            KeyFactory.getInstance("RSA").generatePublic(spec)
        }.getOrNull()
    }
}
