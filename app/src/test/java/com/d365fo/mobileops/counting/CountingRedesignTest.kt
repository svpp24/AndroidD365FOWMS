package com.d365fo.mobileops.counting

import com.d365fo.mobileops.data.local.dao.CountLine
import com.d365fo.mobileops.data.local.entity.CountLineBinEntity
import com.d365fo.mobileops.data.repository.OrderItem
import com.d365fo.mobileops.domain.model.RawScan
import com.d365fo.mobileops.domain.model.ScanSource
import com.d365fo.mobileops.presentation.common.components.StatusKind
import com.d365fo.mobileops.presentation.common.scan.BinRef
import com.d365fo.mobileops.presentation.common.scan.ItemRef
import com.d365fo.mobileops.presentation.common.scan.RejectReason
import com.d365fo.mobileops.presentation.common.scan.ScanIdentity
import com.d365fo.mobileops.presentation.common.scan.ScanKind
import com.d365fo.mobileops.presentation.common.scan.ScanRoute
import com.d365fo.mobileops.presentation.common.scan.routeScan
import com.d365fo.mobileops.presentation.modules.cyclecounting.CountNotice
import com.d365fo.mobileops.presentation.modules.cyclecounting.CountStop
import com.d365fo.mobileops.presentation.modules.cyclecounting.CountingScanState
import com.d365fo.mobileops.presentation.modules.cyclecounting.CycleFlowInfo
import com.d365fo.mobileops.presentation.modules.cyclecounting.ScanMessage
import com.d365fo.mobileops.presentation.modules.cyclecounting.StopMode
import com.d365fo.mobileops.presentation.modules.cyclecounting.cycleCountBand
import com.d365fo.mobileops.presentation.modules.cyclecounting.cycleCountCard
import com.d365fo.mobileops.presentation.modules.cyclecounting.cycleFlowInfo
import com.d365fo.mobileops.presentation.modules.cyclecounting.isSettled
import com.d365fo.mobileops.presentation.modules.cyclecounting.walkingOrder
import com.d365fo.mobileops.presentation.modules.cyclecounting.store.LastCount
import com.d365fo.mobileops.presentation.modules.cyclecounting.store.PendingCount
import com.d365fo.mobileops.presentation.modules.cyclecounting.store.StoreCountDraft
import com.d365fo.mobileops.presentation.modules.cyclecounting.store.StoreCountLine
import com.d365fo.mobileops.presentation.modules.cyclecounting.store.StoreCountState
import com.d365fo.mobileops.presentation.modules.cyclecounting.store.addCount
import com.d365fo.mobileops.presentation.modules.cyclecounting.store.storeCountBand
import com.d365fo.mobileops.presentation.modules.cyclecounting.store.storeCountCard
import com.d365fo.mobileops.presentation.modules.cyclecounting.store.storeCountContext
import com.d365fo.mobileops.presentation.modules.cyclecounting.store.undoCount
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The redesigned counting screens' rules (phase 6): store count's +1 per scan, Undo and what each
 * step accepts; cycle count's walking order, settled bins, and what the band and card say.
 */
class CountingRedesignTest {

    private fun raw(value: String) = RawScan(value, ScanSource.HID_WEDGE)
    private fun binScan(bin: String, location: String? = "STOCK") = ScanIdentity.Bin(raw(bin), BinRef(bin, location))
    private fun itemScan(item: String) = ScanIdentity.Item(raw(item), ItemRef(item, "Name of $item"))

    @Nested
    inner class StoreCount {

        private fun line(id: String, item: String, qty: Double, bin: String = "A1", sent: Boolean = false, serial: String = "") =
            StoreCountLine(
                clientLineId = id, itemId = item, itemName = item, serialId = serial,
                locationId = "STOCK", quantity = qty, unitId = "pcs", countedAt = 0L, sent = sent, binId = bin,
            )

        private fun counting(binId: String = "A1", lines: List<StoreCountLine> = emptyList()) = StoreCountState(
            warehouseId = "ABB",
            draft = StoreCountDraft(warehouseId = "ABB", masterId = "ABB-1", stageId = "S1", lines = lines, started = true),
            locationId = "STOCK",
            binId = binId,
            binsRequired = true,
        )

        @Test
        fun `a second scan of the same item here adds one to its line and moves it to the top`() {
            val lines = listOf(line("b", "BSR-1010-0", 1.0), line("a", "BSR-1009-0", 2.0))
            val (after, last) = addCount(lines, line("new", "BSR-1009-0", 1.0))
            assertThat(after.map { it.clientLineId to it.quantity }).containsExactly("a" to 3.0, "b" to 1.0).inOrder()
            assertThat(last).isEqualTo(LastCount("a", before = 2.0))
        }

        @Test
        fun `a new item, a sent line, a line being sent or another bin each start a new line`() {
            val lines = listOf(line("sent", "X", 1.0, sent = true), line("flying", "Y", 1.0), line("other", "Z", 1.0, bin = "A2"))
            assertThat(addCount(lines, line("n1", "X", 1.0)).second.before).isNull()
            assertThat(addCount(lines, line("n2", "Y", 1.0), inFlight = setOf("flying")).second.before).isNull()
            assertThat(addCount(lines, line("n3", "Z", 1.0)).second.before).isNull()
            assertThat(addCount(lines, line("n4", "W", 1.0)).first.first().clientLineId).isEqualTo("n4")
        }

        @Test
        fun `two serials are two lines, never one line of two`() {
            val (after, _) = addCount(listOf(line("s1", "SER", 1.0, serial = "111")), line("s2", "SER", 1.0, serial = "222"))
            assertThat(after).hasSize(2)
        }

        @Test
        fun `undo takes back the last +1, or the line it made`() {
            val lines = listOf(line("a", "X", 3.0))
            assertThat(undoCount(lines, LastCount("a", 2.0)).single().quantity).isEqualTo(2.0)
            assertThat(undoCount(lines, LastCount("a", null))).isEmpty()
            // Already with the supervisor: nothing to take back.
            assertThat(undoCount(listOf(line("a", "X", 3.0, sent = true)), LastCount("a", null))).hasSize(1)
        }

        @Test
        fun `before Start a bin label starts the count`() {
            val start = StoreCountState(warehouseId = "ABB", binsRequired = true)
            assertThat(routeScan(binScan("A1"), storeCountContext(start))).isInstanceOf(ScanRoute.Accept::class.java)
            val item = routeScan(itemScan("BSR-1009-0"), storeCountContext(start))
            assertThat((item as ScanRoute.Reject).reason).isEqualTo(RejectReason.NOT_EXPECTED)
        }

        @Test
        fun `counting a bin, items are accepted and the next bin switches`() {
            val context = storeCountContext(counting())
            assertThat(routeScan(itemScan("BSR-1009-0"), context)).isInstanceOf(ScanRoute.Accept::class.java)
            assertThat((routeScan(binScan("A2"), context) as ScanRoute.SwitchDocument).documentId).isEqualTo("A2")
            assertThat(routeScan(binScan("A1"), context)).isInstanceOf(ScanRoute.Accept::class.java)
            val unknown = routeScan(ScanIdentity.Unknown(raw("zzz")), context)
            assertThat(unknown).isInstanceOf(ScanRoute.Accept::class.java) // looked up online, Bad band if not found
        }

        @Test
        fun `with no bin chosen an item is turned away - a Warn, nothing added`() {
            val route = routeScan(itemScan("BSR-1009-0"), storeCountContext(counting(binId = "")))
            assertThat((route as ScanRoute.Reject).reason).isEqualTo(RejectReason.NOT_EXPECTED)
            assertThat(route.title).isEqualTo("Scan a bin label")
        }

        @Test
        fun `a batch step takes any scan as the batch`() {
            val item = OrderItem(
                itemId = "BAT", itemName = "Batched", purchUnit = "pcs", inventUnit = "pcs",
                configActive = false, sizeActive = false, colorActive = false, styleActive = false,
                batchActive = true, serialActive = false, minQty = 0.0, maxQty = 0.0, multipleQty = 0.0, standardQty = 0.0,
                variants = emptyList(), scannedVariant = null,
            )
            val context = storeCountContext(counting().copy(pending = PendingCount(item)))
            assertThat(context.acceptUnknown).isTrue()
            assertThat(context.documentKind).isNull()
        }

        @Test
        fun `the start card shows the count number and where it starts`() {
            val start = StoreCountState(
                warehouseId = "ABB",
                draft = StoreCountDraft(warehouseId = "ABB", masterId = "ABB-20261109-1"),
                startLocation = "STOCK",
            )
            val card = storeCountCard(start)
            assertThat(card.code).isEqualTo("ABB-20261109-1")
            assertThat(card.location).isEqualTo("STOCK")
            assertThat(card.label).contains("tap to change")
            assertThat(storeCountBand(start).kind).isEqualTo(StatusKind.Idle)
        }

        @Test
        fun `counting, the card shows the last line here and its count`() {
            val s = counting(lines = listOf(line("a", "BSR-1009-0", 3.0), line("b", "BSR-1010-0", 1.0, bin = "A2")))
                .copy(last = LastCount("a", 2.0))
            val card = storeCountCard(s)
            assertThat(card.code).isEqualTo("BSR-1009-0")
            assertThat(card.quantity).isEqualTo("3")
            assertThat(card.location).isEqualTo("STOCK › A1")
            assertThat(card.label).contains("1 line here")
            assertThat(s.waitingToSend).isEqualTo(1)
        }

        @Test
        fun `a notice wins the band`() {
            val s = counting().copy(notice = CountNotice(StatusKind.Bad, "Not recognised", "zzz - nothing was added"))
            assertThat(storeCountBand(s).kind).isEqualTo(StatusKind.Bad)
            assertThat(storeCountBand(counting()).title).isEqualTo("Scan items")
        }
    }

    @Nested
    inner class CycleCount {

        private fun line(id: String, location: String?, counted: String? = null, item: String = "BSR-1009-0") = CountLine(
            journalLineId = id, journalId = "J", lineNumber = 1, itemId = item, itemName = item,
            locationId = location, batchId = null, serialId = null, countedQuantity = counted,
            markedCounted = false, countedBy = null, countedAt = null,
        )

        private val bins = listOf(BinRef("B2", "L-01"), BinRef("B1", "L-01"), BinRef("C1", "L-02"), BinRef("X9", "OTHER"))

        @Test
        fun `bins are walked location by location, in order`() {
            val order = walkingOrder(listOf("L-02", "L-01", "L-01"), bins, StopMode.BIN)
            assertThat(order).containsExactly(
                CountStop("L-01", "B1"), CountStop("L-01", "B2"), CountStop("L-02", "C1"),
            ).inOrder()
        }

        @Test
        fun `a bin with no location belongs to every location`() {
            val order = walkingOrder(listOf("L-01", "L-02"), listOf(BinRef("ANY", null)), StopMode.BIN)
            assertThat(order).containsExactly(CountStop("L-01", "ANY"), CountStop("L-02", "ANY")).inOrder()
        }

        @Test
        fun `without bins it walks locations, or the journal as a whole`() {
            assertThat(walkingOrder(listOf("b", "a"), emptyList(), StopMode.LOCATION)).containsExactly(CountStop("A", null), CountStop("B", null)).inOrder()
            assertThat(walkingOrder(listOf("a"), emptyList(), StopMode.WHOLE)).containsExactly(CountStop(null, null))
        }

        @Test
        fun `a bin is settled when every line at its location has a row for it`() {
            val lines = listOf(line("1", "L-01"), line("2", "L-01"), line("3", "L-02"))
            val stop = CountStop("L-01", "B1")
            assertThat(isSettled(stop, lines, listOf(CountLineBinEntity("1", "B1", "J", "3")))).isFalse()
            assertThat(isSettled(stop, lines, listOf(CountLineBinEntity("1", "B1", "J", "3"), CountLineBinEntity("2", "B1", "J", "0")))).isTrue()
            // Another bin's row says nothing about this one.
            assertThat(isSettled(stop, lines, listOf(CountLineBinEntity("1", "B2", "J", "3"), CountLineBinEntity("2", "B2", "J", "0")))).isFalse()
        }

        @Test
        fun `a location is settled when its lines are counted`() {
            val lines = listOf(line("1", "L-01", counted = "0"), line("2", "L-02"))
            assertThat(isSettled(CountStop("L-01", null), lines, emptyList())).isTrue()
            assertThat(isSettled(CountStop("L-02", null), lines, emptyList())).isFalse()
        }

        @Test
        fun `go to the next open bin after this one, wrapping round to one skipped`() {
            val stops = listOf(CountStop("L", "A"), CountStop("L", "B"), CountStop("L", "C"))
            val info = CycleFlowInfo(loaded = true, mode = StopMode.BIN, stops = stops, settled = setOf(stops[1]))
            assertThat(info.nextAfter(null)).isEqualTo(stops[0])
            assertThat(info.nextAfter(stops[0])).isEqualTo(stops[2])
            assertThat(info.nextAfter(stops[2])).isEqualTo(stops[0])
            assertThat(info.copy(settled = stops.toSet()).nextAfter(stops[0])).isNull()
        }

        @Test
        fun `before a bin is scanned the band and card say where to go`() {
            val info = cycleFlowInfo(listOf(line("1", "L-01")), emptyList(), bins, StopMode.BIN, location = "L-01", bin = null)
            val state = CountingScanState(loaded = true, journalDescription = "Cycle count")
            assertThat(cycleCountBand(state, info).title).isEqualTo("Go to B1")
            val card = cycleCountCard(state, info)
            assertThat(card.code).isEqualTo("B1")
            assertThat(card.label).isEqualTo("Go to · 1 of 2")
            assertThat(card.location).isEqualTo("L-01")
            assertThat(card.quantity).isNull() // blind: no stock figure
        }

        @Test
        fun `in a bin the card shows the last item counted there and its count`() {
            val counted = line("1", "L-01", counted = "2")
            val info = cycleFlowInfo(listOf(counted), emptyList(), bins, StopMode.BIN, "L-01", "B1", counted, "2")
            val card = cycleCountCard(CountingScanState(loaded = true), info)
            assertThat(card.code).isEqualTo("BSR-1009-0")
            assertThat(card.quantity).isEqualTo("2")
            assertThat(card.location).isEqualTo("B1")
            assertThat(cycleCountBand(CountingScanState(loaded = true), info).title).isEqualTo("Counting B1")
        }

        @Test
        fun `every bin settled - all counted`() {
            val lines = listOf(line("1", "L-01"))
            val rows = listOf(CountLineBinEntity("1", "B1", "J", "0"), CountLineBinEntity("1", "B2", "J", "1"))
            val info = cycleFlowInfo(lines, rows, bins, StopMode.BIN, "L-01", null)
            assertThat(info.allSettled).isTrue()
            assertThat(cycleCountBand(CountingScanState(loaded = true), info).title).isEqualTo("All counted")
        }

        @Test
        fun `a wrong item is a Bad band, a step it isn't is a Warn, a +1 is Ok with Undo`() {
            val info = CycleFlowInfo(loaded = true)
            fun band(m: ScanMessage) = cycleCountBand(CountingScanState(loaded = true, message = m), info)
            assertThat(band(ScanMessage("x", isError = true, title = "Wrong item")).kind).isEqualTo(StatusKind.Bad)
            assertThat(band(ScanMessage("x", isError = true, warn = true)).kind).isEqualTo(StatusKind.Warn)
            val ok = band(ScanMessage("x", isError = false, undoable = true, title = "+1 BSR-1009-0", line = "3 counted in B1"))
            assertThat(ok.kind).isEqualTo(StatusKind.Ok)
            assertThat(ok.undoable).isTrue()
            assertThat(ok.title).isEqualTo("+1 BSR-1009-0")
        }

        @Test
        fun `the router turns an unknown barcode away unless the flow looks it up`() {
            val route = routeScan(ScanIdentity.Unknown(raw("zzz")), com.d365fo.mobileops.presentation.common.scan.ScanContext.Flow(accepts = setOf(ScanKind.ITEM)))
            assertThat((route as ScanRoute.Reject).reason).isEqualTo(RejectReason.UNKNOWN)
        }
    }
}
