package com.d365fo.mobileops.data.crash

import android.content.Context

/**
 * Notices the app stopping again and again at start, and sends it to the recovery screen instead
 * of the screen that keeps failing - so unsent work can still be saved to D365FO or a file before
 * anyone thinks of reinstalling (29 Oct).
 *
 * Plain SharedPreferences, written with commit() from the crash handler: it has to work when the
 * encrypted store, the database or Hilt are what is broken. [CRASHES] crashes within [WINDOW_MS]
 * is a loop; a run that lasts [STABLE_MS] clears the count.
 */
object CrashLoopGuard {

    const val CRASHES = 3
    const val WINDOW_MS = 10 * 60_000L
    const val STABLE_MS = 60_000L

    private const val PREFS = "crash_loop_guard"
    private const val KEY_CRASHES = "crashes"
    private const val KEY_LAST = "last_crash"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** From the uncaught-exception handler: synchronous, the process is about to die. */
    fun recordCrash(context: Context, throwable: Throwable) {
        runCatching {
            val now = System.currentTimeMillis()
            val recent = crashTimes(context).filter { now - it < WINDOW_MS } + now
            prefs(context).edit()
                .putString(KEY_CRASHES, recent.takeLast(CRASHES * 2).joinToString(","))
                .putString(KEY_LAST, "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}".take(300))
                .commit()
        }
    }

    fun inRecovery(context: Context): Boolean = runCatching {
        val now = System.currentTimeMillis()
        crashTimes(context).count { now - it < WINDOW_MS } >= CRASHES
    }.getOrDefault(false)

    /** The last crash, for the recovery screen and support. */
    fun lastCrash(context: Context): String? = runCatching { prefs(context).getString(KEY_LAST, null) }.getOrNull()

    /** The app ran long enough, or the worker chose to start it normally. */
    fun reset(context: Context) {
        runCatching { prefs(context).edit().remove(KEY_CRASHES).commit() }
    }

    private fun crashTimes(context: Context): List<Long> =
        prefs(context).getString(KEY_CRASHES, null).orEmpty().split(',').mapNotNull { it.toLongOrNull() }
}
