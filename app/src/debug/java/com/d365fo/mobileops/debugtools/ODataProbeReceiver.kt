package com.d365fo.mobileops.debugtools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.ConnectionSettings
import com.d365fo.mobileops.data.security.TokenProvider
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * DEBUG BUILDS ONLY (src/debug). Runs one OData read against the configured D365FO with the
 * app's own token and logs the answer under the tag ODATA - for finding test data from adb
 * without the client secret ever leaving the phone. GET only: nothing in D365FO changes.
 *
 *   adb shell am broadcast -n com.d365fo.mobileops.debug/com.d365fo.mobileops.debugtools.ODataProbeReceiver \
 *       --es path "PurchaseOrderLinesV2?\$top=5&\$select=ItemNumber"
 *
 * Protected by android.permission.DUMP in the manifest, like LoadTestReceiver.
 */
@AndroidEntryPoint
class ODataProbeReceiver : BroadcastReceiver() {

    @Inject lateinit var tokenProvider: TokenProvider
    @Inject lateinit var connectionSettings: ConnectionSettings
    @Inject lateinit var companyProvider: CompanyProvider

    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path") ?: return
        thread(name = "odata-probe") {
            try {
                val token = when (val t = runBlocking { tokenProvider.acquireSilent() }) {
                    is NetworkResult.Success -> t.data.value
                    else -> error("no token: ${(t as? NetworkResult.Error)?.failure?.message}")
                }
                val url = connectionSettings.tenant.value.resourceUrl.trimEnd('/') + "/data/" + path
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .apply { companyProvider()?.let { header("X-Company", it) } }
                    .get()
                    .build()
                OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    Log.i(TAG, "${response.code} ${body.length} chars")
                    body.chunked(3_000).take(10).forEach { Log.i(TAG, it) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "probe failed: ${t.message}")
            }
        }
    }

    private companion object {
        const val TAG = "ODATA"
    }
}
