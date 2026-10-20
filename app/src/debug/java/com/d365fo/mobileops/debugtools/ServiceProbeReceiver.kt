package com.d365fo.mobileops.debugtools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.d365fo.mobileops.data.security.ConnectionSettings
import com.d365fo.mobileops.data.security.TokenProvider
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * DEBUG BUILDS ONLY (src/debug). POSTs one raw JSON body to a custom-service operation with the
 * app's own token and logs the status and answer under the tag SVCPROBE - for checking how the
 * AOS binds a request shape from adb, without the client secret ever leaving the phone. The body
 * goes base64-encoded to survive adb shell quoting. Unlike ODataProbeReceiver this can change
 * data: send only bodies that cannot (unknown ids, test journals).
 *
 *   adb shell am broadcast -n com.d365fo.mobileops.debug/com.d365fo.mobileops.debugtools.ServiceProbeReceiver \
 *       --es op "HSMobileOpsUnitSvc/getItemUnits" --es body64 "eyJfcmVxdWVzdCI6e319"
 *
 * Protected by android.permission.DUMP in the manifest, like LoadTestReceiver.
 */
@AndroidEntryPoint
class ServiceProbeReceiver : BroadcastReceiver() {

    @Inject lateinit var tokenProvider: TokenProvider
    @Inject lateinit var connectionSettings: ConnectionSettings

    override fun onReceive(context: Context, intent: Intent) {
        val op = intent.getStringExtra("op") ?: return
        val body = intent.getStringExtra("body64")?.let { String(Base64.decode(it, Base64.DEFAULT)) } ?: return
        thread(name = "service-probe") {
            try {
                val token = when (val t = runBlocking { tokenProvider.acquireSilent() }) {
                    is NetworkResult.Success -> t.data.value
                    else -> error("no token: ${(t as? NetworkResult.Error)?.failure?.message}")
                }
                val url = connectionSettings.tenant.value.resourceUrl.trimEnd('/') +
                    "/api/services/HSMobileOpsServiceGroup/" + op
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                    val answer = response.body?.string().orEmpty()
                    Log.i(TAG, "$op -> ${response.code} ${answer.length} chars")
                    answer.chunked(3_000).take(10).forEach { Log.i(TAG, it) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "probe failed: ${t.message}")
            }
        }
    }

    private companion object {
        const val TAG = "SVCPROBE"
    }
}
