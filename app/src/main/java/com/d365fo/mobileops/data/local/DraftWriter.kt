package com.d365fo.mobileops.data.local

import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saves a screen's draft off the main thread, keeping only the newest version per key.
 *
 * A count draft is the whole count (every line) as one JSON string. It is saved after every scan,
 * so encoding and writing it on the main thread cost more as the count grows (performance plan,
 * phase 2). Here the caller hands over a write and returns at once. One background thread runs
 * the writes in order. A write still waiting when a newer one for the same key arrives is
 * replaced, because only the newest draft matters. The newest write is always run.
 */
@Singleton
class DraftWriter internal constructor(private val executor: Executor) {

    @Inject constructor() : this(
        Executors.newSingleThreadExecutor { r -> Thread(r, "draft-writer").apply { isDaemon = true } },
    )

    private val pending = ConcurrentHashMap<String, AtomicReference<(() -> Unit)?>>()

    /** Runs [write] (the encode as well as the save) on the writer thread, unless a newer one for [key] replaces it first. */
    fun submit(key: String, write: () -> Unit) {
        val slot = pending.getOrPut(key) { AtomicReference(null) }
        // Only the submit that finds the slot empty schedules a run. A later one just swaps in its
        // write, and the run already queued picks up whichever write is newest.
        if (slot.getAndSet(write) == null) {
            executor.execute {
                slot.getAndSet(null)?.let { w ->
                    runCatching(w).onFailure { Timber.e(it, "saving draft %s failed", key) }
                }
            }
        }
    }
}
