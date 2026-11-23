package com.d365fo.mobileops.presentation.modules.cyclecounting.counts

import com.d365fo.mobileops.data.counting.CountSummary
import com.d365fo.mobileops.data.counting.CountType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A blind count is made empty and waits for the supervisor to add what to count (23 Nov 2026). */
class CountSummaryWaitingTest {

    private fun count(type: CountType, items: Int, status: String = "Open") = CountSummary(
        masterId = "AAC-20261123-1",
        type = type,
        reference = "",
        description = "",
        dueUtc = "",
        status = status,
        journalId = "",
        itemCount = items,
        counterCount = 0,
        lineCount = 0,
        myStatus = "",
    )

    @Test
    fun `an open blind count without items waits for the supervisor`() {
        assertTrue(count(CountType.BLIND, items = 0).waitingForItems)
    }

    @Test
    fun `a blind count with items can be counted`() {
        assertFalse(count(CountType.BLIND, items = 3).waitingForItems)
    }

    @Test
    fun `other counts and finished ones never wait`() {
        // Wall to wall never has requested items; it is counted all the same.
        assertFalse(count(CountType.WALL_TO_WALL, items = 0).waitingForItems)
        assertFalse(count(CountType.HAZARD, items = 0).waitingForItems)
        assertFalse(count(CountType.BLIND, items = 0, status = "JournalCreated").waitingForItems)
    }
}
