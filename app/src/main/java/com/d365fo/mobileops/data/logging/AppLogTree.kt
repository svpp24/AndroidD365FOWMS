package com.d365fo.mobileops.data.logging

import timber.log.Timber
import java.time.Instant

/**
 * Feeds every Timber call, at every level, into [LogStore] - no `isLoggable` override, unlike
 * [com.d365fo.mobileops.D365MobileApp]'s release logcat tree, which deliberately drops
 * DEBUG/INFO to avoid ever writing quantities, prices or order numbers to a system-wide log
 * anyone with `adb`/device access can read. This tree's destination is private to the app instead
 * (see [LogStore]'s own doc on why that changes the calculus), so the whole point of planting it
 * is to capture what the logcat-facing trees deliberately throw away.
 */
internal class AppLogTree(private val logStore: LogStore) : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        logStore.append(
            timestamp = Instant.now(),
            level = priority,
            tag = tag ?: "D365",
            message = message,
            throwable = t?.stackTraceToString(),
        )
    }
}
