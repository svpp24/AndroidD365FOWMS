package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.domain.model.TransferCapture
import com.d365fo.mobileops.domain.model.TransferMode
import com.d365fo.mobileops.domain.model.TransferOrderLine
import com.d365fo.mobileops.domain.model.TransferStep
import com.d365fo.mobileops.domain.model.nextIncompleteTransferStep
import com.d365fo.mobileops.domain.model.stepsForTransfer
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The two ends of a transfer are not mirror images, and the differences are the whole risk.
 *
 * Shipping takes stock off a pick face and has nowhere to put it; receiving has to record where it
 * went, and is bounded by what was actually shipped rather than by what was ordered. Getting
 * either wrong is silent: the app would simply offer the operator a step or a quantity that makes
 * no sense, and D365FO would reject it long after they had walked away.
 */
class TransferStepSequenceTest {

    private fun line(
        batch: Boolean = false,
        serial: Boolean = false,
        location: Boolean = true,
        transfer: String = "10",
        shipped: String = "0",
        remaining: String = "10",
    ) = TransferOrderLine(
        lineNumber = 1.0,
        itemId = "A00001",
        itemName = "Test item",
        unitId = "ea",
        transferQuantity = BigDecimal(transfer),
        shippedQuantity = BigDecimal(shipped),
        receivedQuantity = BigDecimal.ZERO,
        remainingQuantity = BigDecimal(remaining),
        fromSiteId = "1",
        fromWarehouseId = "100011",
        toSiteId = "2",
        toWarehouseId = "100022",
        locationId = "BULK-01",
        batchRequired = batch,
        serialRequired = serial,
        locationRequired = location,
    )

    @Test
    fun `shipping never asks where to put the goods`() {
        val steps = stepsForTransfer(
            TransferCapture(mode = TransferMode.SHIP, line = line(location = true)),
        )

        // Even for a location-tracked item. The stock is leaving; there is no destination bin to
        // record, and prompting would be asking the operator to invent an answer.
        assertThat(steps).doesNotContain(TransferStep.LOCATION)
        assertThat(steps).containsExactly(
            TransferStep.TRANSFER_ORDER,
            TransferStep.ITEM,
            TransferStep.QUANTITY,
            TransferStep.CONFIRM,
        ).inOrder()
    }

    @Test
    fun `receiving asks where to put the goods`() {
        val steps = stepsForTransfer(
            TransferCapture(mode = TransferMode.RECEIVE, line = line(location = true)),
        )

        assertThat(steps).contains(TransferStep.LOCATION)
    }

    @Test
    fun `receiving an untracked item still skips the location`() {
        val steps = stepsForTransfer(
            TransferCapture(mode = TransferMode.RECEIVE, line = line(location = false)),
        )

        assertThat(steps).doesNotContain(TransferStep.LOCATION)
    }

    @Test
    fun `tracking dimensions apply in both directions`() {
        val shipping = stepsForTransfer(
            TransferCapture(mode = TransferMode.SHIP, line = line(batch = true, serial = true)),
        )
        val receiving = stepsForTransfer(
            TransferCapture(mode = TransferMode.RECEIVE, line = line(batch = true, serial = true)),
        )

        // A batch-tracked item needs its batch recorded whichever way it is moving.
        assertThat(shipping).containsAtLeast(TransferStep.BATCH, TransferStep.SERIAL)
        assertThat(receiving).containsAtLeast(TransferStep.BATCH, TransferStep.SERIAL)
    }

    @Test
    fun `a receive resumes at the put-away step when the location was cleared`() {
        val capture = TransferCapture(
            transferId = "TR-1",
            mode = TransferMode.RECEIVE,
            line = line(),
            quantity = BigDecimal("4"),
            locationId = null,
        )

        assertThat(nextIncompleteTransferStep(capture)).isEqualTo(TransferStep.LOCATION)
    }

    @Test
    fun `a ship with a quantity is ready to confirm`() {
        val capture = TransferCapture(
            transferId = "TR-1",
            mode = TransferMode.SHIP,
            line = line(),
            quantity = BigDecimal("4"),
        )

        assertThat(nextIncompleteTransferStep(capture)).isEqualTo(TransferStep.CONFIRM)
    }

    @Test
    fun `remaining quantity is what the server sent, not the ordered quantity`() {
        // Ten ordered, four shipped: four is what may be received, and the app must never derive
        // ten from the transfer quantity. This is the single most consequential rule in the flow.
        val partiallyShipped = line(transfer = "10", shipped = "4", remaining = "4")

        assertThat(partiallyShipped.remainingQuantity).isEqualTo(BigDecimal("4"))
        assertThat(partiallyShipped.transferQuantity).isEqualTo(BigDecimal("10"))
        assertThat(partiallyShipped.hasOutstanding).isTrue()
    }

    @Test
    fun `a line with nothing left in this direction is not outstanding`() {
        assertThat(line(remaining = "0").hasOutstanding).isFalse()
    }

    @Test
    fun `the mode maps to the wire values the service expects`() {
        // The server switches on these exact strings; a typo would silently default to receiving.
        assertThat(TransferMode.SHIP.wire).isEqualTo("Ship")
        assertThat(TransferMode.RECEIVE.wire).isEqualTo("Receive")
    }

    @Test
    fun `serial capture needs one per unit, as with receiving`() {
        val base = TransferCapture(
            transferId = "TR-1",
            mode = TransferMode.SHIP,
            line = line(serial = true),
            quantity = BigDecimal("3"),
        )

        assertThat(base.serialsOutstanding).isEqualTo(3)
        assertThat(nextIncompleteTransferStep(base)).isEqualTo(TransferStep.SERIAL)

        val done = base.copy(serialIds = listOf("SN-1", "SN-2", "SN-3"))
        assertThat(done.serialsComplete()).isTrue()
        assertThat(nextIncompleteTransferStep(done)).isEqualTo(TransferStep.CONFIRM)
    }
}
