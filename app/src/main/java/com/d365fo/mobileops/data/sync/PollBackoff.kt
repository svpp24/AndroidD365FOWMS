package com.d365fo.mobileops.data.sync

/**
 * Waits between status checks on a long-running D365FO export: quick at first, when a small
 * export is about to finish, then slower - one long export no longer costs a call every few
 * seconds for minutes, out of an allowance the whole fleet shares. The waits add up to at most
 * [budgetMs], so a caller's overall time limit stays what it was.
 */
object PollBackoff {

    fun delays(budgetMs: Long, firstMs: Long = 3_000L): List<Long> {
        val waits = mutableListOf<Long>()
        var total = 0L
        var index = 0
        while (true) {
            val wait = when {
                index < 5 -> firstMs
                index < 10 -> 5_000L
                else -> 10_000L
            }
            if (total + wait > budgetMs) break
            waits += wait
            total += wait
            index++
        }
        return waits
    }
}
