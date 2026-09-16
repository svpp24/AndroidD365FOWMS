package com.d365fo.mobileops.data.remote.api

import com.d365fo.mobileops.data.remote.dto.BatchServiceRequest
import com.d365fo.mobileops.data.remote.dto.BatchServiceResponse
import com.d365fo.mobileops.data.remote.dto.ChangePasswordServiceResponse
import com.d365fo.mobileops.data.remote.dto.ChangePasswordServiceRequest
import com.d365fo.mobileops.data.remote.dto.LineServiceRequest
import com.d365fo.mobileops.data.remote.dto.LineServiceResponse
import com.d365fo.mobileops.data.remote.dto.LocationServiceRequest
import com.d365fo.mobileops.data.remote.dto.LocationServiceResponse
import com.d365fo.mobileops.data.remote.dto.OnHandServiceRequest
import com.d365fo.mobileops.data.remote.dto.OnHandServiceResponse
import com.d365fo.mobileops.data.remote.dto.PostQueueServiceRequest
import com.d365fo.mobileops.data.remote.dto.PostQueueServiceResponse
import com.d365fo.mobileops.data.remote.dto.PostServiceRequest
import com.d365fo.mobileops.data.remote.dto.PostServiceResponse
import com.d365fo.mobileops.data.remote.dto.PurchOrderServiceRequest
import com.d365fo.mobileops.data.remote.dto.PurchOrderServiceResponse
import com.d365fo.mobileops.data.remote.dto.SerialServiceRequest
import com.d365fo.mobileops.data.remote.dto.SignInServiceRequest
import com.d365fo.mobileops.data.remote.dto.SignInServiceResponse
import com.d365fo.mobileops.data.remote.dto.TransferLineServiceRequest
import com.d365fo.mobileops.data.remote.dto.TransferServiceRequest
import com.d365fo.mobileops.data.remote.dto.TransferServiceResponse
import com.d365fo.mobileops.data.remote.dto.SerialServiceResponse
import com.d365fo.mobileops.data.remote.interceptor.IdempotentRead
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Tag

/**
 * The HSMobileOps AOS service — the read path that replaces OData for scan-driven work.
 *
 * Every method is a POST because that is how a D365FO custom service takes parameters, and every
 * one of them is nonetheless a **read**. That distinction is carried by the [IdempotentRead] tag:
 * without it `RetryAfterInterceptor` would treat these as unsafe to repeat and the step flow would
 * have no recovery from a `429` at all — which is exactly when recovery matters, because a 429
 * only happens when the environment is already under load.
 *
 * See `aos/README.md` for the service, its load characteristics, and the throttling priority that
 * has to be configured before go-live.
 */
interface MobileOpsServiceApi {

    /**
     * One purchase order with its receivable lines, their remaining quantities, and each item's
     * dimension requirements.
     *
     * Answers the PO step and the item step together. Over OData this was four queries.
     */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsReceivingSvc/getPurchaseOrder")
    suspend fun getPurchaseOrder(
        @Body body: PurchOrderServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<PurchOrderServiceResponse>

    /**
     * The outstanding lines for one item on one order - the large-order path.
     *
     * Called only when getPurchaseOrder reported LinesIncluded = false. Cost tracks the number of
     * lines carrying this item, not the size of the order.
     */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsReceivingSvc/getLinesForItem")
    suspend fun getLinesForItem(
        @Body body: LineServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<LineServiceResponse>

    // -- Transfer orders ---------------------------------------------------------------------
    //
    // Both take a Mode of "Ship" or "Receive". One order is worked from two ends and the answers
    // differ in every way that matters, so the direction travels with the request rather than
    // being inferred from which endpoint was called.

    @POST("HSMobileOpsServiceGroup/HSMobileOpsTransferSvc/getTransferOrder")
    suspend fun getTransferOrder(
        @Body body: TransferServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<TransferServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsTransferSvc/getLinesForItem")
    suspend fun getTransferLinesForItem(
        @Body body: TransferLineServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<TransferServiceResponse>

    // -- Worker identity ---------------------------------------------------------------------
    //
    // These are the one group that is NOT tagged IdempotentRead. A sign-in counts a failed
    // attempt and can lock an account, and an administrative call changes a password - replaying
    // either on a 429 would lock a worker out of their own shift for a network hiccup.

    @POST("HSMobileOpsServiceGroup/HSMobileOpsUserSvc/signIn")
    suspend fun signIn(@Body body: SignInServiceRequest): Response<SignInServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsUserSvc/changePassword")
    suspend fun changePassword(
        @Body body: ChangePasswordServiceRequest,
    ): Response<ChangePasswordServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsInquirySvc/validateLocation")
    suspend fun validateLocation(
        @Body body: LocationServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<LocationServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsInquirySvc/validateSerial")
    suspend fun validateSerial(
        @Body body: SerialServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<SerialServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsInquirySvc/validateBatch")
    suspend fun validateBatch(
        @Body body: BatchServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<BatchServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsInquirySvc/getOnHand")
    suspend fun getOnHand(
        @Body body: OnHandServiceRequest,
        @Tag tag: IdempotentRead = IdempotentRead,
    ): Response<OnHandServiceResponse>

    // -- Posting ------------------------------------------------------------------------------
    //
    // Deliberately NOT tagged IdempotentRead. These are writes, and RetryAfterInterceptor must
    // never retry one on its own: the sync queue is what owns the idempotency key and the retry
    // schedule for a posting, in PostingDispatcher. A 429 here is handled by the queue leaving
    // the entry for the next worker run, not by replaying the HTTP call in place.

    @POST("HSMobileOpsServiceGroup/HSMobileOpsPostingSvc/postProductReceipt")
    suspend fun postProductReceipt(@Body body: PostServiceRequest): Response<PostServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsPostingSvc/postTransferShipment")
    suspend fun postTransferShipment(@Body body: PostServiceRequest): Response<PostServiceResponse>

    @POST("HSMobileOpsServiceGroup/HSMobileOpsPostingSvc/postTransferReceipt")
    suspend fun postTransferReceipt(@Body body: PostServiceRequest): Response<PostServiceResponse>

    /**
     * Works every Pending row in HSMobileOpsPostQueue for this company and reports what each
     * became. Operator-triggered from the Activity screen - see the class doc on
     * HSMobileOpsPostQueue for why there is no automatic timer calling this instead.
     */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsPostingSvc/processPending")
    suspend fun processPending(
        @Body body: PostQueueServiceRequest,
    ): Response<PostQueueServiceResponse>

    /** The most recent 50 HSMobileOpsPostQueue rows for this company, whatever their status. */
    @POST("HSMobileOpsServiceGroup/HSMobileOpsPostingSvc/getQueueStatus")
    suspend fun getQueueStatus(
        @Body body: PostQueueServiceRequest,
    ): Response<PostQueueServiceResponse>
}
