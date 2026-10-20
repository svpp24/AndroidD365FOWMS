package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.domain.model.ShippedBatch
import com.d365fo.mobileops.domain.model.ShippedBatchCheck
import com.d365fo.mobileops.domain.model.TransferOrderLine
import com.d365fo.mobileops.domain.model.TransferOrderLookup
import com.d365fo.mobileops.domain.model.checkShippedBatch
import com.d365fo.mobileops.presentation.modules.transfer.CapturedTransferLine
import com.d365fo.mobileops.presentation.modules.transfer.TransferFlowState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Feedback 461bd22a (Transfer - Ship: a 2-unit line scanned as 1 + 1 from two batches still said
 * "1 line outstanding") and 9fb668d7 (Transfer - Receive: check at the batch scan that the batch
 * was shipped, in that quantity).
 */
class TransferBatchFeedbackTest {

    private fun line(
        lineNumber: Double = 1.0,
        remaining: String = "2",
        shipped: List<ShippedBatch> = emptyList(),
    ) = TransferOrderLine(
        lineNumber = lineNumber,
        itemId = "B00001",
        itemName = "Batch item",
        unitId = "ea",
        transferQuantity = BigDecimal("2"),
        shippedQuantity = BigDecimal("2"),
        receivedQuantity = BigDecimal.ZERO,
        remainingQuantity = BigDecimal(remaining),
        fromSiteId = "1",
        fromWarehouseId = "AAC",
        toSiteId = "2",
        toWarehouseId = "AAS",
        locationId = null,
        batchRequired = true,
        serialRequired = false,
        locationRequired = false,
        shippedBatches = shipped,
    )

    private fun scanned(lineNumber: Double, qty: String, batch: String, max: String? = "2") = CapturedTransferLine(
        lineNumber = lineNumber,
        itemId = "B00001",
        itemName = "Batch item",
        quantity = BigDecimal(qty),
        unitSymbol = "ea",
        batchId = batch,
        maxQuantity = max?.let(::BigDecimal),
    )

    private fun order(lines: List<TransferOrderLine>, inline: Boolean = true, count: Int = lines.size) = TransferOrderLookup(
        transferId = "TO-1",
        transferStatus = "Created",
        actionable = true,
        blockReason = null,
        fromSiteId = "1",
        fromWarehouseId = "AAC",
        toSiteId = "2",
        toWarehouseId = "AAS",
        outstandingLineCount = count,
        linesInline = inline,
        lines = lines,
    )

    private val shippedAB = listOf(ShippedBatch("A1", BigDecimal.ONE), ShippedBatch("B2", BigDecimal.ONE))

    @Test
    fun `a line scanned in full from two batches is no longer outstanding`() {
        val l = line()
        val state = TransferFlowState(
            order = order(listOf(l)),
            openLines = listOf(l),
            captured = listOf(scanned(1.0, "1", "A1"), scanned(1.0, "1", "B2")),
        )
        assertThat(state.outstandingLineCount).isEqualTo(0)
    }

    @Test
    fun `a partly scanned line is still outstanding`() {
        val l = line()
        val state = TransferFlowState(order = order(listOf(l)), openLines = listOf(l), captured = listOf(scanned(1.0, "1", "A1")))
        assertThat(state.outstandingLineCount).isEqualTo(1)
    }

    @Test
    fun `a large order without its lines counts lines scanned up to their ceiling`() {
        val state = TransferFlowState(
            order = order(emptyList(), inline = false, count = 5),
            captured = listOf(scanned(1.0, "1", "A1"), scanned(1.0, "1", "B2"), scanned(2.0, "1", "A1")),
        )
        assertThat(state.outstandingLineCount).isEqualTo(4)
    }

    @Test
    fun `a shipped batch within its quantity is accepted`() {
        val check = checkShippedBatch(line(shipped = shippedAB), "a1", BigDecimal.ONE, BigDecimal.ZERO)
        assertThat(check).isEqualTo(ShippedBatchCheck.Ok)
    }

    @Test
    fun `a batch that was not shipped is refused, naming the shipped ones`() {
        val check = checkShippedBatch(line(shipped = shippedAB), "C3", BigDecimal.ONE, BigDecimal.ZERO)
        assertThat(check).isEqualTo(ShippedBatchCheck.NotShipped(shippedAB))
    }

    @Test
    fun `more of a batch than was shipped is refused, counting what is already scanned`() {
        val check = checkShippedBatch(line(shipped = shippedAB), "A1", BigDecimal.ONE, BigDecimal.ONE)
        assertThat(check).isEqualTo(ShippedBatchCheck.TooMany(BigDecimal.ONE, BigDecimal.ONE))
    }

    @Test
    fun `a line without shipped batches takes any batch`() {
        val check = checkShippedBatch(line(), "ANY", BigDecimal.TEN, BigDecimal.ZERO)
        assertThat(check).isEqualTo(ShippedBatchCheck.Ok)
    }
}
