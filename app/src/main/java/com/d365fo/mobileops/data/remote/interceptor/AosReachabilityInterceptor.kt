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
        // Fail fast while the tenant is known to be down: a read would otherwise sit out the
        // connect or read timeout (15 s to a minute) with the worker watching a spinner, only to
        // end in "request timed out" - and OfflineReadCacheInterceptor answers it from the saved
        // copy at once instead. Recovery is noticed by SyncStatusProvider's background probe
        // (AosReachabilityProber, every few seconds while down), never by a worker's lookup.
        // Postings are never short-circuited here - the sync queue owns their retries.
        val request = chain.request()
        val isRead = request.method == "GET" || request.tag(IdempotentRead::class.java) != null
        val probe = request.tag(ReachabilityProbe::class.java) != null
        if (isRead && !probe && !monitor.reachable.value) throw AosUnreachableException()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            // No connection at all marks D365FO down. A call that connected and then outlived
            // its read timeout reached a slow AOS, not a missing one: a posting (creating and
            // confirming an order on a cold AOS can take a minute) or the probe (10 s, while
            // the AOS warms up after a restart) says nothing about reachability; a worker's
            // lookup that hangs that long still counts, so the next ones use the saved copy.
            // A call the app cancelled itself says nothing either.
            val connected = e is java.net.SocketTimeoutException && e.message?.startsWith("failed to connect") != true
            if (!chain.call().isCanceled() && (!connected || (isRead && !probe))) monitor.markUnreachable()
            throw e
        }
        monitor.markReachable()
        return response
    }
}

/** Tags [AosReachabilityProber]'s request: the one call that still goes out while D365FO is marked down. */
object ReachabilityProbe

/** Thrown instead of waiting out a timeout while D365FO is already known to be unreachable. */
class AosUnreachableException : java.io.IOException("D365FO unreachable")
