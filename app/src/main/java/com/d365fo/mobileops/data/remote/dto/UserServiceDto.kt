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
)

@Serializable
data class SignInServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    /**
     * INVALID_CREDENTIALS, INACTIVE, LOCKED_OUT, NO_WAREHOUSE, INVALID_REQUEST.
     *
     * A wrong user name and a wrong password both come back as INVALID_CREDENTIALS by design, so
     * the client must not try to be more specific than the server was.
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
)

@Serializable
data class WarehouseAssignmentDto(
    @SerialName("SiteId") val siteId: String = "",
    @SerialName("WarehouseId") val warehouseId: String = "",
    /** Shown in the switcher, so it reads "Main store" rather than "100011". */
    @SerialName("WarehouseName") val warehouseName: String = "",
    @SerialName("IsDefault") val isDefault: Boolean = false,
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
