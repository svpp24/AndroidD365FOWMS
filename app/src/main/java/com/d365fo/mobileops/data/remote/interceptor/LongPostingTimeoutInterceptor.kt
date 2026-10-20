package com.d365fo.mobileops.data.remote.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * More time for the calls that create and post documents in one go - a purchase order created
 * and confirmed, a return order created, confirmed and its packing slip posted. On a cold AOS
 * these take over a minute. Cut off at the normal read timeout, the call carries on in D365FO
 * while the sync queue resends it, and the resend meets the first one half-done (it sees the
 * order but not yet its confirmation). These are sent in the background, so nobody waits on
 * the longer timeout.
 */
object LongPostingTimeoutInterceptor : Interceptor {

    private val LONG_OPERATIONS = setOf(
        "HSMobileOpsOrderCreateSvc/createPurchaseOrder",
        "HSMobileOpsOrderCreateSvc/createPurchaseReturn",
        "HSMobileOpsOrderCreateSvc/confirmPurchaseOrder",
        // With Settings > Post immediately these post in the call (ProcessNow), not just stage.
        "HSMobileOpsPostingSvc/postProductReceipt",
        "HSMobileOpsPostingSvc/postTransferShipment",
        "HSMobileOpsPostingSvc/postTransferReceipt",
    )
    private const val LONG_READ_SECONDS = 180

    override fun intercept(chain: Interceptor.Chain): Response {
        val path = chain.request().url.encodedPath
        val long = LONG_OPERATIONS.any { path.endsWith(it) }
        return if (long) chain.withReadTimeout(LONG_READ_SECONDS, TimeUnit.SECONDS).proceed(chain.request())
        else chain.proceed(chain.request())
    }
}
