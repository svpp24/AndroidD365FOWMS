package com.d365fo.mobileops.data.remote.interceptor

import com.d365fo.mobileops.data.local.dao.ReadCacheDao
import com.d365fo.mobileops.data.local.entity.ReadCacheEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import timber.log.Timber
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the last answer to each lookup a worker needs on the floor - an order and its lines, an
 * item, a barcode, a location, the order lists - and gives it back when D365FO can't be
 * reached. A receipt or transfer can then still be captured offline. The capture is queued
 * like any other and posts when the connection is back.
 *
 * What is replayed is what D365FO last said, so it can be out of date: another device may have
 * received the same line since. D365FO checks every posting again when it arrives, so a stale
 * answer can delay a mistake but never post one. [OfflineReadNotice] tells the screens a copy is
 * in use, so the worker knows.
 *
 * Only service calls on [REPLAYABLE] and OData entity reads are kept, and only 2xx answers. Postings, sign-in, licensing and
 * exports never are. Copies older than their age limit are not replayed and are deleted.
 * Everything sits in the SQLCipher database with the rest of the app's data.
 *
 * Added outermost in the OkHttp chain, so it also covers a token that can't be fetched offline
 * and [AosReachabilityInterceptor]'s fail-fast while D365FO is down.
 */
@Singleton
class OfflineReadCacheInterceptor internal constructor(
    private val dao: dagger.Lazy<ReadCacheDao>,
    private val now: () -> Long,
) : Interceptor {

    @Inject constructor(dao: dagger.Lazy<ReadCacheDao>) : this(dao, System::currentTimeMillis)

    @Volatile private var writes = 0

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val operation = operationOf(request) ?: return chain.proceed(request)
        val key = keyOf(request)

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            return replay(request, key, operation) ?: throw NotSavedOfflineException(e)
        }

        if (response.code >= 500) {
            replay(request, key, operation)?.let { copy ->
                response.close()
                return copy
            }
            return response
        }
        if (!response.isSuccessful) return response

        val body = response.body ?: return response
        val contentType = body.contentType()
        val text = body.string()
        if (text.length <= MAX_BODY_CHARS) {
            runCatching {
                val now = now()
                dao.get().save(ReadCacheEntity(key = key, path = operation, body = text, savedAt = now))
                if (++writes % PRUNE_EVERY == 0) prune(now)
            }.onFailure { Timber.w(it, "offline cache: could not keep %s", operation) }
        }
        OfflineReadNotice.freshAnswer()
        return response.newBuilder().body(text.toResponseBody(contentType)).build()
    }

    private fun replay(request: Request, key: String, operation: String): Response? {
        val entry = runCatching { dao.get().find(key) }.getOrNull() ?: return null
        if (now() - entry.savedAt > maxAgeMs(operation)) return null
        Timber.i("offline cache: replaying %s saved at %d", operation, entry.savedAt)
        // Only working data (orders, lines, lists) goes into the notice: an item or warehouse
        // saved last week is still right, and would make today's orders look stale.
        if (operation !in MASTER_DATA) OfflineReadNotice.servedCopy(entry.savedAt)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK (offline copy)")
            .header(HEADER_SAVED_AT, entry.savedAt.toString())
            .body(entry.body.toResponseBody(JSON))
            .build()
    }

    private fun prune(now: Long) {
        dao.get().deleteOlderThan(now - MASTER_DATA_MAX_AGE_MS)
        dao.get().trimTo(MAX_ENTRIES)
    }

    companion object {
        const val HEADER_SAVED_AT = "X-HS-Offline-Copy-Saved-At"

        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val HOUR_MS = 60 * 60 * 1000L
        /** Orders, lines, lists, locations, on-hand: these move during a shift. */
        private const val WORKING_DATA_MAX_AGE_MS = 72 * HOUR_MS
        /** Items, barcodes, units, warehouses: these change rarely. */
        private const val MASTER_DATA_MAX_AGE_MS = 30 * 24 * HOUR_MS
        private const val MAX_BODY_CHARS = 2_000_000
        private const val MAX_ENTRIES = 3_000
        private const val PRUNE_EVERY = 50

        /** Service operations whose answer may be replayed offline, as "Service/operation". */
        val REPLAYABLE: Set<String> = setOf(
            "HSMobileOpsReceivingSvc/getPurchaseOrder",
            "HSMobileOpsReceivingSvc/getLinesForItem",
            "HSMobileOpsTransferSvc/getTransferOrder",
            "HSMobileOpsTransferSvc/getLinesForItem",
            "HSMobileOpsInquirySvc/validateLocation",
            "HSMobileOpsInquirySvc/validateSerial",
            "HSMobileOpsInquirySvc/validateBatch",
            "HSMobileOpsInquirySvc/getOnHand",
            "HSMobileOpsCountingSvc/listOpenJournals",
            "HSMobileOpsPurchListSvc/getOrdersForDate",
            "HSMobileOpsTransferListSvc/listTransferOrders",
            "HSMobileOpsTransferListSvc/listPickedSerials",
            "HSMobileOpsItemSvc/getItem",
            "HSMobileOpsItemSvc/getBarcode",
            "HSMobileOpsItemSvc/getWarehouses",
            "HSMobileOpsUnitSvc/getItemUnits",
            "HSMobileOpsUserWarehouseSvc/getUserWarehouses",
            "HSMobileOpsOrderCreateSvc/getOrderItem",
            "HSMobileOpsOrderCreateSvc/getStock",
            "HSMobileOpsOrderCreateSvc/listPurchaseOrdersToConfirm",
            "HSMobileOpsOrderCreateSvc/getPurchaseOrder",
            "HSMobileOpsOrderCreateSvc/listReturnReasons",
            "HSMobileOpsFeedbackSvc/listMyFeedback",
        )

        private val MASTER_DATA: Set<String> = setOf(
            "HSMobileOpsOrderCreateSvc/getOrderItem",
            "HSMobileOpsItemSvc/getItem",
            "HSMobileOpsItemSvc/getBarcode",
            "HSMobileOpsItemSvc/getWarehouses",
            "HSMobileOpsUnitSvc/getItemUnits",
            "HSMobileOpsUserWarehouseSvc/getUserWarehouses",
        )

        fun maxAgeMs(operation: String): Long =
            if (operation in MASTER_DATA) MASTER_DATA_MAX_AGE_MS else WORKING_DATA_MAX_AGE_MS

        /**
         * "Service/operation" when [request] is a replayable service read, "data/EntitySet" for
         * an OData entity read (always a GET, so always a read), else null.
         */
        fun operationOf(request: Request): String? {
            val segments = request.url.pathSegments.filter { it.isNotEmpty() }
            if (request.method == "GET") {
                val data = segments.indexOf("data")
                return if (data >= 0 && data < segments.size - 1) "data/${segments[data + 1].substringBefore('(')}" else null
            }
            if (request.method != "POST" || request.tag(IdempotentRead::class.java) == null) return null
            if (segments.size < 2) return null
            return "${segments[segments.size - 2]}/${segments.last()}".takeIf { it in REPLAYABLE }
        }

        /** Method, URL and body - so company, warehouse and order are all part of the key. */
        fun keyOf(request: Request): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(request.method.toByteArray())
            digest.update(request.url.toString().toByteArray())
            request.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                digest.update(buffer.readByteArray())
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** A replayable read failed to reach D365FO and has no saved copy to answer with. */
class NotSavedOfflineException(cause: IOException) : IOException(cause.message, cause)

/**
 * Whether a screen is currently working from a saved offline copy, and the oldest copy's time.
 * Set by [OfflineReadCacheInterceptor]; cleared by the next fresh answer from D365FO.
 */
object OfflineReadNotice {
    private val _savedAt = MutableStateFlow<Long?>(null)
    val savedAt: StateFlow<Long?> = _savedAt.asStateFlow()

    internal fun servedCopy(savedAt: Long) {
        _savedAt.value = _savedAt.value?.let { minOf(it, savedAt) } ?: savedAt
    }

    internal fun freshAnswer() {
        if (_savedAt.value != null) _savedAt.value = null
    }
}
