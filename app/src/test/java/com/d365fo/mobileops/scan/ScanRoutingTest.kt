package com.d365fo.mobileops.scan

import com.d365fo.mobileops.presentation.common.scan.RejectReason
import com.d365fo.mobileops.presentation.common.scan.ScanContext
import com.d365fo.mobileops.presentation.common.scan.ScanIdentifier
import com.d365fo.mobileops.presentation.common.scan.ScanKind
import com.d365fo.mobileops.presentation.common.scan.ScanRoute
import com.d365fo.mobileops.presentation.common.scan.TransferDirection
import com.d365fo.mobileops.presentation.common.scan.routeScan
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Routing on Home versus inside a flow (redesign brief §3.2). */
class ScanRoutingTest {

    private val lookups = FakeScanLookups()
    private val identifier = ScanIdentifier(lookups)
    private suspend fun route(value: String, context: ScanContext) = routeScan(identifier.identify(scan(value)), context)

    @Nested
    inner class OnHome {
        @Test
        fun `a PO opens PO receiving`() = runTest {
            val r = route("BSP-PO-000161763", ScanContext.Home) as ScanRoute.OpenPoReceiving
            assertThat(r.purchId).isEqualTo("BSP-PO-000161763")
        }

        @Test
        fun `a transfer opens ship or receive by direction`() = runTest {
            assertThat((route("BSP-TO-000000043", ScanContext.Home) as ScanRoute.OpenTransfer).direction)
                .isEqualTo(TransferDirection.SHIP)
            assertThat((route("BSP-TO-000000044", ScanContext.Home) as ScanRoute.OpenTransfer).direction)
                .isEqualTo(TransferDirection.RECEIVE)
            // Not on the device: the screen asks which.
            assertThat((route("BSP-TO-000000999", ScanContext.Home) as ScanRoute.OpenTransfer).direction).isNull()
        }

        @Test
        fun `a bin starts a store count`() = runTest {
            val r = route("BIN-07", ScanContext.Home) as ScanRoute.StartStoreCount
            assertThat(r.bin.locationId).isEqualTo("A-01-02")
        }

        @Test
        fun `an item opens On-hand - EAN, GS1 or item number`() = runTest {
            listOf("5012345678900", "(01)09506000134352(10)24A17", "BSR-10113-0").forEach {
                assertThat(route(it, ScanContext.Home)).isInstanceOf(ScanRoute.OpenOnHand::class.java)
            }
        }

        @Test
        fun `a counting journal and a location open their screens`() = runTest {
            assertThat(route("BSP-JN-000552008", ScanContext.Home)).isInstanceOf(ScanRoute.OpenCountJournal::class.java)
            assertThat(route("A-01-02", ScanContext.Home)).isInstanceOf(ScanRoute.OpenLocation::class.java)
        }

        @Test
        fun `an unknown barcode is turned away`() = runTest {
            val r = route("ZZZ", ScanContext.Home) as ScanRoute.Reject
            assertThat(r.reason).isEqualTo(RejectReason.UNKNOWN)
        }
    }

    @Nested
    inner class InPoReceiving {
        private val ready = ScanContext.Flow(accepts = setOf(ScanKind.PURCHASE_ORDER), documentKind = ScanKind.PURCHASE_ORDER)
        private val onOrder = ScanContext.Flow(
            accepts = setOf(ScanKind.ITEM),
            documentKind = ScanKind.PURCHASE_ORDER,
            currentDocument = "BSP-PO-000161763",
            documentItemIds = setOf("BSR-11397-0", "BSR-20001-0"),
        )

        @Test
        fun `the PO scan is accepted while waiting for one`() = runTest {
            assertThat(route("BSP-PO-000161763", ready)).isInstanceOf(ScanRoute.Accept::class.java)
        }

        @Test
        fun `an item on the order advances the step`() = runTest {
            assertThat(route("(01)09506000134352(10)24A17", onOrder)).isInstanceOf(ScanRoute.Accept::class.java)
            assertThat(route("5012345678900", onOrder)).isInstanceOf(ScanRoute.Accept::class.java)
        }

        @Test
        fun `an item not on the order is a wrong item, nothing added`() = runTest {
            val r = route("BSR-10113-0", onOrder) as ScanRoute.Reject
            assertThat(r.reason).isEqualTo(RejectReason.NOT_ON_DOCUMENT)
            assertThat(r.title).isEqualTo("Wrong item")
            assertThat(r.line).contains("nothing was added")
        }

        @Test
        fun `a blocked item is turned away`() = runTest {
            val r = route("4006381333931", onOrder.copy(documentItemIds = null)) as ScanRoute.Reject
            assertThat(r.reason).isEqualTo(RejectReason.INACTIVE_ITEM)
        }

        @Test
        fun `another PO switches to it`() = runTest {
            val r = route("BSP-PO-000161764", onOrder) as ScanRoute.SwitchDocument
            assertThat(r.documentId).isEqualTo("BSP-PO-000161764")
        }

        @Test
        fun `the same PO again is accepted, not a switch`() = runTest {
            assertThat(route("BSP-PO-000161763", onOrder)).isInstanceOf(ScanRoute.Accept::class.java)
        }

        @Test
        fun `a transfer mid-receipt leaves for where Home would send it`() = runTest {
            val r = route("BSP-TO-000000044", onOrder) as ScanRoute.Leave
            assertThat(r.then).isInstanceOf(ScanRoute.OpenTransfer::class.java)
        }

        @Test
        fun `a location while items are expected says what to scan`() = runTest {
            val r = route("A-01-02", onOrder) as ScanRoute.Reject
            assertThat(r.reason).isEqualTo(RejectReason.NOT_EXPECTED)
            assertThat(r.title).isEqualTo("Scan an item")
            assertThat(r.line).isEqualTo("A-01-02 is a location")
        }

        @Test
        fun `an unknown barcode is turned away`() = runTest {
            assertThat((route("ZZZ", onOrder) as ScanRoute.Reject).reason).isEqualTo(RejectReason.UNKNOWN)
        }

        @Test
        fun `a free-text step takes what nothing on the device knows - a batch number`() = runTest {
            val batchStep = onOrder.copy(accepts = setOf(ScanKind.ITEM, ScanKind.LOCATION, ScanKind.BIN), acceptUnknown = true)
            assertThat(route("24A17", batchStep)).isInstanceOf(ScanRoute.Accept::class.java)
            // Another PO still switches, rather than being taken as the batch.
            assertThat(route("BSP-PO-000161764", batchStep)).isInstanceOf(ScanRoute.SwitchDocument::class.java)
        }

        @Test
        fun `a step can say what it is waiting for`() = runTest {
            val r = route("A-01-02", onOrder.copy(expecting = "Scan the item")) as ScanRoute.Reject
            assertThat(r.title).isEqualTo("Scan the item")
        }
    }

    @Nested
    inner class InStoreCount {
        private val inBin = ScanContext.Flow(
            accepts = setOf(ScanKind.ITEM),
            documentKind = ScanKind.BIN,
            currentDocument = "BIN-07",
        )

        @Test
        fun `items count without a line check`() = runTest {
            assertThat(route("BSR-10113-0", inBin)).isInstanceOf(ScanRoute.Accept::class.java)
        }

        @Test
        fun `the same bin label again is accepted - an empty bin settles at 0`() = runTest {
            assertThat(route("BIN-07", inBin)).isInstanceOf(ScanRoute.Accept::class.java)
        }

        @Test
        fun `the next bin label switches bins`() = runTest {
            lookups.bins["BIN-08"] = com.d365fo.mobileops.presentation.common.scan.BinRef("BIN-08", "A-01-02")
            val r = route("BIN-08", inBin) as ScanRoute.SwitchDocument
            assertThat(r.documentId).isEqualTo("BIN-08")
        }
    }
}
