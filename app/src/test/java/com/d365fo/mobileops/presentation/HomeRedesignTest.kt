package com.d365fo.mobileops.presentation

import com.d365fo.mobileops.data.local.entity.FailedSyncEntity
import com.d365fo.mobileops.data.local.entity.FlowType
import com.d365fo.mobileops.data.local.entity.SyncOperation
import com.d365fo.mobileops.data.repository.CountingActivity
import com.d365fo.mobileops.data.sync.PostQueueEntry
import com.d365fo.mobileops.domain.model.RawScan
import com.d365fo.mobileops.domain.model.ScanSource
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import com.d365fo.mobileops.domain.repository.OnHandLine
import com.d365fo.mobileops.presentation.common.ActivityProblem
import com.d365fo.mobileops.presentation.common.ActivitySent
import com.d365fo.mobileops.presentation.common.HOME_TASK_WORKFLOWS
import com.d365fo.mobileops.presentation.common.HomeScanTarget
import com.d365fo.mobileops.presentation.common.MODULES
import com.d365fo.mobileops.presentation.common.Routes
import com.d365fo.mobileops.presentation.common.activityModel
import com.d365fo.mobileops.presentation.common.homeScanTarget
import com.d365fo.mobileops.presentation.common.homeTasks
import com.d365fo.mobileops.presentation.common.scan.BinRef
import com.d365fo.mobileops.presentation.common.scan.ItemRef
import com.d365fo.mobileops.presentation.common.scan.RejectReason
import com.d365fo.mobileops.presentation.common.scan.ScanIdentity
import com.d365fo.mobileops.presentation.common.scan.ScanKind
import com.d365fo.mobileops.presentation.common.scan.ScanRoute
import com.d365fo.mobileops.presentation.common.scan.TransferDirection
import com.d365fo.mobileops.presentation.modules.stepflow.formatOnHandQty
import com.d365fo.mobileops.presentation.modules.stepflow.onHandTotals
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Home, On-hand and Activity in the redesign (brief §3.2 and §4, phase 8). */
class HomeRedesignTest {

    private fun raw(value: String) = RawScan(value, ScanSource.IMAGER_BROADCAST)
    private val everything = WarehouseWorkflow.entries.toSet()

    private fun doc(id: String, kind: ScanKind, direction: TransferDirection? = null) =
        ScanIdentity.Document(raw(id), kind, id, direction)

    @Nested
    inner class ScanOnHome {
        @Test
        fun `a PO opens receiving with the PO handed over`() {
            val t = homeScanTarget(ScanRoute.OpenPoReceiving(doc("BSP-PO-000161765", ScanKind.PURCHASE_ORDER), "BSP-PO-000161765"), everything)
            assertThat(t).isEqualTo(HomeScanTarget.Resume(FlowType.RECEIVING, "BSP-PO-000161765", Routes.RECEIVE_FLOW))
        }

        @Test
        fun `a transfer opens ship or receive by its direction`() {
            val id = doc("BSP-TO-1", ScanKind.TRANSFER_ORDER)
            assertThat(homeScanTarget(ScanRoute.OpenTransfer(id, "BSP-TO-1", TransferDirection.SHIP), everything))
                .isEqualTo(HomeScanTarget.Resume(FlowType.TRANSFER_SHIP, "BSP-TO-1", Routes.TRANSFER_SHIP))
            assertThat(homeScanTarget(ScanRoute.OpenTransfer(id, "BSP-TO-1", TransferDirection.RECEIVE), everything))
                .isEqualTo(HomeScanTarget.Resume(FlowType.TRANSFER_RECEIVE, "BSP-TO-1", Routes.TRANSFER_RECEIVE))
        }

        @Test
        fun `a transfer of unknown direction asks - unless only one direction is on the menu`() {
            val route = ScanRoute.OpenTransfer(doc("BSP-TO-9", ScanKind.TRANSFER_ORDER), "BSP-TO-9", null)
            assertThat(homeScanTarget(route, everything))
                .isEqualTo(HomeScanTarget.AskTransferDirection("BSP-TO-9", listOf(TransferDirection.SHIP, TransferDirection.RECEIVE)))
            assertThat(homeScanTarget(route, everything - WarehouseWorkflow.TRANSFER_SHIP))
                .isEqualTo(HomeScanTarget.Resume(FlowType.TRANSFER_RECEIVE, "BSP-TO-9", Routes.TRANSFER_RECEIVE))
            val none = homeScanTarget(route, everything - WarehouseWorkflow.TRANSFER_SHIP - WarehouseWorkflow.TRANSFER_RECEIVE)
            assertThat(none).isInstanceOf(HomeScanTarget.Notice::class.java)
        }

        @Test
        fun `a bin starts a store count with the bin given again`() {
            val bin = ScanIdentity.Bin(raw("BIN-07"), BinRef("BIN-07", "AAC"))
            assertThat(homeScanTarget(ScanRoute.StartStoreCount(bin, bin.bin), everything))
                .isEqualTo(HomeScanTarget.Replay(Routes.COUNTING_STORE, "BIN-07"))
        }

        @Test
        fun `a journal opens counting on it when downloaded, else the journal list`() {
            val route = ScanRoute.OpenCountJournal(doc("BSP-JN-1", ScanKind.COUNT_JOURNAL), "BSP-JN-1")
            assertThat(homeScanTarget(route, everything, journalOnDevice = true))
                .isEqualTo(HomeScanTarget.Replay(Routes.countingCapture("BSP-JN-1"), "BSP-JN-1"))
            assertThat(homeScanTarget(route, everything, journalOnDevice = false))
                .isEqualTo(HomeScanTarget.Replay(Routes.countingJournals(), "BSP-JN-1"))
        }

        @Test
        fun `a location opens location inquiry`() {
            val loc = ScanIdentity.Location(raw("A-01-02"), "A-01-02")
            assertThat(homeScanTarget(ScanRoute.OpenLocation(loc, "A-01-02"), everything))
                .isEqualTo(HomeScanTarget.Replay(Routes.INQUIRY_LOCATION, "A-01-02"))
        }

        @Test
        fun `an item opens On-hand - not a menu module, so always`() {
            val item = ScanIdentity.Item(raw("6291103652447"), ItemRef("BSR-1009-0", "Item"))
            assertThat(homeScanTarget(ScanRoute.OpenOnHand(item), emptySet()))
                .isEqualTo(HomeScanTarget.OnHand("BSR-1009-0"))
        }

        @Test
        fun `a module not on the menu opens nothing and says why`() {
            val t = homeScanTarget(
                ScanRoute.OpenPoReceiving(doc("BSP-PO-1", ScanKind.PURCHASE_ORDER), "BSP-PO-1"),
                everything - WarehouseWorkflow.PO_RECEIVING,
            ) as HomeScanTarget.Notice
            assertThat(t.warn).isTrue()
            assertThat(t.line).contains("PO receiving")
        }

        @Test
        fun `an unknown barcode is a Bad notice with the router's words`() {
            val unknown = ScanIdentity.Unknown(raw("XYZ"))
            val t = homeScanTarget(ScanRoute.Reject(unknown, RejectReason.UNKNOWN, "Not recognised", "XYZ isn't an item"), everything)
            assertThat(t).isEqualTo(HomeScanTarget.Notice("Not recognised", "XYZ isn't an item"))
        }
    }

    @Nested
    inner class Tiles {
        @Test
        fun `four tiles in order, with counts, only those on the menu`() {
            val counts = mapOf(WarehouseWorkflow.PO_RECEIVING to "3 due")
            val all = homeTasks(MODULES, counts)
            assertThat(all.map { it.title }).containsExactly("Receive", "Ship", "Count", "Create").inOrder()
            assertThat(all.first().count).isEqualTo("3 due")
            assertThat(all.last().modules.map { it.workflow })
                .containsExactly(WarehouseWorkflow.PO_CREATE, WarehouseWorkflow.TO_CREATE, WarehouseWorkflow.PURCHASE_RETURN).inOrder()

            val noShip = homeTasks(MODULES.filter { it.workflow != WarehouseWorkflow.TRANSFER_SHIP }, counts)
            assertThat(noShip.map { it.title }).doesNotContain("Ship")
        }

        @Test
        fun `transfer receive is not a tile, so it stays in the list under them`() {
            assertThat(WarehouseWorkflow.TRANSFER_RECEIVE).isNotIn(HOME_TASK_WORKFLOWS)
        }
    }

    @Nested
    inner class OnHand {
        private fun line(wh: String, available: Int, onHand: Int, reserved: Int) = OnHandLine(
            "BSR-1009-0", "S1", wh, BigDecimal(onHand), BigDecimal(available), BigDecimal(reserved), BigDecimal.ZERO, BigDecimal(available),
        )

        @Test
        fun `totals add up the warehouses`() {
            val t = onHandTotals(listOf(line("AAC", 5, 7, 2), line("ABB", 1, 1, 0)))
            assertThat(t.available).isEqualTo(BigDecimal(6))
            assertThat(t.onHand).isEqualTo(BigDecimal(8))
            assertThat(t.reserved).isEqualTo(BigDecimal(2))
            assertThat(onHandTotals(emptyList()).available).isEqualTo(BigDecimal.ZERO)
        }

        @Test
        fun `quantities read without trailing zeros`() {
            assertThat(formatOnHandQty(BigDecimal("12.000"))).isEqualTo("12")
            assertThat(formatOnHandQty(BigDecimal("2.50"))).isEqualTo("2.5")
            assertThat(formatOnHandQty(BigDecimal("1E+2"))).isEqualTo("100")
        }
    }

    @Nested
    inner class Activity {
        private fun failure(id: String, order: String, acknowledged: Boolean = false, at: Long = 0) = FailedSyncEntity(
            failureId = id, operation = SyncOperation.POST_PRODUCT_RECEIPT, aggregateId = order, payloadJson = "{}",
            workerId = "002", deviceId = "d", originallyQueuedAt = 0, failedAt = at, attempts = 3,
            errorCode = null, errorMessage = "Period closed", serverDetail = null, acknowledged = acknowledged,
        )

        private fun queue(order: String, status: String) = PostQueueEntry(
            sourceId = order, documentType = "Registration", status = status, errorMessage = null,
            receivedUtc = "2026-11-09T08:00:00Z", processedUtc = null, userName = "002", linesJson = null,
        )

        private fun counting(key: String, inProgress: Boolean = false, failed: Boolean = false, warning: Boolean = false) = CountingActivity(
            key = key, journalId = "BSP-JN-$key", title = "Submit count", description = null, label = "Done",
            percent = null, inProgress = inProgress, failed = failed, warning = warning, startedAt = 0,
        )

        @Test
        fun `problems first - failed postings newest first, then counting failures`() {
            val m = activityModel(
                failures = listOf(failure("a", "PO-1", at = 1), failure("b", "PO-2", at = 2)),
                waiting = emptyList(),
                queue = emptyList(),
                counting = listOf(counting("1", failed = true), counting("2")),
            )
            assertThat(m.problems.map { it.key }).containsExactly("posting:b", "posting:a", "count:1").inOrder()
            assertThat(m.sent).containsExactly(ActivitySent.Count(counting("2")))
        }

        @Test
        fun `a refusal D365FO reports is shown once, as the failure that can be retried`() {
            val m = activityModel(
                failures = listOf(failure("a", "PO-1")),
                waiting = emptyList(),
                queue = listOf(queue("PO-1", "Failed"), queue("PO-9", "Failed"), queue("PO-3", "Registered")),
                counting = emptyList(),
            )
            assertThat(m.problems.map { it::class }).containsExactly(ActivityProblem.Posting::class, ActivityProblem.Refused::class).inOrder()
            assertThat((m.problems[1] as ActivityProblem.Refused).entry.sourceId).isEqualTo("PO-9")
            assertThat(m.sent.map { (it as ActivitySent.Queue).entry.sourceId }).containsExactly("PO-3")
        }

        @Test
        fun `dismissed failures leave the problems but stay listed`() {
            val m = activityModel(listOf(failure("a", "PO-1", acknowledged = true)), emptyList(), emptyList(), emptyList())
            assertThat(m.problems).isEmpty()
            assertThat(m.dismissed.map { it.failureId }).containsExactly("a")
        }

        @Test
        fun `pending rows are sent but not done, running counts are apart`() {
            val m = activityModel(emptyList(), emptyList(), listOf(queue("PO-1", "Pending")), listOf(counting("1", inProgress = true)))
            assertThat((m.sent.single() as ActivitySent.Queue).pending).isTrue()
            assertThat(m.running.map { it.key }).containsExactly("1")
            assertThat(m.problems).isEmpty()
        }

        @Test
        fun `a counting warning is a problem, but only a Warn one`() {
            val m = activityModel(emptyList(), emptyList(), emptyList(), listOf(counting("1", warning = true)))
            assertThat((m.problems.single() as ActivityProblem.Count).warningOnly).isTrue()
        }
    }
}
