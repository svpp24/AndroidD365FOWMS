package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** HSMobileOpsFeedbackSvc - one request and one response shape for both operations. */
@Serializable
data class FeedbackServiceRequest(@SerialName("_request") val request: FeedbackRequestBody)

@Serializable
data class FeedbackRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("UserName") val userName: String,
    @SerialName("DeviceId") val deviceId: String = "",
    @SerialName("FeedbackId") val feedbackId: String = "",
    @SerialName("WarehouseId") val warehouseId: String = "",
    @SerialName("AppVersion") val appVersion: String = "",
    @SerialName("DeviceModel") val deviceModel: String = "",
    @SerialName("Screen") val screen: String = "",
    /** Problem, Idea or Question. */
    @SerialName("FeedbackType") val feedbackType: String = "",
    @SerialName("Message") val message: String = "",
    @SerialName("Logs") val logs: String = "",
    /** A JPEG, base64 - blank for none. */
    @SerialName("ScreenshotBase64") val screenshotBase64: String = "",
    @SerialName("Take") val take: Int = 0,
)

@Serializable
data class FeedbackItemDto(
    @SerialName("FeedbackId") val feedbackId: String = "",
    @SerialName("SubmittedUtc") val submittedUtc: String = "",
    @SerialName("Screen") val screen: String = "",
    @SerialName("FeedbackType") val feedbackType: String = "",
    @SerialName("Message") val message: String = "",
    /** New, InProgress, Done or WontDo. */
    @SerialName("Status") val status: String = "",
    @SerialName("Response") val response: String = "",
)

@Serializable
data class FeedbackServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String = "",
    @SerialName("Message") val message: String = "",
    @SerialName("AlreadyCreated") val alreadyCreated: Boolean = false,
    @SerialName("Items") val items: List<FeedbackItemDto> = emptyList(),
)
