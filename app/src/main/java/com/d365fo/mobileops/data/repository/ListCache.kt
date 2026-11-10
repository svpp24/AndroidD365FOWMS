package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A short-lived, shared answer for the lists several screens ask D365FO for - today's POs, open
 * transfers, the journals open for counting. Home, the process screens, the enquiry lists and
 * the offline prefetcher each asked on their own, Home every time it showed; now the first
 * caller asks and the rest get that answer for [TTL_MS]. Callers asking at the same moment wait
 * for the one request in flight instead of sending their own.
 *
 * Only successes are kept - a failure is asked again next time. [invalidate] drops everything:
 * SyncStatusProvider calls it whenever the send queue shrinks (a receipt, shipment or order was
 * posted), so a list never shows work this device has just done as still open.
 */
@Singleton
class ListCache @Inject constructor() {
    private class Entry(val at: Long, val value: Any?)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Monotonic milliseconds; replaced in tests. */
    internal var now: () -> Long = { System.nanoTime() / 1_000_000 }

    suspend fun <T> get(key: String, ttlMs: Long = TTL_MS, fetch: suspend () -> NetworkResult<T>): NetworkResult<T> {
        // Never answer before the caller's first suspension: a ViewModel asking from init got a
        // cached answer before its own state existed (crash, 10 Nov). Same timing as a network call.
        kotlinx.coroutines.yield()
        return locks.getOrPut(key) { Mutex() }.withLock {
            val hit = entries[key]
            if (hit != null && now() - hit.at < ttlMs) {
                @Suppress("UNCHECKED_CAST")
                return@withLock NetworkResult.Success(hit.value as T)
            }
            val result = fetch()
            if (result is NetworkResult.Success) entries[key] = Entry(now(), result.data)
            result
        }
    }

    /** Drops every kept answer, or only those whose key starts with [prefix]. */
    fun invalidate(prefix: String? = null) {
        if (prefix == null) entries.clear() else entries.keys.removeIf { it.startsWith(prefix) }
    }

    companion object {
        const val TTL_MS = 90_000L
    }
}
