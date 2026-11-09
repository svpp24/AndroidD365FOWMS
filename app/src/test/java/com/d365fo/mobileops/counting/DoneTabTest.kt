package com.d365fo.mobileops.counting

import com.d365fo.mobileops.domain.model.CountingJournalSummary
import com.d365fo.mobileops.domain.model.ORIGIN_WALL_TO_WALL
import com.d365fo.mobileops.presentation.modules.cyclecounting.CountPhase
import com.d365fo.mobileops.presentation.modules.cyclecounting.JournalCountState
import com.d365fo.mobileops.presentation.modules.cyclecounting.isDone
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoneTabTest {

    private fun journal(recounts: Int = 0, wallToWall: Boolean = false) = CountingJournalSummary(
        journalId = "J",
        journalNameId = "CYC",
        description = null,
        journalDate = null,
        lineCount = 10,
        recountLines = recounts,
        originType = if (wallToWall) ORIGIN_WALL_TO_WALL else "SCHEDULED",
    )

    private fun state(phase: CountPhase, failedExceptions: Int = 0, thirdCount: Boolean = false) =
        JournalCountState(
            journalId = "J", phase = phase, total = 10, withQuantity = 10, markedOnly = 0,
            failedExceptions = failedExceptions, thirdCountNeeded = thirdCount,
        )

    @Test
    fun `submitted and posted journals are done`() {
        assertTrue(isDone(journal(), state(CountPhase.SUBMITTED), localOnly = false))
        assertTrue(isDone(journal(), state(CountPhase.POSTED), localOnly = false))
    }

    @Test
    fun `journals still being counted or sent are not done`() {
        for (phase in listOf(CountPhase.READY, CountPhase.COUNTING, CountPhase.SUBMITTING, CountPhase.FAILED)) {
            assertFalse(phase.name, isDone(journal(), state(phase), localOnly = false))
        }
        assertFalse(isDone(journal(), count = null, localOnly = false))
    }

    @Test
    fun `work left for this worker keeps a submitted journal open`() {
        assertFalse(isDone(journal(recounts = 2), state(CountPhase.SUBMITTED), localOnly = false))
        assertFalse(isDone(journal(), state(CountPhase.SUBMITTED, failedExceptions = 1), localOnly = false))
        assertFalse(isDone(journal(), state(CountPhase.SUBMITTED, thirdCount = true), localOnly = false))
    }

    @Test
    fun `a journal D365FO calls done is done with nothing on the device`() {
        val posted = journal().copy(doneNote = "Posted 5 Nov 2026")
        assertTrue(isDone(posted, count = null, localOnly = false))
        assertTrue(isDone(posted.copy(originType = ORIGIN_WALL_TO_WALL), count = null, localOnly = false))
    }

    @Test
    fun `a wall-to-wall journal is done only once D365FO no longer lists it`() {
        assertFalse(isDone(journal(wallToWall = true), state(CountPhase.SUBMITTED), localOnly = false))
        assertTrue(isDone(journal(wallToWall = true), state(CountPhase.SUBMITTED), localOnly = true))
        assertTrue(isDone(journal(wallToWall = true), state(CountPhase.POSTED), localOnly = false))
    }
}
