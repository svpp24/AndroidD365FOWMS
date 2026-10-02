package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// -- HSMobileOpsUserWarehouseSvc - a worker's assigned warehouses with names, for the switcher.
// Same rows (WarehouseAssignmentDto) signIn returns.

@Serializable
data class UserWarehousesServiceRequest(@SerialName("_request") val request: UserWarehousesRequestBody)

@Serializable
data class UserWarehousesRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("UserId") val userId: String,
)

@Serializable
data class UserWarehousesServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Warehouses") val warehouses: List<WarehouseAssignmentDto> = emptyList(),
)
