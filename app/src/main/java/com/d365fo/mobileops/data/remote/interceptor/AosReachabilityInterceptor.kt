package com.d365fo.mobileops.data.remote.interceptor

import com.d365fo.mobileops.data.sync.AosReachabilityMonitor
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feeds [AosReachabilityMonitor] from what actually happens to each request.
 *
 * Any HTTP response - even a 4xx or 5xx - proves the AOS host answered: an authentication
 * failure, a throttled 429, a rejected posting are D365FO having an opinion, not D365FO being
 * unreachable. Only a transport-level failure - no HTTP answer came back at all (DNS, a
 * refused or reset connection, a timeout) - means the host itself did not respond, and that is
 * exactly the shape of failure a dev AOS environment produced when it went down mid-session:
 * `curl: (28) Failed to connect... Could not connect to server`, with general internet
 * unaffected the whole time.
 *
 * Added first, before the other AOS interceptors (see NetworkModule) - OkHttp runs the first-
 * added interceptor outermost, so this observes the one final outcome a caller actually sees
 * after [RetryAfterInterceptor]'s own internal retries, rather than counting each retried
 * attempt as a separate observation.
 */
@Singleton
class AosReachabilityInterceptor @Inject constructor(
    private val monitor: AosReachabilityMonitor,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            monitor.markUnreachable()
            throw e
        }
        monitor.markReachable()
        return response
    }
}
