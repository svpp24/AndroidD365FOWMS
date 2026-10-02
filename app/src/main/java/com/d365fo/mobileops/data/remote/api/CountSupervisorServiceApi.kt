package com.d365fo.mobileops.data.remote.api

import com.d365fo.mobileops.data.remote.dto.PostJournalServiceRequest
import com.d365fo.mobileops.data.remote.dto.ReviewServiceRequest
import com.d365fo.mobileops.data.remote.dto.SupervisorServiceResponse
import com.d365fo.mobileops.data.remote.dto.VarianceServiceRequest
import com.d365fo.mobileops.data.remote.dto.VarianceServiceResponse
import com.d365fo.mobileops.data.remote.interceptor.IdempotentRead
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Tag

/** `HSMobileOpsCountSupervisorSvc`. getVariance is a read; review and post are writes (never auto-retried). */
interface CountSupervisorServiceApi {

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountSupervisorSvc/getVariance")
    suspend fun getVariance(
        @Body body: VarianceServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<VarianceServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountSupervisorSvc/reviewLines")
    suspend fun reviewLines(@Body body: ReviewServiceRequest): Response<SupervisorServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountSupervisorSvc/postJournal")
    suspend fun postJournal(@Body body: PostJournalServiceRequest): Response<SupervisorServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountSupervisorSvc/getAccuracyReport")
    suspend fun getAccuracyReport(
        @Body body: com.d365fo.mobileops.data.remote.dto.AccuracyServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<com.d365fo.mobileops.data.remote.dto.AccuracyServiceResponse>
}
