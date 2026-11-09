package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.domain.model.DeliveryTolerance
import com.d365fo.mobileops.domain.model.PurchaseOrderLine
import com.d365fo.mobileops.domain.model.ReceivingCapture
import com.d365fo.mobileops.domain.model.ReceivingStep
import com.d365fo.mobileops.domain.model.TrackingRequirement
import com.d365fo.mobileops.presentation.common.components.StatusKind
import com.d365fo.mobileops.presentation.modules.stepflow.CapturedLine
import com.d365fo.mobileops.presentation.modules.stepflow.PostOutcome
import com.d365fo.mobileops.presentation.modules.stepflow.ReceivingFlowState
import com.d365fo.mobileops.presentation.modules.stepflow.ReceivingNotice
import com.d365fo.mobileops.presentation.modules.stepflow.nextCardFor
import com.d365fo.mobileops.presentation.modules.stepflow.orderComplete
import com.d365fo.mobileops.presentation.modules.stepflow.receivingBand
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The redesigned receiving screen's rules (phase 4): when the order counts as done (and so
 * posts by itself), and what the status band and next-item card say.
 */
class ReceivingRedesignTest {

    private fun line(number: Double, item: String, outstanding: String) = PurchaseOrderLine(
        lineId = "PO-1:$number",
        purchId = "BSP-PO-000000001",
        lineNumber = number,
        itemId = item,
        itemName = "Name of $item",
        purchaseUnit = "pcs",
        inventoryUnit = "pcs",
        unitConversionFactor = BigDecimal.ONE,
        orderedQuantity = BigDecimal(outstanding),
        receivedQuantity = BigDecimal.ZERO,
        uncommittedQuantity = BigDecimal.ZERO,
        siteId = "1",
        warehouseId = "AAC",
        locationId = null,
        tolerance = DeliveryTolerance(overPercent = null, underPercent = null),
        tracking = TrackingRequirement.NONE,
    )

    private fun banked(number: Double, item: String, qty: String, max: String? = qty) =
        CapturedLine(number, item, "Name of $item", BigDecimal(qty), "pcs", maxQuantity = max?.let(::BigDecimal))

    private val lines = listOf(line(1.0, "BSR-A", "5"), line(2.0, "BSR-B", "3"))
    private val onOrder = ReceivingFlowState(
        step = ReceivingStep.ITEM,
        capture = ReceivingCapture(purchId = "BSP-PO-000000001", vendorAccount = "27455"),
        openLines = lines,
        outstandingLineCount = 2,
        linesInline = true,
    )

    private fun remaining(s: ReceivingFlowState) = { l: PurchaseOrderLine ->
        (l.outstandingQuantity - s.captured.filter { it.lineNumber == l.lineNumber }.fold(BigDecimal.ZERO) { t, c -> t + c.quantity })
            .coerceAtLeast(BigDecimal.ZERO)
    }

    // -- when the order is done -------------------------------------------------------------

    @Test
    fun `nothing banked is never done`() {
        assertThat(orderComplete(onOrder)).isFalse()
    }

    @Test
    fun `one of two lines banked is not done`() {
        assertThat(orderComplete(onOrder.copy(captured = listOf(banked(1.0, "BSR-A", "5"))))).isFalse()
    }

    @Test
    fun `both lines in full is done - this is what posts by itself`() {
        val s = onOrder.copy(captured = listOf(banked(1.0, "BSR-A", "5"), banked(2.0, "BSR-B", "3")))
        assertThat(orderComplete(s)).isTrue()
    }

    @Test
    fun `a short line (keypad down to 2 of 3) is not done`() {
        val s = onOrder.copy(captured = listOf(banked(1.0, "BSR-A", "5"), banked(2.0, "BSR-B", "2")))
        assertThat(orderComplete(s)).isFalse()
    }

    @Test
    fun `what an earlier posting from this device already staged counts`() {
        val s = onOrder.copy(
            captured = listOf(banked(2.0, "BSR-B", "3")),
            stagedElsewhere = mapOf(1.0 to BigDecimal("5")),
        )
        assertThat(orderComplete(s)).isTrue()
    }

    @Test
    fun `large order - done once as many lines are banked in full as are outstanding`() {
        val large = onOrder.copy(linesInline = false, openLines = emptyList(), outstandingLineCount = 2)
        assertThat(orderComplete(large.copy(captured = listOf(banked(7.0, "X", "4"))))).isFalse()
        assertThat(orderComplete(large.copy(captured = listOf(banked(7.0, "X", "4"), banked(9.0, "Y", "1", max = "2"))))).isFalse()
        assertThat(orderComplete(large.copy(captured = listOf(banked(7.0, "X", "4"), banked(9.0, "Y", "2"))))).isTrue()
    }

    // -- the band -----------------------------------------------------------------------------

    @Test
    fun `ready, then order found`() {
        assertThat(receivingBand(ReceivingFlowState()).kind).isEqualTo(StatusKind.Idle)
        val found = receivingBand(onOrder.copy(order = null).copy(order = dummyOrder()))
        assertThat(found.kind).isEqualTo(StatusKind.Info)
        assertThat(found.title).isEqualTo("BSP-PO-000000001")
        assertThat(found.line).isEqualTo("Vendor 27455 · 2 lines to receive")
    }

    @Test
    fun `a received line is green with Undo, and says it is about to post when it was the last`() {
        val received = ReceivingNotice(ReceivingNotice.Tone.OK, "Received 3 pcs", "BSR-B", undoable = true)
        val band = receivingBand(onOrder.copy(notice = received))
        assertThat(band.kind).isEqualTo(StatusKind.Ok)
        assertThat(band.undoable).isTrue()
        val last = receivingBand(onOrder.copy(notice = received, autoPostAt = 1L))
        assertThat(last.line).contains("Posts in 5 s")
    }

    @Test
    fun `a wrong item is red and says nothing was added`() {
        val band = receivingBand(onOrder.copy(error = "BSR-Z is not on this order"))
        assertThat(band.kind).isEqualTo(StatusKind.Bad)
        assertThat(band.line).isEqualTo("Nothing was added")
    }

    @Test
    fun `posting in progress wins over everything`() {
        val band = receivingBand(onOrder.copy(posting = true, captured = listOf(banked(1.0, "BSR-A", "5")), error = "x"))
        assertThat(band.kind).isEqualTo(StatusKind.Info)
        assertThat(band.title).isEqualTo("Posting 1 line…")
    }

    @Test
    fun `steps that need a real choice are amber`() {
        listOf(ReceivingStep.BATCH, ReceivingStep.LOCATION, ReceivingStep.BATCH_EXPIRY_DATE, ReceivingStep.QUANTITY).forEach {
            assertThat(receivingBand(onOrder.copy(step = it)).kind).isEqualTo(StatusKind.Warn)
        }
    }

    // -- the next card ------------------------------------------------------------------------

    @Test
    fun `the card shows the next open line with its full quantity`() {
        val card = nextCardFor(onOrder, remaining(onOrder))!!
        assertThat(card.label).isEqualTo("Next · line 1 of 2")
        assertThat(card.code).isEqualTo("BSR-A")
        assertThat(card.quantity).isEqualTo("5")
        val after = onOrder.copy(captured = listOf(banked(1.0, "BSR-A", "5")))
        val next = nextCardFor(after, remaining(after))!!
        assertThat(next.label).isEqualTo("Next · line 2 of 2")
        assertThat(next.code).isEqualTo("BSR-B")
    }

    @Test
    fun `all scanned says so`() {
        val done = onOrder.copy(captured = listOf(banked(1.0, "BSR-A", "5"), banked(2.0, "BSR-B", "3")), autoPostAt = 1L)
        assertThat(nextCardFor(done, remaining(done))!!.label).isEqualTo("All lines scanned")
    }

    @Test
    fun `after posting, the ready band shows the posting's result`() {
        val posted = PostOutcome("BSP-PO-000000001", 2, 0L, postedLive = true, grnId = "BSP-000000053")
        val s = ReceivingFlowState(lastPost = posted, notice = ReceivingNotice(ReceivingNotice.Tone.OK, "Received · GRN BSP-000000053"))
        assertThat(receivingBand(s).title).isEqualTo("Received · GRN BSP-000000053")
    }

    private fun dummyOrder() = com.d365fo.mobileops.domain.model.PurchaseOrder(
        purchId = "BSP-PO-000000001",
        vendorAccount = "27455",
        vendorName = "Vendor",
        status = com.d365fo.mobileops.domain.model.OrderStatus.BACKORDER,
        siteId = "1",
        warehouseId = "AAC",
        deliveryDate = null,
        approved = true,
        tolerance = DeliveryTolerance(overPercent = null, underPercent = null),
        lines = lines,
    )
}
