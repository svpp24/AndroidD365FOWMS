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
