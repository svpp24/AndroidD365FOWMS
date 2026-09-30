package com.d365fo.mobileops.data.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.d365fo.mobileops.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks the app's own installed signing certificate against the release key's known
 * fingerprint - one layer of the threat model's "APK repackaging" mitigation (see
 * `licensing/README.md`): a decompiled-and-resigned APK, distributed under a different key,
 * fails this check and refuses to run, same as `HSMobileOpsLicense`'s own honest caveat that no
 * application-level check can promise more than that against someone with full device control.
 *
 * **Release builds only.** A debug build is always signed with the local debug keystore, which
 * never matches [BuildConfig.RELEASE_SIGNING_CERT_SHA256] - checking there would brick every
 * developer's own build, not catch tampering.
 *
 * **A blank expected fingerprint skips the check rather than failing it** - that means the
 * build pipeline never set `RELEASE_SIGNING_CERT_SHA256` (see `local.properties.template`), a
 * configuration gap, not evidence of tampering. Once configured, a genuine mismatch - or being
 * unable to read the app's own signature at all - is treated as [tampered], never fail-open.
 */
@Singleton
class SelfIntegrityCheck @Inject constructor(@ApplicationContext private val context: Context) {

    val tampered: Boolean by lazy { computeTampered() }

    private fun computeTampered(): Boolean {
        if (BuildConfig.DEBUG) return false

        val expected = BuildConfig.RELEASE_SIGNING_CERT_SHA256
        if (expected.isBlank()) {
            Timber.w("RELEASE_SIGNING_CERT_SHA256 not configured - skipping the signing-certificate self-check")
            return false
        }

        val actual = installedSigningCertSha256()
        if (actual == null) {
            Timber.e("could not read this app's own signing certificate - treating as tampered")
            return true
        }

        val normalize: (String) -> String = { it.replace(":", "").lowercase() }
        val ok = normalize(actual) == normalize(expected)
        if (!ok) {
            Timber.e("signing certificate mismatch: expected=%s actual=%s", expected, actual)
        }
        return !ok
    }

    private fun installedSigningCertSha256(): String? = runCatching {
        val packageManager = context.packageManager
        val signatureBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo ?: return null
            val signatures = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            signatures?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        } ?: return null

        MessageDigest.getInstance("SHA-256").digest(signatureBytes)
            .joinToString(":") { "%02X".format(it) }
    }.getOrNull()
}
