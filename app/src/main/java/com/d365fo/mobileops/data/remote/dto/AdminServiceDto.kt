package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contracts for the two Admin Center write/read calls added to `HSMobileOpsUserSvc` -
 * `setModulePermission` and `getWorkerDirectory`. See
 * `aos/HSMobileOps/AxClass/HSMobileOpsUserService.xml` for both.
 *
 * Both carry [SetModulePermissionRequestBody.actingUserName] /
 * [WorkerDirectoryRequestBody.actingUserName] rather than trusting a client-side supervisor
 * flag - the AOS re-looks-up that user and checks `IsSupervisor` itself before doing anything.
 * The app's own role gating (hiding the Admin Center menu entry from a non-supervisor) is UX,
 * not the security boundary; this field is what makes that boundary real.
 */
@Serializable
data class SetModulePermissionServiceRequest(
    @SerialName("_request") val request: SetModulePermissionRequestBody,
)

@Serializable
data class SetModulePermissionRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("ActingUserName") val actingUserName: String,
    @SerialName("TargetUserName") val targetUserName: String,
    /** A [com.d365fo.mobileops.domain.model.WarehouseWorkflow] name. */
    @SerialName("Module") val module: String,
    @SerialName("IsAllowed") val isAllowed: Boolean,
)

@Serializable
data class SetModulePermissionServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    /** INVALID_REQUEST, NOT_SUPERVISOR, ACTING_USER_NOT_FOUND, TARGET_USER_NOT_FOUND, INVALID_MODULE. */
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
)

@Serializable
data class WorkerDirectoryServiceRequest(
    @SerialName("_request") val request: WorkerDirectoryRequestBody,
)

@Serializable
data class WorkerDirectoryRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("ActingUserName") val actingUserName: String,
)

@Serializable
data class WorkerDirectoryServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
    @SerialName("Workers") val workers: List<WorkerSummaryDto> = emptyList(),
)

@Serializable
data class WorkerSummaryDto(
    @SerialName("UserName") val userName: String = "",
    @SerialName("DisplayName") val displayName: String = "",
    @SerialName("IsSupervisor") val isSupervisor: Boolean = false,
)
