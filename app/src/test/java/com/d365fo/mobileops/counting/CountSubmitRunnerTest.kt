package com.d365fo.mobileops.counting

import com.d365fo.mobileops.data.counting.CountClaimManager
import com.d365fo.mobileops.data.counting.CountSubmitRunner
import com.d365fo.mobileops.data.counting.RunResult
import com.d365fo.mobileops.data.counting.TransportResult
import com.d365fo.mobileops.data.counting.toTransport
import com.d365fo.mobileops.data.local.entity.CountSubmissionError
import com.d365fo.mobileops.data.local.entity.CountSubmissionState
import com.d365fo.mobileops.data.local.entity.TransferMode
import com.d365fo.mobileops.domain.model.ImportJobStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CountSubmitRunnerTest {

    private val transport = FakeCountTransport()
    private val store = FakeSubmissionStore()
    private val remote = FakeCountTaskRepository()
    private val claimStore = FakeClaimStore()
    private val claims = CountClaimManager(remote, claimStore)
    private val runner = CountSubmitRunner(transport, store, claims).also {
        it.pollIntervalMs = 0
        it.maxPollsPerRun = 5
    }

    @Test
    fun `DMF success marks the journal submitted and releases the claim`() = runTest {
        claims.claim("CNT-1", null)
        store.queue("CNT-1")
        transport.polls += TransportResult.Ok(ImportJobStatus.Running(50))
        transport.polls += TransportResult.Ok(ImportJobStatus.Succeeded)

        assertThat(runner.run("CNT-1")).isEqualTo(RunResult.DONE)

        assertThat(store.rows["CNT-1"]!!.state).isEqualTo(CountSubmissionState.SUCCEEDED)
        assertThat(store.journalStatus["CNT-1"]).isEqualTo("SUBMITTED")
        assertThat(remote.released).containsExactly("CNT-1")
        assertThat(transport.claimTokens.single()).isEqualTo("token-1")
    }

    @Test
    fun `audit travels with the submission`() = runTest {
        store.queue("CNT-1")
        transport.polls += TransportResult.Ok(ImportJobStatus.Succeeded)

        runner.run("CNT-1")

        val audit = transport.audits.single()
        assertThat(audit.submissionId).isEqualTo("sub-CNT-1")
        assertThat(audit.userId).isEqualTo("counter1")
        assertThat(audit.deviceId).isEqualTo("device-1")
        assertThat(audit.startedAt).isEqualTo(1_000L)
        assertThat(audit.submittedAt).isEqualTo(2_000L)
        assertThat(audit.passNumber).isEqualTo(1)
        assertThat(audit.originType).isEqualTo("SCHEDULED")
    }

    @Test
    fun `already posted is a definite failure, never retried`() = runTest {
        store.queue("CNT-1")
        transport.start = businessRule("JOURNAL_POSTED").toTransport()

        assertThat(runner.run("CNT-1")).isEqualTo(RunResult.DONE)

        val row = store.rows["CNT-1"]!!
        assertThat(row.state).isEqualTo(CountSubmissionState.FAILED)
        assertThat(row.error).isEqualTo(CountSubmissionError.ALREADY_POSTED)
        assertThat(store.journalStatus["CNT-1"]).isEqualTo("FAILED")
    }

    @Test
    fun `offline submit retries and keeps the submission queued`() = runTest {
        store.queue("CNT-1")
        transport.start = offline().toTransport()

        assertThat(runner.run("CNT-1")).isEqualTo(RunResult.RETRY)

        assertThat(store.rows["CNT-1"]!!.state).isEqualTo(CountSubmissionState.QUEUED)
        assertThat(store.journalStatus).doesNotContainKey("CNT-1")
    }

    @Test
    fun `HTTP 429 retries`() = runTest {
        store.queue("CNT-1")
        transport.start = tooManyRequests().toTransport()

        assertThat(runner.run("CNT-1")).isEqualTo(RunResult.RETRY)
        assertThat(store.rows["CNT-1"]!!.state).isEqualTo(CountSubmissionState.QUEUED)
    }

    @Test
    fun `a resumed job polls the stored execution instead of importing again`() = runTest {
        store.queue("CNT-1", executionId = "exec-already-enqueued")
        transport.polls += TransportResult.Ok(ImportJobStatus.Succeeded)

        assertThat(runner.run("CNT-1")).isEqualTo(RunResult.DONE)

        assertThat(transport.startCalls).isEqualTo(0)
        assertThat(store.rows["CNT-1"]!!.state).isEqualTo(CountSubmissionState.SUCCEEDED)
    }

    @Test
    fun `a lost claim blocks submit without touching the server`() = runTest {
        claims.claim("CNT-1", null)
        claimStore.markLost("CNT-1", "Your claim on this journal expired.")
        store.queue("CNT-1")

        assertThat(runner.run("CNT-1")).isEqualTo(RunResult.DONE)

        assertThat(transport.startCalls).isEqualTo(0)
        assertThat(store.rows["CNT-1"]!!.error).isEqualTo(CountSubmissionError.CLAIM_LOST)
    }

    @Test
    fun `claim lost reported by the server ends the submission`() = runTest {
        store.queue("CNT-1")
        transport.start = businessRule("CLAIM_LOST").toTransport()

        runner.run("CNT-1")

        assertThat(store.rows["CNT-1"]!!.error).isEqualTo(CountSubmissionError.CLAIM_LOST)
    }

    @Test
    fun `DMF execution failure is reported with its reason`() = runTest {
        store.queue("CNT-1")
        transport.polls += TransportResult.Ok(ImportJobStatus.Failed("2 lines not accepted - blocked item (lines 4, 7)"))

        runner.run("CNT-1")

        val row = store.rows["CNT-1"]!!
        assertThat(row.error).isEqualTo(CountSubmissionError.DMF_FAILED)
        assertThat(row.message).contains("lines 4, 7")
    }

    @Test
    fun `partially accepted import is submitted with its warning`() = runTest {
        store.queue("CNT-1")
        transport.polls += TransportResult.Ok(ImportJobStatus.PartiallySucceeded("1 line not accepted"))

        runner.run("CNT-1")

        assertThat(store.rows["CNT-1"]!!.state).isEqualTo(CountSubmissionState.PARTIAL)
        assertThat(store.journalStatus["CNT-1"]).isEqualTo("SUBMITTED")
    }

    @Test
    fun `an import still running after this run's polls is resumed later, not failed`() = runTest {
        store.queue("CNT-1")

        assertThat(runner.run("CNT-1")).isEqualTo(RunResult.RETRY)
        assertThat(store.rows["CNT-1"]!!.executionId).isEqualTo("exec-1")
        assertThat(store.rows["CNT-1"]!!.state).isEqualTo(CountSubmissionState.IMPORTING)
    }

    @Test
    fun `DIRECT mode submits in one call`() = runTest {
        store.queue("CNT-1", mode = TransferMode.DIRECT)

        assertThat(runner.run("CNT-1")).isEqualTo(RunResult.DONE)

        assertThat(transport.startCalls).isEqualTo(0)
        assertThat(store.rows["CNT-1"]!!.state).isEqualTo(CountSubmissionState.SUCCEEDED)
    }

    @Test
    fun `DIRECT not implemented on the server is reported, not retried`() = runTest {
        store.queue("CNT-1", mode = TransferMode.DIRECT)
        transport.direct = businessRule("NOT_IMPLEMENTED").toTransport()

        assertThat(runner.run("CNT-1")).isEqualTo(RunResult.DONE)
        assertThat(store.rows["CNT-1"]!!.error).isEqualTo(CountSubmissionError.NOT_AVAILABLE)
    }

    @Test
    fun `unexpected items and empty locations are sent after the counts`() = runTest {
        store.queue("CNT-1")
        store.addException("CNT-1")
        store.locationChecks += com.d365fo.mobileops.data.local.entity.CountLocationCheckEntity(
            "CNT-1", "B-02", verifiedEmpty = true, verifiedBy = "counter1", verifiedAt = 1_700L,
        )
        transport.polls += TransportResult.Ok(ImportJobStatus.Succeeded)

        assertThat(runner.run("CNT-1")).isEqualTo(RunResult.DONE)

        assertThat(transport.sentExceptions.single().single().itemId).isEqualTo("UNEXPECTED-1")
        assertThat(transport.sentEmptyLocations.single().single().locationId).isEqualTo("B-02")
        assertThat(store.exceptions.single().sentAt).isNotNull()
        assertThat(store.rows["CNT-1"]!!.state).isEqualTo(CountSubmissionState.SUCCEEDED)
    }

    @Test
    fun `exception submit failure keeps the counts submitted and says so`() = runTest {
        store.queue("CNT-1")
        store.addException("CNT-1")
        transport.polls += TransportResult.Ok(ImportJobStatus.Succeeded)
        transport.exceptions = businessRule("NOT_IMPLEMENTED").toTransport()

        assertThat(runner.run("CNT-1")).isEqualTo(RunResult.DONE)

        val row = store.rows["CNT-1"]!!
        assertThat(row.state).isEqualTo(CountSubmissionState.PARTIAL)
        assertThat(row.error).isEqualTo(CountSubmissionError.EXCEPTIONS_FAILED)
        assertThat(row.message).contains("1 unexpected item could not be sent")
        assertThat(store.journalStatus["CNT-1"]).isEqualTo("SUBMITTED")
        assertThat(store.exceptions.single().sendError).isNotNull()
        assertThat(store.exceptions.single().sentAt).isNull()
    }

    @Test
    fun `offline while sending exceptions retries without re-sending the counts`() = runTest {
        store.queue("CNT-1")
        store.addException("CNT-1")
        transport.polls += TransportResult.Ok(ImportJobStatus.Succeeded)
        transport.exceptions = offline().toTransport()

        assertThat(runner.run("CNT-1")).isEqualTo(RunResult.RETRY)
        assertThat(store.rows["CNT-1"]!!.countsDone).isTrue()

        transport.exceptions = TransportResult.Ok(1)
        assertThat(runner.run("CNT-1")).isEqualTo(RunResult.DONE)

        assertThat(transport.startCalls).isEqualTo(1)
        assertThat(store.rows["CNT-1"]!!.state).isEqualTo(CountSubmissionState.SUCCEEDED)
    }

    @Test
    fun `a resend of exceptions only never touches the counts`() = runTest {
        store.queue("CNT-1")
        store.rows["CNT-1"] = store.rows["CNT-1"]!!.copy(countsDone = true, exceptionsOnly = true)
        store.addException("CNT-1")

        assertThat(runner.run("CNT-1")).isEqualTo(RunResult.DONE)

        assertThat(transport.startCalls).isEqualTo(0)
        assertThat(transport.sentExceptions).hasSize(1)
    }

    @Test
    fun `a partially accepted import keeps its warning through a restart before exceptions`() = runTest {
        store.queue("CNT-1")
        store.addException("CNT-1")
        transport.polls += TransportResult.Ok(ImportJobStatus.PartiallySucceeded("1 line not accepted"))
        transport.exceptions = offline().toTransport()
        runner.run("CNT-1")

        transport.exceptions = TransportResult.Ok(1)
        runner.run("CNT-1")

        val row = store.rows["CNT-1"]!!
        assertThat(row.state).isEqualTo(CountSubmissionState.PARTIAL)
        assertThat(row.message).isEqualTo("1 line not accepted")
    }

    @Test
    fun `a finished submission is left alone`() = runTest {
        store.queue("CNT-1")
        store.update("CNT-1", CountSubmissionState.SUCCEEDED)

        assertThat(runner.run("CNT-1")).isEqualTo(RunResult.DONE)
        assertThat(transport.startCalls).isEqualTo(0)
    }
}
