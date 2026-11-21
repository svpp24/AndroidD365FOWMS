package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.data.remote.interceptor.RateLimitInterceptor
import com.d365fo.mobileops.data.remote.interceptor.RetryAfterInterceptor
import com.d365fo.mobileops.data.security.CredentialRouter
import com.d365fo.mobileops.util.Constants
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A call sleeping out a Retry-After gives its concurrency slot back (performance plan, phase 4). */
class ThrottleSlotTest {

    private val server = MockWebServer().apply { start() }

    @AfterEach
    fun stop() = runCatching { server.shutdown() }.let { }

    @Test
    fun `the slot is free while a throttled read waits, and held again after`() {
        val slots = RateLimitInterceptor()
        val client = OkHttpClient.Builder()
            .addInterceptor(slots)
            .addInterceptor(RetryAfterInterceptor(CredentialRouter(), slots))
            .build()
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setBody("ok"))

        var body: String? = null
        val caller = thread { body = client.newCall(Request.Builder().url(server.url("/data/Items")).build()).execute().use { it.body!!.string() } }

        assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull()
        Thread.sleep(300)
        // Waiting out Retry-After: every slot free.
        assertThat(slots.freeSlots()).isEqualTo(Constants.MAX_CONCURRENT_REQUESTS)

        caller.join(5_000)
        assertThat(body).isEqualTo("ok")
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(slots.freeSlots()).isEqualTo(Constants.MAX_CONCURRENT_REQUESTS)
    }

    @Test
    fun `outside a call there is no slot to give back`() {
        val slots = RateLimitInterceptor()
        assertThat(slots.withoutSlot { slots.freeSlots() }).isEqualTo(Constants.MAX_CONCURRENT_REQUESTS)
        assertThat(slots.freeSlots()).isEqualTo(Constants.MAX_CONCURRENT_REQUESTS)
    }
}
