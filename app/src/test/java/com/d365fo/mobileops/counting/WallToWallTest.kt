package com.d365fo.mobileops.counting

import com.d365fo.mobileops.data.counting.CountClaimManager
import com.d365fo.mobileops.data.counting.CountSubmitRunner
import com.d365fo.mobileops.data.counting.RunResult
import com.d365fo.mobileops.data.counting.TransportResult
import com.d365fo.mobileops.data.local.entity.CountSubmissionError
import com.d365fo.mobileops.data.local.entity.CountSubmissionState
import com.d365fo.mobileops.data.local.entity.TransferMode
import com.d365fo.mobileops.domain.model.COUNT_MODE_DOUBLE_BLIND
import com.d365fo.mobileops.domain.model.CountRange
import com.d365fo.mobileops.domain.model.CountingJournalSummary
import com.d365fo.mobileops.domain.model.ORIGIN_WALL_TO_WALL
import com.d365fo.mobileops.domain.model.originLabel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class WallToWallTest {

    private fun journal(origin: String, lines: Int = 10, recounts: Int = 0) = CountingJournalSummary(
        journalId = "W2W-J",
        journalNameId = "W2W",
        description = null,
        journalDate = null,
        lineCount = lines,
        countMode = COUNT_MODE_DOUBLE_BLIND,
        recountLines = recounts,
        originType = origin,
    )

    @Test
    fun `a wall-to-wall journal is only under its own tab, recounts included`() {
        val j = journal(ORIGIN_WALL_TO_WALL, lines = 10, recounts = 3)
        assertThat(j.onWallToWallTab).isTrue()
        assertThat(j.onCycleTab).isFalse()
        assertThat(j.onRecountTab).isFalse()
        assertThat(originLabel(j.originType)).isEqualTo("Wall-to-wall")
    }

    @Test
    fun `a cycle journal is not under Wall-to-wall`() {
        val j = journal("SCHEDULED", lines = 10, recounts = 3)
        assertThat(j.onWallToWallTab).isFalse()
        assertThat(j.onCycleTab).isTrue()
        assertThat(j.onRecountTab).isTrue()
    }

    private val transport = FakeCountTransport()
    private val store = FakeSubmissionStore()
    private val remote = FakeCountTaskRepository()
    private val claimStore = FakeClaimStore()
    private val claims = CountClaimManager(remote, claimStore)
    private val runner = CountSubmitRunner(transport, store, claims).also {
        it.pollIntervalMs = 0
        it.maxPollsPerRun = 5
    }
    private val location = CountRange("LOC:A-01", "A-01", "A-01", "A-01", 4)

    private suspend fun claimLocation(token: String) {
        remote.claimAnswer = { _, range -> FakeCountTaskRepository.granted(range, token = token) }
        claims.claim("W2W-J", location)
        store.queue("W2W-J", mode = TransferMode.DIRECT)
    }

    @Test
    fun `End count of a claimed location completes it, and the counter is told`() = runTest {
        claimLocation("W2W-17")
        transport.direct = TransportResult.Ok(
            com.d365fo.mobileops.data.counting.DirectSubmitResult(acceptedLines = 4, passResult = "4 counted."),
        )

        assertThat(runner.run("W2W-J")).isEqualTo(RunResult.DONE)

        assertThat(transport.completedLocations).containsExactly("A-01")
        val row = store.rows["W2W-J"]!!
        assertThat(row.state).isEqualTo(CountSubmissionState.SUCCEEDED)
        assertThat(row.resultNote).isEqualTo("4 counted. A-01 is complete. 2 location(s) left.")
        // Completed first, released after.
        assertThat(remote.released).containsExactly("W2W-J")
    }

    @Test
    fun `a location the server will not complete says why, the counts still landed`() = runTest {
        claimLocation("W2W-17")
        transport.complete = TransportResult.Fail(CountSubmissionError.OTHER, "2 line(s) at A-01 have no count.")

        assertThat(runner.run("W2W-J")).isEqualTo(RunResult.DONE)

        val row = store.rows["W2W-J"]!!
        assertThat(row.state).isEqualTo(CountSubmissionState.SUCCEEDED)
        assertThat(row.resultNote).isEqualTo("A-01 is not complete: 2 line(s) at A-01 have no count.")
    }

    @Test
    fun `an empty check that did not go leaves the location open`() = runTest {
        claimLocation("W2W-17")
        store.addException("W2W-J")
        transport.exceptions = TransportResult.Fail(CountSubmissionError.OTHER, "refused")

        runner.run("W2W-J")

        assertThat(transport.completedLocations).isEmpty()
        assertThat(store.rows["W2W-J"]!!.resultNote).contains("A-01 is not complete")
    }

    @Test
    fun `second counts after every location is done complete nothing`() = runTest {
        claimLocation("W2W-0")

        runner.run("W2W-J")

        assertThat(transport.completedLocations).isEmpty()
    }

    @Test
    fun `an ordinary journal claim completes nothing`() = runTest {
        claimLocation("token-1")

        runner.run("W2W-J")

        assertThat(transport.completedLocations).isEmpty()
    }
}
