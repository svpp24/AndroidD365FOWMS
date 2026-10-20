package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contracts for `HSMobileOpsLicenseSvc/getLicense` and `/registerDevice` - the launch-time half
 * of the certificate-chain licensing design, called before any worker has signed in. See
 * `aos/HSMobileOps/AxClass/HSMobileOpsLicenseService.xml` and `LicenseRepository`, which is the
 * one place these are actually called from.
 */
@Serializable
data class GetLicenseServiceRequest(
    @SerialName("_request") val request: GetLicenseRequestBody,
)

@Serializable
data class GetLicenseRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
)

@Serializable
data class GetLicenseServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    /** NO_LICENSE, LICENSE_INVALID, LICENSE_EXPIRED - same codes the sign-in path already uses. */
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
    /** The raw WMSLIC1... string, exactly as imported. Verified client-side against the
     *  device's own already-activated certificate - never trusted on [success] alone. */
    @SerialName("LicenseKey") val licenseKey: String? = null,
    /** This server's own UTC clock, ISO 8601 - what the clock-rollback check anchors to. */
    @SerialName("ServerUtcNow") val serverUtcNow: String? = null,
)

@Serializable
data class RegisterDeviceServiceRequest(
    @SerialName("_request") val request: RegisterDeviceRequestBody,
)

@Serializable
data class RegisterDeviceRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("DeviceId") val deviceId: String,
    @SerialName("DeviceBrand") val deviceBrand: String = "",
    @SerialName("DeviceModel") val deviceModel: String = "",
    @SerialName("Platform") val platform: String = "",
    @SerialName("PlatformVersion") val platformVersion: String = "",
    @SerialName("AppVersionName") val appVersionName: String = "",
    /** A Play Integrity Standard API token from [PlayIntegrityTokenProvider], or null if one
     *  could not be obtained - AOS treats a missing token as an UNVERIFIED integrity verdict,
     *  not a hard failure, see `HSMobileOpsLicense::verifyWithAttestation`. */
    @SerialName("IntegrityToken") val integrityToken: String? = null,
)

@Serializable
data class RegisterDeviceServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    /** NO_LICENSE, LICENSE_INVALID, LICENSE_EXPIRED, DEVICE_LIMIT_REACHED, LICENSE_REVOKED or
     *  INTEGRITY_FAILED - the last two only ever come from the cloud/attestation check layered
     *  on top of the local certificate/license chain, see `HSMobileOpsLicense::
     *  verifyWithAttestation`. */
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
)
