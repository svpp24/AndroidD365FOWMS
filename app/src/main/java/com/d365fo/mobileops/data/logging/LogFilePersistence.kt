package com.d365fo.mobileops.data.logging

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** One line of [LogFilePersistence]'s on-disk format - epoch millis rather than [java.time.Instant]
 *  directly, since kotlinx.serialization has no built-in Instant support and this project has no
 *  reason to add a custom serializer just for this. */
@Serializable
data class PersistedLogEntry(
    val epochMillis: Long,
    val level: Int,
    val tag: String,
    val message: String,
    val throwable: String?,
)

/**
 * Disk half of [LogStore] - a crash kills the in-memory buffer instantly, so "even the errors"
 * (see LogStore's own doc) only actually covers a crash if the crash's own log line survives the
 * process dying, which means it has to already be on disk, not queued somewhere in memory when
 * the process ends.
 *
 * One background thread does every write, in order - not a coroutine dispatcher pool, since
 * concurrent appends from multiple threads would interleave into a corrupt file; a single
 * dedicated executor makes "read this file back later" safe without any file-level locking.
 * Appends are cheap (one JSON line via [kotlinx.serialization] - no hand-rolled escaping for a
 * message that might contain a newline or a tab); the file is trimmed back to [LogStore.MaxBytes]
 * only when it has grown a full [TrimMarginBytes] past that, not on every single append, so the
 * cost of a full rewrite is paid rarely instead of on every log line.
 */
@Singleton
class LogFilePersistence @Inject constructor(private val context: Context) {

    private val file: File by lazy {
        File(context.filesDir, "logs/app_log.jsonl").apply { parentFile?.mkdirs() }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val executor = Executors.newSingleThreadExecutor()

    fun append(entry: LogEntry) {
        executor.execute {
            runCatching {
                val persisted = PersistedLogEntry(
                    entry.timestamp.toEpochMilli(),
                    entry.level,
                    entry.tag,
                    entry.message,
                    entry.throwable,
                )
                file.appendText(json.encodeToString(persisted) + "\n")
                if (file.length() > LogStore.MaxBytes + TrimMarginBytes) trimToBudget()
            }.onFailure { Timber.tag(LogTag).w(it, "append failed") }
        }
    }

    fun clear() {
        executor.execute { runCatching { file.writeText("") }.onFailure { Timber.tag(LogTag).w(it, "clear failed") } }
    }

    /**
     * Reads the whole file and returns everything currently on disk - called once, at
     * [LogStore] startup, off the main thread. Not itself trimmed to a budget on read (that's
     * [trimToBudget]'s job, already keeping the file near [LogStore.MaxBytes]); this just parses
     * whatever is there.
     */
    fun loadRecent(): List<PersistedLogEntry> =
        runCatching {
            if (!file.exists()) return emptyList()
            file.readLines().mapNotNull { line ->
                runCatching { json.decodeFromString<PersistedLogEntry>(line) }.getOrNull()
            }
        }.getOrElse {
            Timber.tag(LogTag).w(it, "loadRecent failed")
            emptyList()
        }

    /**
     * Called only from within [append]'s executor task, so this already has exclusive access to
     * the file - keeps the newest lines that fit in [LogStore.MaxBytes], oldest first to go,
     * mirroring exactly how [LogStore] itself evicts from memory.
     */
    private fun trimToBudget() {
        val lines = file.readLines()
        var totalBytes = 0
        val kept = ArrayDeque<String>()
        for (line in lines.asReversed()) {
            val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1
            if (totalBytes + lineBytes > LogStore.MaxBytes) break
            kept.addFirst(line)
            totalBytes += lineBytes
        }
        runCatching {
            file.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
        }.onFailure { Timber.tag(LogTag).w(it, "trimToBudget failed") }
    }

    /**
     * Blocks the caller until every write already queued has finished - used only by
     * D365MobileApp's uncaught-exception handler, to give the crash's own log line a real chance
     * to reach disk before the process actually terminates. A no-op read-only task appended
     * after everything already queued: the single-thread executor runs tasks in submission
     * order, so this cannot complete before an already-queued append does.
     */
    fun flush(timeoutMs: Long = 2000) {
        val latch = CountDownLatch(1)
        executor.execute { latch.countDown() }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private companion object {
        const val TrimMarginBytes = 256 * 1024
        const val LogTag = "LogFilePersistence"
    }
}
