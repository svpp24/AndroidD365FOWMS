package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.data.local.dao.ReadCacheDao
import com.d365fo.mobileops.data.local.entity.ReadCacheEntity
import com.d365fo.mobileops.data.remote.interceptor.IdempotentRead
import com.d365fo.mobileops.data.remote.interceptor.OfflineReadCacheInterceptor
import com.d365fo.mobileops.data.remote.interceptor.OfflineReadNotice
import com.google.common.truth.Truth.assertThat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class OfflineReadCacheTest {

    private val hour = 60 * 60 * 1000L
    private var clock = 1_000_000 * hour
    private val rows = mutableMapOf<String, ReadCacheEntity>()
    private val dao = object : ReadCacheDao {
        override fun find(key: String) = rows[key]
        override fun save(entry: ReadCacheEntity) { rows[entry.key] = entry }
        override fun deleteOlderThan(before: Long) = 0
        override fun trimTo(keep: Int) = 0
        override fun clear() = rows.clear()
    }
    private val server = MockWebServer().apply { start() }
    private val client = OkHttpClient.Builder()
        .addInterceptor(OfflineReadCacheInterceptor({ dao }, { clock }))
        .build()

    @AfterEach
    fun stop() = runCatching { server.shutdown() }.let { }

    private fun call(operation: String, body: String = """{"_request":{"PurchId":"PO-1"}}""", tagged: Boolean = true) =
        client.newCall(
            Request.Builder()
                .url(server.url("/api/services/HSMobileOpsServiceGroup/$operation"))
                .post(body.toRequestBody("application/json".toMediaType()))
                .apply { if (tagged) tag(IdempotentRead::class.java, IdempotentRead) }
                .build(),
        ).execute()

    @Test
    fun `a saved answer is replayed when D365FO can't be reached`() {
        server.enqueue(MockResponse().setBody("""{"Success":true,"PurchId":"PO-1"}"""))
        assertThat(call("HSMobileOpsReceivingSvc/getPurchaseOrder").body!!.string()).contains("PO-1")

        server.shutdown()
        clock += hour
        val offline = call("HSMobileOpsReceivingSvc/getPurchaseOrder")

        assertThat(offline.code).isEqualTo(200)
        assertThat(offline.body!!.string()).isEqualTo("""{"Success":true,"PurchId":"PO-1"}""")
        assertThat(offline.header(OfflineReadCacheInterceptor.HEADER_SAVED_AT)).isEqualTo((clock - hour).toString())
        assertThat(OfflineReadNotice.savedAt.value).isEqualTo(clock - hour)
    }

    @Test
    fun `a server error also falls back to the saved answer`() {
        server.enqueue(MockResponse().setBody("""{"Success":true}"""))
        call("HSMobileOpsItemSvc/getItem").close()
        server.enqueue(MockResponse().setResponseCode(503))

        assertThat(call("HSMobileOpsItemSvc/getItem").code).isEqualTo(200)
    }

    @Test
    fun `a different order is a different answer`() {
        server.enqueue(MockResponse().setBody("""{"PurchId":"PO-1"}"""))
        call("HSMobileOpsReceivingSvc/getPurchaseOrder").close()
        server.shutdown()

        assertThrows<IOException> { call("HSMobileOpsReceivingSvc/getPurchaseOrder", """{"_request":{"PurchId":"PO-2"}}""") }
    }

    @Test
    fun `postings and untagged calls are never kept`() {
        server.enqueue(MockResponse().setBody("""{"Success":true}"""))
        server.enqueue(MockResponse().setBody("""{"Success":true}"""))
        call("HSMobileOpsPostingSvc/postProductReceipt").close()
        call("HSMobileOpsReceivingSvc/getPurchaseOrder", tagged = false).close()

        assertThat(rows).isEmpty()
    }

    @Test
    fun `a copy past its age limit is not replayed`() {
        server.enqueue(MockResponse().setBody("""{"Success":true}"""))
        call("HSMobileOpsReceivingSvc/getPurchaseOrder").close()
        server.shutdown()
        clock += 73 * hour

        assertThrows<IOException> { call("HSMobileOpsReceivingSvc/getPurchaseOrder") }
    }

    @Test
    fun `item master data is kept longer than orders`() {
        server.enqueue(MockResponse().setBody("""{"ItemId":"A"}"""))
        call("HSMobileOpsItemSvc/getItem").close()
        server.shutdown()
        clock += 10 * 24 * hour

        assertThat(call("HSMobileOpsItemSvc/getItem").code).isEqualTo(200)
    }
}
