package com.d365fo.mobileops.data.logging

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One captured log line - see [LogStore]'s own doc for what "captured" means here.
 *
 * [id] exists purely for the log viewer's row-expansion state (which rows are expanded, keyed
 * by id) - timestamp alone isn't a safe key, since two entries can legitimately share the same
 * millisecond.
 */
data class LogEntry(
    val id: Long,
    val timestamp: Instant,
    val level: Int,
    val tag: String,
    val message: String,
    val throwable: String?,
) {
    val levelLabel: String
        get() = when (level) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> "?"
        }

    /** Rough UTF-8 byte size of this entry, for [LogStore]'s 2 MB budget - not exact (no framing
     *  overhead counted), but exactness doesn't matter for a soft memory cap. */
    val approxSizeBytes: Int
        get() = tag.length + message.length + (throwable?.length ?: 0) + 48
}

/**
 * Every log line this app produces, in memory, up to a 2 MB budget - on request, this must work
 * regardless of sign-in state or license state, since those are often exactly what someone is
 * trying to diagnose using it. Fed by [AppLogTree], planted unconditionally in
 * D365MobileApp.onCreate ahead of every other tree - not gated by
 * BuildConfig.ALLOW_SENSITIVE_LOGGING the way the logcat-facing trees are (see D365MobileApp's
 * own doc on why release logcat is deliberately thin), and not level-filtered either: every
 * Timber call at every level, including errors and the uncaught-exception handler
 * D365MobileApp installs, reaches this store. It never leaves the device on its own - there is
 * no upload, no automatic network call, no export-to-file. A worker or supervisor can view it and
 * copy it to the clipboard to paste into a support message themselves, which is a deliberate
 * choice, not an oversight - the exposure that matters is data leaving the device without a
 * person deciding to send it, not a person choosing to read their own device's own log.
 *
 * The last [RetentionMillis] (5 minutes, feedback 11 Oct: "maintain only last 5min log") - older
 * entries drop as new ones arrive, and older ones read back from disk at start are skipped.
 * [MaxBytes] still caps a burst inside those 5 minutes. [LogFilePersistence] keeps its own 2 MB
 * budget on disk; what it holds beyond 5 minutes is never shown.
 *
 * A hard crash still loses whatever hadn't reached disk yet, but not everything - see
 * [LogFilePersistence]'s own doc on how "even the errors" (this class's opening line) actually
 * survives the process dying, and [D365MobileApp]'s uncaught-exception handler on how the crash
 * itself gets a chance to be flushed before termination.
 */
@Singleton
class LogStore @Inject constructor(
    private val persistence: LogFilePersistence,
    private val applicationScope: CoroutineScope,
) {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val nextId = AtomicLong(0)

    init {
        // Off the calling thread - this runs during Hilt graph construction inside
        // Application.onCreate, and file I/O there would delay the first frame. Loaded entries
        // are merged (not a blind overwrite) with whatever this process has already logged
        // itself by the time the read finishes, and re-sorted - both sources are already
        // individually chronological, so this only has to interleave them correctly, not
        // re-derive order from nothing.
        applicationScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - RetentionMillis
            val loaded = persistence.loadRecent().filter { it.epochMillis >= cutoff }.map { p ->
                LogEntry(nextId.getAndIncrement(), Instant.ofEpochMilli(p.epochMillis), p.level, p.tag, p.message, p.throwable)
            }
            if (loaded.isNotEmpty()) {
                _entries.update { live -> (loaded + live).sortedBy { it.timestamp } }
            }
        }
    }

    fun append(timestamp: Instant, level: Int, tag: String, message: String, throwable: String?) {
        val entry = LogEntry(nextId.getAndIncrement(), timestamp, level, tag, message, throwable)
        persistence.append(entry)
        _entries.update { current ->
            val next = current + entry
            var totalBytes = next.sumOf { it.approxSizeBytes }
            // Older than 5 minutes first - next is chronological, so that is a prefix too.
            val cutoff = timestamp.minusMillis(RetentionMillis)
            var dropFrom = next.indexOfFirst { !it.timestamp.isBefore(cutoff) }.coerceAtLeast(0)
            for (i in 0 until dropFrom) totalBytes -= next[i].approxSizeBytes
            // Drop oldest-first until back under budget - next is chronological, so the entries
            // to evict are always a prefix, never a scan of the whole list.
            while (totalBytes > MaxBytes && dropFrom < next.size) {
                totalBytes -= next[dropFrom].approxSizeBytes
                dropFrom++
            }
            if (dropFrom == 0) next else next.subList(dropFrom, next.size)
        }
    }

    fun clear() {
        _entries.value = emptyList()
        persistence.clear()
    }

    /** See [LogFilePersistence.flush] - called only from D365MobileApp's uncaught-exception
     *  handler, to give the crash's own log line a real chance to reach disk before the process
     *  actually dies. */
    fun flush() = persistence.flush()

    companion object {
        const val MaxBytes = 2 * 1024 * 1024
        const val RetentionMillis = 5 * 60 * 1000L
    }
}
