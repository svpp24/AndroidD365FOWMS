package com.d365fo.mobileops.data.remote.api

import com.d365fo.mobileops.data.remote.dto.CountStageServiceRequest
import com.d365fo.mobileops.data.remote.dto.CountStageServiceResponse
import com.d365fo.mobileops.data.remote.interceptor.IdempotentRead
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Tag

/** `HSMobileOpsCountStageSvc` - the Stock count control center's staging (see CountStagingRepository). */
interface CountStageServiceApi {

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountStageSvc/openStage")
    suspend fun openStage(@Body body: CountStageServiceRequest): Response<CountStageServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountStageSvc/submitLines")
    suspend fun submitLines(@Body body: CountStageServiceRequest): Response<CountStageServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountStageSvc/getStageMode")
    suspend fun getStageMode(
        @Body body: CountStageServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<CountStageServiceResponse>
}
