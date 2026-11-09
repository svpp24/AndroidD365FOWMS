package com.d365fo.mobileops.data.remote.api

import com.d365fo.mobileops.data.remote.dto.DeviceWorkServiceRequest
import com.d365fo.mobileops.data.remote.dto.DeviceWorkServiceResponse
import com.d365fo.mobileops.data.remote.interceptor.IdempotentRead
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Tag

/** `HSMobileOpsDeviceWorkSvc` - see WorkBackup and ModuleSwitches. */
interface DeviceWorkServiceApi {

    /** Saves this device's whole set of unsent work. Resending the same set changes nothing. */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsDeviceWorkSvc/syncWork")
    suspend fun syncWork(@Body body: DeviceWorkServiceRequest): Response<DeviceWorkServiceResponse>

    /** Changes nothing: the worker's work saved from other devices. */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsDeviceWorkSvc/listWork")
    suspend fun listWork(
        @Body body: DeviceWorkServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<DeviceWorkServiceResponse>

    /** Changes nothing: the modules switched off for this app version. */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsDeviceWorkSvc/getAppControl")
    suspend fun getAppControl(
        @Body body: DeviceWorkServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<DeviceWorkServiceResponse>
}
