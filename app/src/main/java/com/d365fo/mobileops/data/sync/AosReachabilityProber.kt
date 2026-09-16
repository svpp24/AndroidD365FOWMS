package com.d365fo.mobileops.data.sync

import com.d365fo.mobileops.data.remote.interceptor.AosReachabilityInterceptor
import com.d365fo.mobileops.data.security.ConnectionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fires an unauthenticated probe at the configured AOS host to answer "is it up" without
 * waiting for an operator to make a real call first.
 *
 * [AosReachabilityMonitor] is otherwise purely reactive - see its own class doc - which reads
 * fine while the app is in active use but leaves a real gap right at the moment it matters
 * most: an app opened cold over a tenant that is actually down shows nothing wrong until the
 * operator's first scan finds out the hard way. [SyncStatusProvider] calls [probe] once at
 * startup and again every time device internet comes back, to close that gap.
 *
 * Deliberately bare - no [AuthInterceptor][com.d365fo.mobileops.data.remote.interceptor.AuthInterceptor],
 * no company header, no retry - because the question here is narrower than any real API call:
 * not "can I sign in and read data" but "does anything answer at this address at all".
 * Attempting a token acquisition first would fold in AAD reachability and credential state,
 * which is a different failure with a different fix, and AuthInterceptor fails fast (throws)
 * when no token is available rather than sending the request unauthenticated - exactly wrong
 * for a check that has to work before anyone has signed in yet. [AosReachabilityInterceptor]
 * is attached even so, because the outcome answers exactly the question
 * [AosReachabilityMonitor] tracks, and a probe is not a special case of that: any HTTP
 * response at all - even the 401 an unauthenticated request predictably gets - proves the host
 * answered.
 */
@Singleton
class AosReachabilityProber @Inject constructor(
    private val connectionSettings: ConnectionSettings,
    certificatePinner: CertificatePinner,
    reachabilityInterceptor: AosReachabilityInterceptor,
) {
    // Short and separate from the shared client's own timeouts (15s/60s/60s) - warehouse
    // Wi-Fi roaming tolerance is the wrong budget for "is anyone home", which should read as
    // unreachable and get out of the way quickly rather than hold this up for a minute.
    private val client = OkHttpClient.Builder()
        .certificatePinner(certificatePinner)
        .addInterceptor(reachabilityInterceptor)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Fire-and-forget: [AosReachabilityInterceptor] records the outcome on the client's own
     * dispatcher thread, so nothing here needs the response - a failed probe (including one
     * that never got a URL to call because the device is unconfigured) is silently swallowed
     * rather than surfaced as an app error.
     */
    suspend fun probe() {
        val resourceUrl = connectionSettings.tenant.value.resourceUrl
        if (resourceUrl.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(resourceUrl).build()).execute().close()
            }
        }
    }
}
