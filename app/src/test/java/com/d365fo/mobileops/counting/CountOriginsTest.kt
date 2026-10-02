package com.d365fo.mobileops.counting

import com.d365fo.mobileops.domain.model.CountingJournalSummary
import com.d365fo.mobileops.domain.model.byPriority
import com.d365fo.mobileops.domain.model.originLabel
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CountOriginsTest {

    private fun journal(id: String, priority: Int, date: Long, origin: String = "SCHEDULED") =
        CountingJournalSummary(id, "Count", null, date, 1, originType = origin, priority = priority)

    @Test
    fun `server priority sorts first, then the oldest journal`() {
        val sorted = listOf(
            journal("SCHED-NEW", priority = 0, date = 300),
            journal("TRIGGERED", priority = 90, date = 400, origin = "TRIGGERED"),
            journal("SCHED-OLD", priority = 0, date = 100),
            journal("SPOT", priority = 50, date = 200, origin = "SPOT"),
        ).byPriority()

        assertThat(sorted.map { it.journalId }).containsExactly("TRIGGERED", "SPOT", "SCHED-OLD", "SCHED-NEW").inOrder()
    }

    @Test
    fun `the app never ranks origins itself - equal priority keeps date order`() {
        val sorted = listOf(
            journal("TRIGGERED", priority = 0, date = 200, origin = "TRIGGERED"),
            journal("SCHEDULED", priority = 0, date = 100),
        ).byPriority()

        assertThat(sorted.map { it.journalId }).containsExactly("SCHEDULED", "TRIGGERED").inOrder()
    }

    @Test
    fun `every origin has a badge except a plain scheduled count`() {
        assertThat(originLabel("SCHEDULED")).isNull()
        listOf("PERIODIC", "SPOT", "TRIGGERED", "OPPORTUNITY", "CONTROL_GROUP", "RECOUNT").forEach {
            assertThat(originLabel(it)).isNotNull()
        }
    }
}
