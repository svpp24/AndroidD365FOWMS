package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.data.remote.interceptor.AosReachabilityInterceptor
import com.d365fo.mobileops.data.remote.interceptor.AosUnreachableException
import com.d365fo.mobileops.data.remote.interceptor.IdempotentRead
import com.d365fo.mobileops.data.remote.interceptor.ReachabilityProbe
import com.d365fo.mobileops.data.sync.AosReachabilityMonitor
import com.google.common.truth.Truth.assertThat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Lookups while D365FO is down fail at once, instead of sitting out a timeout. */
class AosReachabilityTest {

    private val monitor = AosReachabilityMonitor()
    private val server = MockWebServer().apply { start() }
    private val client = OkHttpClient.Builder()
        .addInterceptor(AosReachabilityInterceptor(monitor))
        .readTimeout(300, TimeUnit.MILLISECONDS)
        .build()

    @AfterEach
    fun stop() = runCatching { server.shutdown() }.let { }

    private fun read(probe: Boolean = false) = client.newCall(
        Request.Builder().url(server.url("/api/services/x/read"))
            .post("{}".toRequestBody("application/json".toMediaType()))
            .tag(IdempotentRead::class.java, IdempotentRead)
            .apply { if (probe) tag(ReachabilityProbe::class.java, ReachabilityProbe) }
            .build(),
    ).execute()

    private fun post() = client.newCall(
        Request.Builder().url(server.url("/api/services/x/create"))
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build(),
    ).execute()

    @Test
    fun `a lookup while D365FO is marked down fails at once, without a request`() {
        monitor.markUnreachable()

        assertThrows<AosUnreachableException> { read() }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `the reachability probe still goes out while down, and an answer brings D365FO back`() {
        monitor.markUnreachable()
        server.enqueue(MockResponse().setResponseCode(401))

        read(probe = true).close()

        assertThat(server.requestCount).isEqualTo(1)
        assertThat(monitor.reachable.value).isTrue()
    }

    @Test
    fun `a slow posting is not D365FO being down`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertThrows<SocketTimeoutException> { post() }
        assertThat(monitor.reachable.value).isTrue()
    }

    @Test
    fun `a lookup that times out marks D365FO down`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertThrows<SocketTimeoutException> { read() }
        assertThat(monitor.reachable.value).isFalse()
    }

    @Test
    fun `a probe that connects but answers slowly leaves D365FO up`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertThrows<SocketTimeoutException> { read(probe = true) }
        assertThat(monitor.reachable.value).isTrue()
    }

    @Test
    fun `nothing listening marks D365FO down`() {
        server.shutdown()

        assertThrows<java.io.IOException> { post() }
        assertThat(monitor.reachable.value).isFalse()
    }
}
