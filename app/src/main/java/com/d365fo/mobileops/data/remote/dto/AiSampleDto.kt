package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** HSMobileOpsAiSampleSvc/uploadSample - one AI count training sample. */
@Serializable
data class AiSampleServiceRequest(@SerialName("_request") val request: AiSampleRequestBody)

@Serializable
data class AiSampleRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("SampleId") val sampleId: String,
    @SerialName("ItemId") val itemId: String,
    @SerialName("Quantity") val quantity: Int,
    @SerialName("Corrections") val corrections: Int,
    @SerialName("Annotation") val annotation: String,
    @SerialName("ImageBase64") val imageBase64: String,
    @SerialName("DeviceId") val deviceId: String,
    @SerialName("UserName") val userName: String,
    @SerialName("WarehouseId") val warehouseId: String,
)

@Serializable
data class AiSampleServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String = "",
    @SerialName("Stored") val stored: Boolean = false,
)
