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

    /**
     * The last good answer per key, kept through [invalidate] - a screen shows it at once while
     * it asks D365FO again (user, 16 Nov 2026: Transfer orders sat on a spinner after each posting).
     */
    private val lastGood = ConcurrentHashMap<String, Entry>()

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
            if (result is NetworkResult.Success) {
                entries[key] = Entry(now(), result.data)
                lastGood[key] = Entry(now(), result.data)
            }
            result
        }
    }

    /** The last good answer for [key] if it is under [maxAgeMs] old - to show while asking again. */
    fun <T> peek(key: String, maxAgeMs: Long = PEEK_MAX_MS): T? {
        val hit = lastGood[key] ?: return null
        @Suppress("UNCHECKED_CAST")
        return if (now() - hit.at < maxAgeMs) hit.value as T else null
    }

    /** Drops every kept answer, or only those whose key starts with [prefix]. */
    fun invalidate(prefix: String? = null) {
        if (prefix == null) entries.clear() else entries.keys.removeIf { it.startsWith(prefix) }
    }

    companion object {
        const val TTL_MS = 90_000L
        const val PEEK_MAX_MS = 12 * 60 * 60_000L
    }
}
