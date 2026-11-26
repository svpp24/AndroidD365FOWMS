package com.d365fo.mobileops.data.remote.api

import com.d365fo.mobileops.data.remote.dto.CountControlServiceRequest
import com.d365fo.mobileops.data.remote.dto.CountControlServiceResponse
import com.d365fo.mobileops.data.remote.interceptor.IdempotentRead
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Tag

/**
 * `HSMobileOpsCountControlSvc`: Count control for the store supervisor. Reads are idempotent;
 * decide, hold and post are writes (never auto-retried). A server without the service answers 404.
 */
interface CountControlServiceApi {

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountControlSvc/getCountControl")
    suspend fun getCountControl(@Body body: CountControlServiceRequest, @Tag tag: IdempotentRead = IdempotentRead): Response<CountControlServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountControlSvc/getCountedTwice")
    suspend fun getCountedTwice(@Body body: CountControlServiceRequest, @Tag tag: IdempotentRead = IdempotentRead): Response<CountControlServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountControlSvc/getCounters")
    suspend fun getCounters(@Body body: CountControlServiceRequest, @Tag tag: IdempotentRead = IdempotentRead): Response<CountControlServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountControlSvc/getBlindCounts")
    suspend fun getBlindCounts(@Body body: CountControlServiceRequest, @Tag tag: IdempotentRead = IdempotentRead): Response<CountControlServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountControlSvc/getVarianceLines")
    suspend fun getVarianceLines(@Body body: CountControlServiceRequest, @Tag tag: IdempotentRead = IdempotentRead): Response<CountControlServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountControlSvc/decideBin")
    suspend fun decideBin(@Body body: CountControlServiceRequest): Response<CountControlServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountControlSvc/holdStoreJournal")
    suspend fun holdStoreJournal(@Body body: CountControlServiceRequest): Response<CountControlServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountControlSvc/postStoreJournal")
    suspend fun postStoreJournal(@Body body: CountControlServiceRequest): Response<CountControlServiceResponse>
}
