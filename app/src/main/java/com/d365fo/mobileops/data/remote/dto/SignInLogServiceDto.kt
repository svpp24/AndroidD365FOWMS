package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** HSMobileOpsSignInLogSvc/logSession - one worker session, sent when it starts and when it ends. */
@Serializable
data class SignInLogServiceRequest(@SerialName("_request") val request: SignInLogRequestBody)

@Serializable
data class SignInLogRequestBody(
    @SerialName("SessionId") val sessionId: String,
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("UserName") val userName: String,
    @SerialName("DeviceId") val deviceId: String = "",
    @SerialName("DeviceName") val deviceName: String = "",
    @SerialName("WarehouseId") val warehouseId: String = "",
    @SerialName("AppVersionName") val appVersionName: String = "",
    @SerialName("SignedInOffline") val signedInOffline: Boolean = false,
    /** Milliseconds since 1 Jan 1970 UTC. */
    @SerialName("SignInMillis") val signInMillis: Long,
    /** 0 while the session is open. */
    @SerialName("SignOutMillis") val signOutMillis: Long = 0,
    @SerialName("EndReason") val endReason: String = "",
)

@Serializable
data class SignInLogServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String = "",
    @SerialName("Message") val message: String = "",
)
