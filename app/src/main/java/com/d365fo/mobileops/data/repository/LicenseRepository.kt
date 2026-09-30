package com.d365fo.mobileops.data.repository

import android.os.Build
import com.d365fo.mobileops.BuildConfig
import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.GetLicenseRequestBody
import com.d365fo.mobileops.data.remote.dto.GetLicenseServiceRequest
import com.d365fo.mobileops.data.remote.dto.RegisterDeviceRequestBody
import com.d365fo.mobileops.data.remote.dto.RegisterDeviceServiceRequest
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.data.security.license.CertificatePayload
import com.d365fo.mobileops.data.security.license.LicenseCrypto
import com.d365fo.mobileops.data.security.license.LicenseVerifyResult
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** What [LicenseRepository.state] gates `AppRoot` on. */
sealed interface LicenseState {
    /** The very first check of a cold start, before anything has been decided. */
    data object Checking : LicenseState

    /** No certificate stored yet - show [com.d365fo.mobileops.presentation.auth.LicenseActivationScreen]. */
    data object NeedsActivation : LicenseState

    /** A definitive refusal - bad signature, expired, tenant/customer mismatch, device limit
     *  reached, or a genuine connectivity failure outside the offline grace period. Never
     *  recoverable by retrying the same input; the message is shown as-is, it already names
     *  the specific reason. */
    data class Blocked(val message: String) : LicenseState

    /** Either a real full validation just succeeded, or a connectivity failure landed within
     *  the offline grace period of the last real success - see [LicenseRepository.GRACE_PERIOD_MS]. */
    data object Ok : LicenseState
}

/**
 * The per-launch orchestrator for the certificate-chain licensing design - see
 * `licensing/README.md` for the full chain this walks, end to end:
 *
 * 1. Re-verify the stored certificate against the compiled-in root key ([LicenseRootTrustAnchor]).
 * 2. Call `HSMobileOpsLicenseSvc/getLicense`.
 * 3. Verify the returned license's signature against the CERTIFICATE's own customer public key -
 *    never anything the server merely asserts.
 * 4. Check the license's customerId/tenantId match the certificate's, and that it hasn't expired.
 * 5. Check neither this device's own clock nor the server's returned clock is earlier than the
 *    last trusted time this device has ever seen - a rollback in either direction is rejected.
 * 6. Call `HSMobileOpsLicenseSvc/registerDevice` - the server-enforced device-limit gate.
 * 7. On full success, cache the validation for the offline grace period.
 *
 * **Only a genuine connectivity failure (steps 2 or 6 not reachable at all) falls back to the
 * grace period.** A step that ran and produced a definite answer - bad signature, expired,
 * mismatched tenant, device limit reached - is never grace-covered; see [failOrGrace] and its
 * call sites.
 */
@Singleton
class LicenseRepository @Inject constructor(
    private val service: MobileOpsServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val secureStore: SecureStore,
) {

    private val _state = MutableStateFlow<LicenseState>(LicenseState.Checking)
    val state: StateFlow<LicenseState> = _state.asStateFlow()

    private fun company(): String =
        companyProvider()?.takeIf { it.isNotBlank() }?.lowercase().orEmpty()

    /**
     * Verifies a freshly-scanned certificate and, only on success, stores it - see
     * `LicenseActivationScreen`, the one caller. Never re-runs [validate] itself; the caller
     * does that once activation succeeds, same as any other state transition here.
     */
    fun activate(rawCertificate: String): LicenseVerifyResult<CertificatePayload> {
        val result = LicenseCrypto.verifyCertificate(rawCertificate)
        if (result is LicenseVerifyResult.Valid) {
            secureStore.setLicenseCertificate(rawCertificate)
        }
        return result
    }

    /** The full per-launch chain described in the class doc. Updates [state] and returns it. */
    suspend fun validate(): LicenseState {
        _state.value = LicenseState.Checking
        val result = runCatching { validateInternal() }
            .getOrElse {
                // "Any parse or verification exception means invalid, never fail open" - this
                // is the outermost net for anything the steps below did not already catch.
                Timber.e(it, "license validation threw")
                LicenseState.Blocked("Could not verify this app's license.")
            }
        _state.value = result
        return result
    }

    private suspend fun validateInternal(): LicenseState {
        val storedCertificate = secureStore.licenseCertificate()
            ?: return LicenseState.NeedsActivation

        // Step 1 - a definitive local check, never grace-covered: a certificate that fails to
        // re-verify against the compiled root key means corrupted storage or tampering, not a
        // network hiccup.
        val certPayload = when (val certResult = LicenseCrypto.verifyCertificate(storedCertificate)) {
            is LicenseVerifyResult.Invalid ->
                return LicenseState.Blocked("The stored certificate is no longer valid: ${certResult.reason}. Re-activate this device.")
            is LicenseVerifyResult.Valid -> certResult.payload
        }

        // Step 2.
        val licenseResponse = when (
            val result = errorMapper.safeCall {
                service.getLicense(GetLicenseServiceRequest(GetLicenseRequestBody(company())))
            }
        ) {
            is NetworkResult.Error -> return failOrGrace(result.failure.message)
            NetworkResult.Loading -> return failOrGrace("unexpected Loading")
            is NetworkResult.Success -> result.data
        }

        if (!licenseResponse.success) {
            // A real answer from AOS (NO_LICENSE/LICENSE_INVALID/LICENSE_EXPIRED), not a
            // transport failure - never grace-covered.
            return LicenseState.Blocked(
                licenseResponse.message ?: "This app's license is not valid. Contact your administrator.",
            )
        }

        val serverNow = licenseResponse.serverUtcNow?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return LicenseState.Blocked("The server did not return a valid clock reading.")

        // Step 5 - checked before trusting anything the license itself says, so a rolled-back
        // clock can never be used to make an already-expired license look current again.
        val lastTrusted = secureStore.lastTrustedTimeUtcMillis()
        if (lastTrusted != null) {
            val deviceNowMillis = System.currentTimeMillis()
            if (serverNow.toEpochMilli() < lastTrusted || deviceNowMillis < lastTrusted) {
                return LicenseState.Blocked(
                    "This device's clock (or the server's) appears to have moved backward. Contact your administrator.",
                )
            }
        }

        val rawLicense = licenseResponse.licenseKey
            ?: return LicenseState.Blocked("The server returned no license.")

        // Step 3 - verified against the CERTIFICATE's own key, never anything the response
        // itself claims about being valid.
        val licensePayload = when (val licResult = LicenseCrypto.verifyLicense(rawLicense, certPayload.customerPublicKeyXml)) {
            is LicenseVerifyResult.Invalid ->
                return LicenseState.Blocked("The license on file did not verify: ${licResult.reason}.")
            is LicenseVerifyResult.Valid -> licResult.payload
        }

        // Step 4.
        if (licensePayload.customerId != certPayload.customerId || licensePayload.tenantId != certPayload.tenantId) {
            return LicenseState.Blocked("The license on file does not match this device's certificate.")
        }

        val expiry = runCatching { Instant.parse(licensePayload.expiryUtc) }.getOrNull()
            ?: return LicenseState.Blocked("The license on file has an unreadable expiry date.")
        if (serverNow.isAfter(expiry)) {
            return LicenseState.Blocked("This app's license expired on ${licensePayload.expiryUtc}. Contact your administrator.")
        }

        // Step 6.
        val registerResponse = when (
            val result = errorMapper.safeCall {
                service.registerDevice(
                    RegisterDeviceServiceRequest(
                        RegisterDeviceRequestBody(
                            dataAreaId = company(),
                            deviceId = secureStore.deviceId(),
                            deviceBrand = Build.MANUFACTURER,
                            deviceModel = Build.MODEL,
                            platform = "Android",
                            platformVersion = Build.VERSION.RELEASE.orEmpty(),
                            appVersionName = BuildConfig.VERSION_NAME,
                        ),
                    ),
                )
            }
        ) {
            is NetworkResult.Error -> return failOrGrace(result.failure.message)
            NetworkResult.Loading -> return failOrGrace("unexpected Loading")
            is NetworkResult.Success -> result.data
        }

        if (!registerResponse.success) {
            return LicenseState.Blocked(
                registerResponse.message ?: "This device could not be registered. Contact your administrator.",
            )
        }

        // Step 7.
        secureStore.setLastTrustedTimeUtcMillis(serverNow.toEpochMilli())
        secureStore.setLastLicenseValidationUtcMillis(serverNow.toEpochMilli())

        return LicenseState.Ok
    }

    /**
     * Only ever called for a transport-level failure (no HTTP answer, a timeout, offline) - see
     * the call sites, both `NetworkResult.Error`. Falls back to the last cached successful
     * validation for [GRACE_PERIOD_MS]; outside that window, or with no prior success at all,
     * the same reason is surfaced as a hard [LicenseState.Blocked].
     */
    private fun failOrGrace(reason: String?): LicenseState {
        val lastValidation = secureStore.lastLicenseValidationUtcMillis()
        if (lastValidation != null && System.currentTimeMillis() - lastValidation < GRACE_PERIOD_MS) {
            Timber.w("license check failed (%s) - within grace period, proceeding", reason)
            return LicenseState.Ok
        }
        return LicenseState.Blocked(reason ?: "Could not verify this app's license. Check the connection and try again.")
    }

    private companion object {
        /** How long the app keeps working on the last successful validation alone, once a
         *  live check genuinely cannot be reached - see the class doc's step 7 and [failOrGrace]. */
        const val GRACE_PERIOD_MS = 72 * 60 * 60 * 1000L
    }
}
