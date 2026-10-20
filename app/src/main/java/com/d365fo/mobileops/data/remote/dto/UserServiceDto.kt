package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contracts for the HSMobileOps user service — the worker identity layer.
 *
 * Two layers of identity, answering different questions. The device authenticates to D365FO with a
 * service principal, which proves the *app* may talk to the system; every device shares it, so on
 * its own every posting would carry the same CreatedBy. These calls add the second layer: which
 * worker. That is what makes a posted receipt attributable to a person.
 *
 * **A password appears in a request and never in a response.** Nothing here carries one back, and
 * nothing stores one on the device — see WorkerSessionRepository.
 */
@Serializable
data class SignInServiceRequest(
    @SerialName("_request") val request: SignInRequestBody,
)

@Serializable
data class SignInRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("UserName") val userName: String,
    @SerialName("Password") val password: String,
    @SerialName("DeviceId") val deviceId: String = "",
    /**
     * Feeds HSMobileOpsDevice, the fleet-visibility list a supervisor sees in D365FO itself -
     * the same idea as the standard Warehouse app's own registered-devices screen, built from
     * whatever a real sign-in already carries rather than a separate registration call.
     */
    @SerialName("DeviceBrand") val deviceBrand: String = "",
    @SerialName("DeviceModel") val deviceModel: String = "",
    @SerialName("Platform") val platform: String = "",
    @SerialName("PlatformVersion") val platformVersion: String = "",
    @SerialName("AppVersionName") val appVersionName: String = "",
    /** A Play Integrity Standard API token from [com.d365fo.mobileops.data.security.PlayIntegrityTokenProvider],
     *  or null if one could not be obtained - see `HSMobileOpsLicense::verifyWithAttestation` for
     *  how AOS treats a missing token (UNVERIFIED, not a hard failure). */
    @SerialName("IntegrityToken") val integrityToken: String? = null,
)

@Serializable
data class SignInServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    /**
     * INVALID_CREDENTIALS, INACTIVE, LOCKED_OUT, NO_WAREHOUSE, INVALID_REQUEST, NO_LICENSE,
     * LICENSE_INVALID, LICENSE_EXPIRED, DEVICE_LIMIT_REACHED, LICENSE_REVOKED, INTEGRITY_FAILED.
     *
     * A wrong user name and a wrong password both come back as INVALID_CREDENTIALS by design, so
     * the client must not try to be more specific than the server was. The license-related codes
     * are all checked server-side before credentials are even looked at - see
     * WorkerSessionRepository.signIn. The last two (LICENSE_REVOKED, INTEGRITY_FAILED) only ever
     * come from the live cloud/attestation check layered on top of the local certificate/license
     * chain - see `HSMobileOpsLicense::verifyWithAttestation`.
     */
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
    @SerialName("UserName") val userName: String = "",
    @SerialName("DisplayName") val displayName: String = "",
    @SerialName("PersonnelNumber") val personnelNumber: String = "",
    @SerialName("IsSupervisor") val isSupervisor: Boolean = false,
    /** Distinct from IsSupervisor - see the field help on HSMobileOpsUser.IsCycleCountSupervisor. */
    @SerialName("IsCycleCountSupervisor") val isCycleCountSupervisor: Boolean = false,
    /** Zero means "no personal override" - see ReceivingFlowViewModel.effectiveMaximum. */
    @SerialName("MaxOverPercent") val maxOverPercent: Double = 0.0,
    @SerialName("MaxOverQty") val maxOverQty: Double = 0.0,
    @SerialName("MustChangePassword") val mustChangePassword: Boolean = false,
    @SerialName("Warehouses") val warehouses: List<WarehouseAssignmentDto> = emptyList(),
    /**
     * Set only alongside Success = true, when the app's license expires within the server's
     * renewal-warning window (HSMobileOpsUserService.LicenseWarningDays) - never blocks the
     * sign-in, unlike every ErrorCode above. Blank the rest of the time.
     */
    @SerialName("LicenseWarning") val licenseWarning: String? = null,
    /**
     * Explicit *restrictions* only - an empty list (every worker today, and any worker an admin
     * has never touched) means every module stays allowed. See
     * WorkerSessionRepository.signIn's own note on why absence must mean "allowed," never
     * "denied": this field did not exist before this feature, and every current worker already
     * has unrestricted access - defaulting to deny-all the moment this ships would silently lock
     * every module for every worker until an admin explicitly re-granted each one.
     */
    @SerialName("ModulePermissions") val modulePermissions: List<ModulePermissionDto> = emptyList(),
)

@Serializable
data class WarehouseAssignmentDto(
    @SerialName("SiteId") val siteId: String = "",
    @SerialName("WarehouseId") val warehouseId: String = "",
    /** Shown in the switcher, so it reads "Main store" rather than "100011". */
    @SerialName("WarehouseName") val warehouseName: String = "",
    @SerialName("IsDefault") val isDefault: Boolean = false,
)

/**
 * One row of [SignInServiceResponse.modulePermissions] - `module` is a
 * [com.d365fo.mobileops.domain.model.WarehouseWorkflow] name (`"PO_RECEIVING"` etc.), matched by
 * `WorkerSessionRepository.signIn()` against that enum. An AOS build that predates this feature,
 * or one where an admin has never touched a given worker, simply never sends this field or sends
 * it empty - handled the same way either way (see the field doc above).
 */
@Serializable
data class ModulePermissionDto(
    @SerialName("Module") val module: String = "",
    @SerialName("IsAllowed") val isAllowed: Boolean = true,
)

// -- Password change --------------------------------------------------------------------------

@Serializable
data class ChangePasswordServiceRequest(
    @SerialName("_request") val request: ChangePasswordRequestBody,
)

@Serializable
data class ChangePasswordRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("UserName") val userName: String,
    /** Required even for a forced change; see the service contract for why. */
    @SerialName("CurrentPassword") val currentPassword: String,
    @SerialName("NewPassword") val newPassword: String,
)

@Serializable
data class ChangePasswordServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    /** POLICY when the new password is too short, INVALID_CREDENTIALS when the old one is wrong. */
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
)
