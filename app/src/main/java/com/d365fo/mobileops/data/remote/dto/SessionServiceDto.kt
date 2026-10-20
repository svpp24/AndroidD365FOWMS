package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** HSMobileOpsSessionSvc - one active device per worker (see HSMobileOpsSessionService). */
@Serializable
data class SessionServiceRequest(@SerialName("_request") val request: SessionRequestBody)

@Serializable
data class SessionRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("UserName") val userName: String,
    @SerialName("DeviceId") val deviceId: String,
    /** This device's unfinished work for the worker - reported with every call. */
    @SerialName("PendingJobs") val pendingJobs: Int = 0,
    @SerialName("PendingCounts") val pendingCounts: Int = 0,
)

/** [signedInElsewhere]: another device has signed in as this worker since - sign out here. */
@Serializable
data class SessionServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
    @SerialName("SignedInElsewhere") val signedInElsewhere: Boolean = false,
    /** peekSession: the worker's unfinished work on the device that last held the session. */
    @SerialName("OtherDevicePendingJobs") val otherDevicePendingJobs: Int = 0,
    @SerialName("OtherDevicePendingCounts") val otherDevicePendingCounts: Int = 0,
    @SerialName("OtherDeviceReportedUtc") val otherDeviceReportedUtc: String? = null,
)
