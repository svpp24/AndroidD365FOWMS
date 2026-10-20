package com.d365fo.mobileops.data.remote.api

import com.d365fo.mobileops.data.remote.dto.SessionServiceRequest
import com.d365fo.mobileops.data.remote.dto.SessionServiceResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/** `HSMobileOpsSessionSvc` - writes (each records which device is the worker's active one). */
interface SessionServiceApi {

    @POST("HSMobileOpsServiceGroup/HSMobileOpsSessionSvc/startSession")
    suspend fun startSession(@Body body: SessionServiceRequest): Response<SessionServiceResponse>

    /** Changes nothing: what the device that last held the session still has unfinished. */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsSessionSvc/peekSession")
    suspend fun peekSession(@Body body: SessionServiceRequest): Response<SessionServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsSessionSvc/checkSession")
    suspend fun checkSession(@Body body: SessionServiceRequest): Response<SessionServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsSessionSvc/endSession")
    suspend fun endSession(@Body body: SessionServiceRequest): Response<SessionServiceResponse>
}
