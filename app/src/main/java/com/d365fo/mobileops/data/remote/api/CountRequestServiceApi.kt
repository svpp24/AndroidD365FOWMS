package com.d365fo.mobileops.data.remote.api

import com.d365fo.mobileops.data.remote.dto.CountRequestServiceRequest
import com.d365fo.mobileops.data.remote.dto.CountRequestServiceResponse
import com.d365fo.mobileops.data.remote.interceptor.IdempotentRead
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Tag

/** `HSMobileOpsCountRequestSvc` - the handheld's side: the warehouse's counts, a count's items, its results. */
interface CountRequestServiceApi {

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountRequestSvc/listCounts")
    suspend fun listCounts(
        @Body body: CountRequestServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<CountRequestServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountRequestSvc/getCountItems")
    suspend fun getCountItems(
        @Body body: CountRequestServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<CountRequestServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountRequestSvc/getCountResults")
    suspend fun getCountResults(
        @Body body: CountRequestServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<CountRequestServiceResponse>
}
