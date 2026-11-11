package com.d365fo.mobileops.data.demo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Demo mode (user, 11 Nov 2026): the app runs against [DemoBackend] - fictional data held in
 * memory on the device - instead of a company's D365FO, so Google Play reviewers and prospects can
 * use every screen with no connection set up and no password. Nothing leaves the device while it
 * is on: [DemoInterceptor] answers every request before it reaches the network.
 *
 * A plain object (not injected) because the OkHttp interceptors read it on every call and must not
 * depend on Hilt; [init] loads the saved switch at app start, before any client is built.
 */
object DemoMode {
    private const val PREFS = "demo_mode"
    private const val KEY_ACTIVE = "active"

    /** The saved connection profile the demo runs under; removed when the demo ends. */
    const val CONNECTION_LABEL = "Demo"

    /** Never resolved: [DemoInterceptor] answers before any lookup. `.invalid` is reserved (RFC 2606). */
    const val CONNECTION_URL = "https://demo.inventoryhub.invalid"

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()
    val isActive: Boolean get() = _active.value

    fun init(context: Context) {
        _active.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, false)
    }

    internal fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, on).commit()
        _active.value = on
    }
}

/**
 * While [DemoMode] is on, answers every request from [DemoBackend] instead of the network: D365FO
 * services and OData, the Microsoft sign-in token, the reachability probe and blob transfers. Off,
 * it only passes the request on. Sits inside the reachability interceptor, so the app counts D365FO
 * as reachable, and before the auth interceptor, so no token is fetched for a demo call.
 */
object DemoInterceptor : Interceptor {
    private val jsonType = "application/json".toMediaType()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!DemoMode.isActive) return chain.proceed(request)
        val url = request.url
        val reply = runCatching {
            when {
                url.host.endsWith("login.microsoftonline.com") ->
                    DemoBackend.Reply(200, """{"access_token":"demo","token_type":"Bearer","expires_in":3600,"ext_expires_in":3600}""")
                url.encodedPath.startsWith("/api/") || url.encodedPath.startsWith("/data/") -> {
                    val body = request.body?.let { b -> okio.Buffer().also { b.writeTo(it) }.readUtf8() }
                    DemoBackend.respond(request.method, url.encodedPath, url.encodedQuery, body)
                }
                else -> DemoBackend.Reply(200, "{}")
            }
        }.getOrElse { DemoBackend.Reply(200, "{}") }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(reply.code)
            .message(if (reply.code in 200..299) "OK" else "Error")
            .body(reply.json.toResponseBody(jsonType))
            .build()
    }
}
