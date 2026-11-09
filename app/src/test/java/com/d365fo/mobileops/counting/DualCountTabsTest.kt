package com.d365fo.mobileops.counting

import com.d365fo.mobileops.domain.model.COUNT_MODE_DOUBLE_BLIND
import com.d365fo.mobileops.domain.model.CountingJournalSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DualCountTabsTest {

    private fun journal(lines: Int, recounts: Int) = CountingJournalSummary(
        journalId = "J",
        journalNameId = "CYC",
        description = null,
        journalDate = null,
        lineCount = lines,
        countMode = COUNT_MODE_DOUBLE_BLIND,
        recountLines = recounts,
    )

    @Test
    fun `lines to count and no recounts are under Cycle only`() {
        val j = journal(lines = 100, recounts = 0)
        assertTrue(j.onCycleTab)
        assertFalse(j.onRecountTab)
    }

    @Test
    fun `only recounts are under Recount only`() {
        val j = journal(lines = 3, recounts = 3)
        assertFalse(j.onCycleTab)
        assertTrue(j.onRecountTab)
    }

    @Test
    fun `some of each is under both`() {
        val j = journal(lines = 10, recounts = 3)
        assertTrue(j.onCycleTab)
        assertTrue(j.onRecountTab)
    }

    @Test
    fun `a supervisor's journal with nothing to count stays reachable under Cycle`() {
        val j = journal(lines = 0, recounts = 0)
        assertTrue(j.onCycleTab)
        assertFalse(j.onRecountTab)
    }
}
