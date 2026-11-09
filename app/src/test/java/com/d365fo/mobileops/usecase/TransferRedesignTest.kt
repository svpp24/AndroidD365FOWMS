package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.data.repository.StockRow
import com.d365fo.mobileops.domain.model.TransferCapture
import com.d365fo.mobileops.domain.model.TransferMode
import com.d365fo.mobileops.domain.model.TransferOrderLine
import com.d365fo.mobileops.domain.model.TransferOrderLookup
import com.d365fo.mobileops.domain.model.TransferStep
import com.d365fo.mobileops.presentation.common.components.StatusKind
import com.d365fo.mobileops.presentation.modules.transfer.CapturedTransferLine
import com.d365fo.mobileops.presentation.modules.transfer.OutOfStock
import com.d365fo.mobileops.presentation.modules.transfer.ShipPick
import com.d365fo.mobileops.presentation.modules.transfer.TransferFlowState
import com.d365fo.mobileops.presentation.modules.transfer.TransferNotice
import com.d365fo.mobileops.presentation.modules.transfer.planShipPick
import com.d365fo.mobileops.presentation.modules.transfer.transferBand
import com.d365fo.mobileops.presentation.modules.transfer.transferCardFor
import com.d365fo.mobileops.presentation.modules.transfer.transferComplete
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The redesigned transfer screens' rules (phase 5): where a shipped line is picked from, when a
 * transfer counts as done (and so ships or posts by itself), and what the band and card say.
 */
class TransferRedesignTest {

    private fun line(number: Double, item: String, remaining: String) = TransferOrderLine(
        lineNumber = number,
        itemId = item,
        itemName = "Name of $item",
        unitId = "pcs",
        transferQuantity = BigDecimal(remaining),
        shippedQuantity = BigDecimal.ZERO,
        receivedQuantity = BigDecimal.ZERO,
        remainingQuantity = BigDecimal(remaining),
        fromSiteId = "1",
        fromWarehouseId = "AAC",
        toSiteId = "2",
        toWarehouseId = "ABB",
        locationId = null,
        batchRequired = false,
        serialRequired = false,
        locationRequired = true,
    )

    private val lines = listOf(line(1.0, "BSR-A", "5"), line(2.0, "BSR-B", "3"))
    private val order = TransferOrderLookup(
        transferId = "BSP-TO-000000001",
        transferStatus = "Created",
        actionable = true,
        blockReason = null,
        fromSiteId = "1",
        fromWarehouseId = "AAC",
        toSiteId = "2",
        toWarehouseId = "ABB",
        outstandingLineCount = 2,
        linesInline = true,
        lines = lines,
    )

    private fun onOrder(mode: TransferMode = TransferMode.SHIP) = TransferFlowState(
        step = TransferStep.ITEM,
        capture = TransferCapture(transferId = order.transferId, mode = mode, fromWarehouseId = "AAC", toWarehouseId = "ABB", defaultLocationId = "RECV"),
        order = order,
        openLines = lines,
    )

    private fun banked(number: Double, item: String, qty: String) =
        CapturedTransferLine(number, item, "Name of $item", BigDecimal(qty), "pcs", maxQuantity = BigDecimal(qty))

    private fun remaining(s: TransferFlowState) = { l: TransferOrderLine ->
        (l.remainingQuantity - s.captured.filter { it.lineNumber == l.lineNumber }.fold(BigDecimal.ZERO) { t, c -> t + c.quantity })
            .coerceAtLeast(BigDecimal.ZERO)
    }

    private fun stock(location: String, available: Double, batch: String = "") = StockRow(batch, "", available, location)

    private fun plan(
        rows: List<StockRow>,
        preferred: String? = null,
        scanned: Boolean = false,
        batchTracked: Boolean = false,
        lineBatch: String? = null,
        labelBatch: String? = null,
    ) = planShipPick(rows, preferred, scanned, locationTracked = true, batchTracked = batchTracked, lineBatch = lineBatch, labelBatch = labelBatch)

    // -- planShipPick ---------------------------------------------------------------------------

    @Test
    fun `the only location with stock is picked, with its quantity`() {
        val p = plan(listOf(stock("A-01", 4.0), stock("B-02", 0.0)))
        assertThat(p).isEqualTo(ShipPick.Ready("A-01", null, BigDecimal.valueOf(4.0)))
    }

    @Test
    fun `no stock anywhere is out of stock`() {
        assertThat(plan(listOf(stock("A-01", 0.0)))).isEqualTo(ShipPick.OutOfStock(null))
        assertThat(plan(emptyList())).isEqualTo(ShipPick.OutOfStock(null))
    }

    @Test
    fun `the default issue location is kept when it has stock`() {
        val p = plan(listOf(stock("A-01", 4.0), stock("ISSUE", 2.0)), preferred = "issue")
        assertThat((p as ShipPick.Ready).locationId).isEqualTo("ISSUE")
    }

    @Test
    fun `a default location without stock gives way to the one that has it`() {
        val p = plan(listOf(stock("A-01", 4.0)), preferred = "ISSUE")
        assertThat((p as ShipPick.Ready).locationId).isEqualTo("A-01")
    }

    @Test
    fun `several locations with stock are offered, most first`() {
        val p = plan(listOf(stock("A-01", 1.0), stock("B-02", 6.0))) as ShipPick.Ready
        assertThat(p.locationId).isNull()
        assertThat(p.askLocations.map { it.first }).containsExactly("B-02", "A-01").inOrder()
    }

    @Test
    fun `a scanned bin without stock is out of stock there, not somewhere else`() {
        val p = plan(listOf(stock("A-01", 4.0)), preferred = "C-03", scanned = true)
        assertThat(p).isEqualTo(ShipPick.OutOfStock("C-03"))
    }

    @Test
    fun `the one batch at the location is taken`() {
        val p = plan(listOf(stock("A-01", 3.0, "B1")), batchTracked = true) as ShipPick.Ready
        assertThat(p.batchId).isEqualTo("B1")
        assertThat(p.available).isEqualTo(BigDecimal.valueOf(3.0))
    }

    @Test
    fun `two batches at the location ask, unless the line was made for one of them`() {
        val rows = listOf(stock("A-01", 3.0, "B1"), stock("A-01", 2.0, "B2"))
        assertThat((plan(rows, batchTracked = true) as ShipPick.Ready).batchId).isNull()
        val p = plan(rows, batchTracked = true, lineBatch = "b2") as ShipPick.Ready
        assertThat(p.batchId).isEqualTo("B2")
        assertThat(p.available).isEqualTo(BigDecimal.valueOf(2.0))
    }

    @Test
    fun `a label's batch narrows the stock but is left to the batch step`() {
        val rows = listOf(stock("A-01", 3.0, "B1"), stock("B-02", 2.0, "B2"))
        val p = plan(rows, batchTracked = true, labelBatch = "B2") as ShipPick.Ready
        assertThat(p.locationId).isEqualTo("B-02")
        assertThat(p.batchId).isNull()
    }

    @Test
    fun `an item without locations takes the warehouse total`() {
        val p = planShipPick(listOf(stock("", 2.0), stock("", 1.0)), null, false, false, false, null, null)
        assertThat(p).isEqualTo(ShipPick.Ready(null, null, BigDecimal.valueOf(3.0)))
    }

    // -- transferComplete ----------------------------------------------------------------------

    @Test
    fun `nothing banked is never complete`() {
        assertThat(transferComplete(onOrder(), remaining(onOrder()))).isFalse()
    }

    @Test
    fun `complete once every line is banked in full`() {
        val one = onOrder().copy(captured = listOf(banked(1.0, "BSR-A", "5")))
        assertThat(transferComplete(one, remaining(one))).isFalse()
        val both = one.copy(captured = one.captured + banked(2.0, "BSR-B", "3"))
        assertThat(transferComplete(both, remaining(both))).isTrue()
    }

    @Test
    fun `a skipped line is not waited for`() {
        val s = onOrder().copy(captured = listOf(banked(1.0, "BSR-A", "5")), setAside = setOf(2.0))
        assertThat(transferComplete(s, remaining(s))).isTrue()
    }

    @Test
    fun `a line shipped short because of stock is not waited for`() {
        val s = onOrder().copy(
            captured = listOf(banked(1.0, "BSR-A", "2"), banked(2.0, "BSR-B", "3")),
            setAside = setOf(1.0),
        )
        assertThat(transferComplete(s, remaining(s))).isTrue()
    }

    @Test
    fun `a large order counts lines done against the server's count`() {
        val big = onOrder().copy(order = order.copy(linesInline = false, lines = emptyList()), openLines = emptyList())
        val one = big.copy(captured = listOf(banked(1.0, "BSR-A", "5")))
        assertThat(transferComplete(one, remaining(one))).isFalse()
        val skipped = one.copy(setAside = setOf(2.0))
        assertThat(transferComplete(skipped, remaining(skipped))).isTrue()
    }

    // -- band and card --------------------------------------------------------------------------

    @Test
    fun `ready, then the transfer found`() {
        val ready = transferBand(TransferFlowState(capture = TransferCapture(mode = TransferMode.SHIP)))
        assertThat(ready.kind).isEqualTo(StatusKind.Idle)
        assertThat(ready.line).isEqualTo("Scan a transfer order to ship")
        val found = transferBand(onOrder())
        assertThat(found.kind).isEqualTo(StatusKind.Info)
        assertThat(found.title).isEqualTo(order.transferId)
        assertThat(found.line).isEqualTo("AAC → ABB · 2 lines to ship")
    }

    @Test
    fun `a wrong item is a Bad band saying nothing was added`() {
        val band = transferBand(onOrder().copy(error = "BSR-Z is not on this order"))
        assertThat(band.kind).isEqualTo(StatusKind.Bad)
        assertThat(band.line).isEqualTo("Nothing was added")
    }

    @Test
    fun `out of stock is a Warn band naming the item`() {
        val band = transferBand(onOrder().copy(outOfStock = OutOfStock(2.0, "BSR-B", "AAC")))
        assertThat(band.kind).isEqualTo(StatusKind.Warn)
        assertThat(band.title).isEqualTo("Out of stock")
        assertThat(band.line).contains("BSR-B: none to pick at AAC")
    }

    @Test
    fun `the last line says it ships by itself, with Undo`() {
        val s = onOrder().copy(
            notice = TransferNotice(TransferNotice.Tone.OK, "Picked 3 pcs", "BSR-B", undoable = true),
            autoPostAt = 1L,
        )
        val band = transferBand(s)
        assertThat(band.kind).isEqualTo(StatusKind.Ok)
        assertThat(band.undoable).isTrue()
        assertThat(band.line).contains("Ships in 5 s")
        assertThat(transferBand(s.copy(capture = s.capture.copy(mode = TransferMode.RECEIVE))).line).contains("Posts in 5 s")
    }

    @Test
    fun `the card shows the next open line in full, skipping set-aside lines`() {
        val s = onOrder().copy(captured = listOf(banked(1.0, "BSR-A", "5")))
        val card = transferCardFor(s, remaining(s))!!
        assertThat(card.code).isEqualTo("BSR-B")
        assertThat(card.quantity).isEqualTo("3")
        assertThat(card.label).isEqualTo("Next · line 2 of 2")
        val skipped = onOrder().copy(setAside = setOf(1.0))
        assertThat(transferCardFor(skipped, remaining(skipped))!!.code).isEqualTo("BSR-B")
    }

    @Test
    fun `receiving shows where the stock goes`() {
        val s = onOrder(TransferMode.RECEIVE)
        assertThat(transferCardFor(s, remaining(s))!!.location).isEqualTo("RECV")
    }

    @Test
    fun `the line in hand shows its quantity, location and batch`() {
        val s = onOrder().copy(
            capture = onOrder().capture.copy(line = lines[0], quantity = BigDecimal("4"), locationId = "A-01", batchId = "B1", unitSymbol = "pcs"),
        )
        val card = transferCardFor(s, remaining(s))!!
        assertThat(card.code).isEqualTo("BSR-A")
        assertThat(card.quantity).isEqualTo("4")
        assertThat(card.location).isEqualTo("A-01")
        assertThat(card.description).contains("Batch B1")
    }
}
