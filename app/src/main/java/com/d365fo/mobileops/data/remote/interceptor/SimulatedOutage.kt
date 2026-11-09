package com.d365fo.mobileops.data.remote.interceptor

import com.d365fo.mobileops.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * DEBUG BUILDS ONLY: fails every D365FO call as if the tenant were unreachable, so offline
 * sign-in and offline lookups can be tested without touching the device's network settings.
 * Switched by debugtools/SimulateOutageReceiver over adb. Added to the OkHttp chain only when
 * [BuildConfig.DEBUG], and [enabled] is ignored in any other build.
 */
object SimulatedOutage : Interceptor {
    @Volatile var enabled = false

    override fun intercept(chain: Interceptor.Chain): Response {
        if (BuildConfig.DEBUG && enabled) throw IOException("simulated D365FO outage (debug)")
        return chain.proceed(chain.request())
    }
}

/**
 * DEBUG BUILDS ONLY: answers every Nth D365FO call with 429 and Retry-After, as D365FO's service
 * protection does, so what the app does under throttling can be measured. Innermost, like
 * [SimulatedOutage]: the real call is not made. Switched by debugtools/SimulateThrottleReceiver.
 */
object SimulatedThrottle : Interceptor {
    @Volatile var everyNth = 0
    @Volatile var retryAfterSeconds = 30
    private val calls = java.util.concurrent.atomic.AtomicInteger()
    val throttled = java.util.concurrent.atomic.AtomicInteger()

    override fun intercept(chain: Interceptor.Chain): Response {
        val nth = everyNth
        if (BuildConfig.DEBUG && nth > 0 && calls.incrementAndGet() % nth == 0) {
            throttled.incrementAndGet()
            android.util.Log.i("THROTTLE", "429 for ${chain.request().url.encodedPath}")
            return Response.Builder()
                .request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(429)
                .message("Too Many Requests")
                .header("Retry-After", retryAfterSeconds.toString())
                .body(okhttp3.ResponseBody.create(null, ""))
                .build()
        }
        return chain.proceed(chain.request())
    }
}
