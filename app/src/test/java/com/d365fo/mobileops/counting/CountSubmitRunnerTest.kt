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
import com.d365fo.mobileops.util.AppFailure
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
    fun `another device holding the journal marks this device's claim lost, naming the holder`() = runTest {
        claims.claim("CNT-1", null)
        store.queue("CNT-1")
        transport.start = AppFailure.BusinessRule(
            "CNT-1 is being counted by Asha (001) on another device",
            d365ErrorCode = "CLAIM_LOST",
        ).toTransport()

        runner.run("CNT-1")

        assertThat(store.rows["CNT-1"]!!.message).contains("being counted by Asha (001) on another device")
        val claim = claimStore.get("CNT-1")!!
        assertThat(claim.lost).isTrue()
        assertThat(claim.lostReason).isEqualTo("CNT-1 is being counted by Asha (001) on another device. Counts are kept.")
    }

    @Test
    fun `a store that reviews journal counts sends them to the control center, not the journal`() = runTest {
        store.queue("CNT-1")
        transport.staged = TransportResult.Ok(com.d365fo.mobileops.data.counting.StagedCount("AAC-20261012-1", accepted = 3, duplicates = 1))

        assertThat(runner.run("CNT-1")).isEqualTo(RunResult.DONE)

        assertThat(transport.startCalls).isEqualTo(0)
        val row = store.rows["CNT-1"]!!
        assertThat(row.state).isEqualTo(CountSubmissionState.SUCCEEDED)
        assertThat(row.resultNote).contains("Sent for review")
        assertThat(row.resultNote).contains("1 counted twice")
    }

    @Test
    fun `staging refused by the supervisor's decision ends the submission`() = runTest {
        store.queue("CNT-1")
        transport.staged = TransportResult.Fail(CountSubmissionError.OTHER, "AAC-20261012-1 has been transferred by the supervisor")

        runner.run("CNT-1")

        assertThat(store.rows["CNT-1"]!!.state).isEqualTo(CountSubmissionState.FAILED)
        assertThat(transport.startCalls).isEqualTo(0)
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
        assertThat(row.message).contains("1 unexpected item not sent")
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
    fun `double-blind pass reports that a third count is needed`() = runTest {
        store.queue("CNT-1", mode = TransferMode.DIRECT)
        transport.direct = TransportResult.Ok(
            com.d365fo.mobileops.data.counting.DirectSubmitResult(
                acceptedLines = 3,
                thirdCountNeeded = true,
                passResult = "Pass 2 differs from pass 1 on 2 lines.",
            ),
        )

        assertThat(runner.run("CNT-1")).isEqualTo(RunResult.DONE)

        val row = store.rows["CNT-1"]!!
        assertThat(row.state).isEqualTo(CountSubmissionState.SUCCEEDED)
        assertThat(row.thirdCountNeeded).isTrue()
        assertThat(row.resultNote).isEqualTo("Pass 2 differs from pass 1 on 2 lines.")
    }

    @Test
    fun `recount by the previous counter is refused with a clear error`() = runTest {
        store.queue("CNT-1", mode = TransferMode.DIRECT)
        transport.direct = businessRule("RECOUNT_SAME_USER").toTransport()

        assertThat(runner.run("CNT-1")).isEqualTo(RunResult.DONE)

        val row = store.rows["CNT-1"]!!
        assertThat(row.error).isEqualTo(CountSubmissionError.SAME_COUNTER)
        assertThat(row.message).contains("Someone else must do this recount")
    }

    @Test
    fun `a non-countable line is confirmed, not counted - the confirmation goes with the exceptions`() = runTest {
        store.queue("CNT-1")
        store.confirmations += com.d365fo.mobileops.data.local.entity.JournalLineEntity(
            journalLineId = "CNT-1-4",
            journalId = "CNT-1",
            lineNumber = 4,
            itemId = "TRANSIT-ITEM",
            itemName = "Transit item",
            quantity = "",
            unitId = "",
            siteId = null,
            warehouseId = "WH1",
            locationId = "TRANSIT",
            batchId = null,
            serialId = null,
            inventDimId = null,
            capturedAt = 0L,
            locationType = "TRANSIT",
            verifyOnly = true,
            confirmedBy = "counter1",
            confirmedAt = 3_000L,
        )
        // Nothing countable on the journal: the DMF package would be empty.
        transport.start = businessRule("NOTHING_COUNTED").toTransport()

        assertThat(runner.run("CNT-1")).isEqualTo(RunResult.DONE)

        assertThat(transport.sentConfirmations.single().lineNumber).isEqualTo(4)
        assertThat(store.confirmations.single().confirmationSentAt).isNotNull()
        assertThat(store.rows["CNT-1"]!!.state).isEqualTo(CountSubmissionState.SUCCEEDED)
    }

    @Test
    fun `nothing counted and nothing to confirm is still refused`() = runTest {
        store.queue("CNT-1")
        transport.start = businessRule("NOTHING_COUNTED").toTransport()

        runner.run("CNT-1")

        assertThat(store.rows["CNT-1"]!!.error).isEqualTo(CountSubmissionError.NOTHING_COUNTED)
    }

    @Test
    fun `a finished submission is left alone`() = runTest {
        store.queue("CNT-1")
        store.update("CNT-1", CountSubmissionState.SUCCEEDED)

        assertThat(runner.run("CNT-1")).isEqualTo(RunResult.DONE)
        assertThat(transport.startCalls).isEqualTo(0)
    }
}
