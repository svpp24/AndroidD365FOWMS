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
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * DEBUG BUILDS ONLY (src/debug). A load generator: simulates N handhelds making the app's read
 * calls - PO lookup, on-hand, journal list, user warehouses - against the configured D365FO, at
 * a steady rate, and logs latency and status (429 = throttled) under the tag LOADTEST.
 *
 * Runs on the device so it can use the app's own token: the client secret never leaves the
 * phone. Reads only - nothing in D365FO changes. Every simulated device shares this phone's one
 * app registration, which is the worst case the registration pool exists to avoid.
 *
 *   adb shell am broadcast -n com.d365fo.mobileops.debug/com.d365fo.mobileops.debugtools.LoadTestReceiver \
 *       --ei devices 50 --ei seconds 120 --ei perMinute 4 --ei rampSeconds 20 --es warehouse AAC
 *
 * Protected by android.permission.DUMP in the manifest - adb shell holds it, ordinary apps don't.
 */
@AndroidEntryPoint
class LoadTestReceiver : BroadcastReceiver() {

    @Inject lateinit var tokenProvider: TokenProvider
    @Inject lateinit var connectionSettings: ConnectionSettings
    @Inject lateinit var companyProvider: CompanyProvider

    override fun onReceive(context: Context, intent: Intent) {
        val devices = intent.getIntExtra("devices", 20).coerceIn(1, 400)
        val seconds = intent.getIntExtra("seconds", 120).coerceIn(10, 1800)
        val perMinute = intent.getIntExtra("perMinute", 4).coerceIn(1, 120)
        val ramp = intent.getIntExtra("rampSeconds", 20).coerceIn(0, 600)
        val warehouse = intent.getStringExtra("warehouse") ?: "AAC"
        val user = intent.getStringExtra("user") ?: "002"
        val purchIds = (intent.getStringExtra("purchIds") ?: "BSP-PO-000161686,BSP-PO-000161680,BSP-PO-000161687").split(',')
        val items = (intent.getStringExtra("items") ?: "HSV001,HST002,Dm001,BSR-47274-0").split(',')
        // Not goAsync(): a broadcast still held open after about a minute is reported as an ANR
        // (seen live on a 6-minute run). The app is in the foreground while testing, so the
        // process stays alive for the thread without it.
        thread(name = "loadtest") {
            try {
                run(devices, seconds, perMinute, ramp, warehouse, user, purchIds, items)
            } catch (t: Throwable) {
                Log.w(TAG, "load test failed: ${t.message}")
            }
        }
    }

    private fun run(
        devices: Int, seconds: Int, perMinute: Int, ramp: Int,
        warehouse: String, user: String, purchIds: List<String>, items: List<String>,
    ) {
        val base = connectionSettings.tenant.value.servicesRoot + "HSMobileOpsServiceGroup/"
        val company = companyProvider()?.uppercase().orEmpty().ifBlank { "BSP" }
        fun token(): String = when (val t = runBlocking { tokenProvider.acquireSilent() }) {
            is NetworkResult.Success -> t.data.value
            else -> error("no token - is the device connected? ${(t as? NetworkResult.Error)?.failure?.message}")
        }
        token()

        val ops = listOf(
            Op("getPurchaseOrder", "HSMobileOpsReceivingSvc/getPurchaseOrder", 5) {
                """{"_request":{"DataAreaId":"$company","PurchId":"${purchIds.random()}","WarehouseId":"$warehouse"}}"""
            },
            Op("getOnHand", "HSMobileOpsInquirySvc/getOnHand", 3) {
                """{"_request":{"DataAreaId":"$company","ItemId":"${items.random()}","WarehouseId":"$warehouse"}}"""
            },
            Op("listOpenJournals", "HSMobileOpsCountingSvc/listOpenJournals", 1) {
                """{"_request":{"DataAreaId":"$company","WarehouseId":"$warehouse","UserId":"$user"}}"""
            },
            Op("getUserWarehouses", "HSMobileOpsUserWarehouseSvc/getUserWarehouses", 1) {
                """{"_request":{"DataAreaId":"$company","UserId":"$user"}}"""
            },
        )
        val weighted = ops.flatMap { op -> List(op.weight) { op } }

        val client = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 512; maxRequestsPerHost = 512 })
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val json = "application/json".toMediaType()
        val latencies = ConcurrentHashMap<String, CopyOnWriteArrayList<Long>>()
        val statuses = ConcurrentHashMap<String, AtomicInteger>()
        val started = System.currentTimeMillis()
        val deadline = started + ramp * 1000L + seconds * 1000L
        val intervalMs = 60_000.0 / perMinute

        Log.w(TAG, "start: $devices devices x $perMinute/min for ${seconds}s after a ${ramp}s ramp, against $base")
        val pool = Executors.newFixedThreadPool(devices)
        repeat(devices) {
            pool.execute {
                if (ramp > 0) Thread.sleep(Random.nextLong(ramp * 1000L))
                while (System.currentTimeMillis() < deadline) {
                    val op = weighted.random()
                    val request = Request.Builder()
                        .url(base + op.path)
                        .header("Authorization", "Bearer " + token())
                        .header("X-Company", company)
                        .post(op.body().toRequestBody(json))
                        .build()
                    val t0 = System.nanoTime()
                    val status = try {
                        client.newCall(request).execute().use { it.body?.bytes(); it.code.toString() }
                    } catch (e: Exception) {
                        e.javaClass.simpleName
                    }
                    latencies.getOrPut(op.name) { CopyOnWriteArrayList() }.add((System.nanoTime() - t0) / 1_000_000)
                    statuses.getOrPut(status) { AtomicInteger() }.incrementAndGet()
                    // Exponential think time around the target rate, like independent workers.
                    val wait = (-Math.log(1 - Random.nextDouble()) * intervalMs).toLong()
                    Thread.sleep(wait.coerceAtMost((deadline - System.currentTimeMillis()).coerceAtLeast(0)))
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(seconds + ramp + 120L, TimeUnit.SECONDS)

        val elapsed = (System.currentTimeMillis() - started) / 1000.0
        val total = statuses.values.sumOf { it.get() }
        Log.w(TAG, "done: $total requests in ${"%.0f".format(elapsed)}s = ${"%.1f".format(total / elapsed)} req/s " +
            "(${"%.0f".format(total / elapsed * 300)} per 5 min)")
        latencies.toSortedMap().forEach { (name, values) ->
            val v = values.sorted()
            fun p(q: Double) = v[((v.size - 1) * q).toInt()]
            Log.w(TAG, "$name n=${v.size} p50=${p(0.5)} p90=${p(0.9)} p99=${p(0.99)} max=${v.last()} ms")
        }
        Log.w(TAG, "status: " + statuses.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value.get()}" })
    }

    private class Op(val name: String, val path: String, val weight: Int, val body: () -> String)

    private companion object {
        const val TAG = "LOADTEST"
    }
}
