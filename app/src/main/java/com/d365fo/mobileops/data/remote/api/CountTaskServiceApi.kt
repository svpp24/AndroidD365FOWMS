package com.d365fo.mobileops.data.remote.api

import com.d365fo.mobileops.data.remote.dto.CountClaimServiceRequest
import com.d365fo.mobileops.data.remote.dto.CountClaimServiceResponse
import com.d365fo.mobileops.data.remote.dto.CountClaimTokenServiceRequest
import com.d365fo.mobileops.data.remote.dto.DirectLinesServiceRequest
import com.d365fo.mobileops.data.remote.dto.DirectLinesServiceResponse
import com.d365fo.mobileops.data.remote.dto.DirectSubmitServiceRequest
import com.d365fo.mobileops.data.remote.dto.DirectSubmitServiceResponse
import com.d365fo.mobileops.data.remote.dto.JournalStateServiceRequest
import com.d365fo.mobileops.data.remote.dto.JournalStateServiceResponse
import com.d365fo.mobileops.data.remote.interceptor.IdempotentRead
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Tag

/**
 * `HSMobileOpsCountTaskSvc` - claims, journal state and DIRECT transfer mode. Reads are tagged
 * [IdempotentRead] so a 429 is retried by the interceptor; claims and submits are writes and
 * are not (their callers own retrying - the submit job via WorkManager's backoff).
 */
interface CountTaskServiceApi {

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountTaskSvc/claimJournal")
    suspend fun claimJournal(@Body body: CountClaimServiceRequest): Response<CountClaimServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountTaskSvc/heartbeatClaim")
    suspend fun heartbeatClaim(@Body body: CountClaimTokenServiceRequest): Response<CountClaimServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountTaskSvc/releaseJournal")
    suspend fun releaseJournal(@Body body: CountClaimTokenServiceRequest): Response<CountClaimServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountTaskSvc/getJournalState")
    suspend fun getJournalState(
        @Body body: JournalStateServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<JournalStateServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountTaskSvc/getJournalLines")
    suspend fun getJournalLines(
        @Body body: DirectLinesServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<DirectLinesServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountTaskSvc/submitCounts")
    suspend fun submitCounts(@Body body: DirectSubmitServiceRequest): Response<DirectSubmitServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsCountTaskSvc/submitExceptions")
    suspend fun submitExceptions(
        @Body body: com.d365fo.mobileops.data.remote.dto.CountExceptionsServiceRequest,
    ): Response<com.d365fo.mobileops.data.remote.dto.CountExceptionsServiceResponse>
}
