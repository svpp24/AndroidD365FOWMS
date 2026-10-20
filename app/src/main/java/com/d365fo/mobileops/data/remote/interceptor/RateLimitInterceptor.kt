package com.d365fo.mobileops.data.remote.interceptor

import com.d365fo.mobileops.util.Constants
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.Semaphore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caps concurrent calls into D365FO.
 *
 * D365FO applies its own priority-based throttling per environment and returns 429 with
 * `Retry-After` once an app exceeds it. Being throttled is expensive — the request has
 * already cost a round trip — so the client keeps itself under the limit rather than
 * discovering it. Excess calls queue on the semaphore instead of failing.
 */
@Singleton
class RateLimitInterceptor @Inject constructor() : Interceptor {

    private val permits = Semaphore(Constants.MAX_CONCURRENT_REQUESTS, true)

    override fun intercept(chain: Interceptor.Chain): Response {
        permits.acquire()
        return try {
            chain.proceed(chain.request())
        } finally {
            permits.release()
        }
    }
}

/**
 * Marks a request as a read that is safe to repeat.
 *
 * The AOS service reads are POSTs — a custom service takes its parameters in a body, so there is
 * no GET form of them. Method alone therefore cannot tell a repeatable read from a posting, and
 * treating every POST as unsafe would leave the whole step flow with no throttle recovery at all.
 * Tagging the request says so explicitly, at the one place that knows: the API declaration.
 *
 * A tag rather than a header, so nothing about this leaves the device.
 */
object IdempotentRead

/**
 * Honours `Retry-After` on 429 and 503 for a bounded number of attempts.
 *
 * Only idempotent reads are retried here: GETs, and POSTs explicitly tagged [IdempotentRead].
 * Postings are never retried at the transport layer — the sync queue owns their retry policy,
 * because it also owns the idempotency key that makes a retry safe. A product receipt replayed by
 * an interceptor that does not know about that key is a receipt posted twice.
 */
@Singleton
class RetryAfterInterceptor @Inject constructor(
    private val router: com.d365fo.mobileops.data.security.CredentialRouter,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val repeatable = request.method == "GET" ||
            request.tag(IdempotentRead::class.java) != null
        if (!repeatable) {
            // A posting is never retried here (the sync queue owns that), but a "too many
            // requests" answer still counts, and still rests this device's app registration.
            return chain.proceed(request).also { response ->
                if (response.code == TOO_MANY_REQUESTS) {
                    router.onThrottledReply()
                    router.onStillThrottled(retryAfterMs(response) ?: BASE_WAIT_MS)
                }
            }
        }

        var attempt = 0
        var response = chain.proceed(request)

        while (response.code in THROTTLED_CODES && attempt < MAX_ATTEMPTS) {
            if (response.code == TOO_MANY_REQUESTS) router.onThrottledReply()
            val waitMs = retryAfterMs(response) ?: (BASE_WAIT_MS shl attempt)

            response.close()
            try {
                Thread.sleep(waitMs)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return chain.proceed(request)
            }
            attempt++
            response = chain.proceed(request)
        }
        // Still throttled after every retry D365FO's own Retry-After allowed: this registration's
        // allowance is spent for now, so the next token comes from another one (CredentialRouter).
        if (response.code == TOO_MANY_REQUESTS) {
            router.onThrottledReply()
            router.onStillThrottled(retryAfterMs(response) ?: MAX_WAIT_MS)
        }
        return response
    }

    private fun retryAfterMs(response: Response): Long? =
        response.header("Retry-After")?.toLongOrNull()?.times(1_000)?.coerceAtMost(MAX_WAIT_MS)

    private companion object {
        const val TOO_MANY_REQUESTS = 429
        val THROTTLED_CODES = setOf(429, 503)
        const val MAX_ATTEMPTS = 3
        const val BASE_WAIT_MS = 1_000L
        const val MAX_WAIT_MS = 30_000L
    }
}
