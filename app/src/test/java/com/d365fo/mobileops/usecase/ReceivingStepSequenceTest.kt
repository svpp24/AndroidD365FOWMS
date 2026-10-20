package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.domain.model.DeliveryTolerance
import com.d365fo.mobileops.domain.model.ItemDimensions
import com.d365fo.mobileops.domain.model.PurchaseOrderLine
import com.d365fo.mobileops.domain.model.TrackingRequirement
import com.d365fo.mobileops.domain.model.ReceivingCapture
import com.d365fo.mobileops.domain.model.ReceivingStep
import com.d365fo.mobileops.domain.model.nextIncompleteStep
import com.d365fo.mobileops.domain.model.stepsFor
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The step sequence is the interaction design, so it is tested as behaviour rather than shape.
 *
 * The failure that matters here is asking for a dimension the item does not track: D365FO
 * rejects a posting that carries one, and the operator has already walked away by the time it
 * fails. The opposite — not asking for one that is required — is just as bad and fails later.
 */
class ReceivingStepSequenceTest {

    private fun item(
        batch: Boolean = false,
        serial: Boolean = false,
        location: Boolean = true,
    ) = ItemDimensions(
        itemNumber = "ITEM-1",
        productName = "Test item",
        inventoryUnit = "ea",
        batchRequired = batch,
        serialRequired = serial,
        locationRequired = location,
    )

    /**
     * The item step is answered by a line *and* the item facts, because the flow resolves both
     * in one move - a capture holding one without the other is not a state the flow can reach.
     */
    private fun line() = PurchaseOrderLine(
        lineId = "PO-1:1.0",
        purchId = "PO-1",
        lineNumber = 1.0,
        itemId = "ITEM-1",
        itemName = "Test item",
        purchaseUnit = "ea",
        inventoryUnit = "ea",
        unitConversionFactor = BigDecimal.ONE,
        orderedQuantity = BigDecimal("10"),
        receivedQuantity = BigDecimal.ZERO,
        uncommittedQuantity = BigDecimal.ZERO,
        siteId = "1",
        warehouseId = "100011",
        locationId = null,
        tolerance = DeliveryTolerance(overPercent = null, underPercent = null),
        tracking = TrackingRequirement.NONE,
    )

    @Test
    fun `an untracked item is never asked for a batch or a serial`() {
        val steps = stepsFor(ReceivingCapture(item = item()))

        assertThat(steps).containsExactly(
            ReceivingStep.PURCHASE_ORDER,
            ReceivingStep.ITEM,
            ReceivingStep.QUANTITY,
            ReceivingStep.LOCATION,
            ReceivingStep.CONFIRM,
        ).inOrder()
    }

    @Test
    fun `a batch tracked item gains exactly one step`() {
        val steps = stepsFor(ReceivingCapture(item = item(batch = true)))

        assertThat(steps).contains(ReceivingStep.BATCH)
        assertThat(steps).doesNotContain(ReceivingStep.SERIAL)
    }

    @Test
    fun `an item tracked both ways is asked for batch before serial`() {
        val steps = stepsFor(ReceivingCapture(item = item(batch = true, serial = true)))

        assertThat(steps.indexOf(ReceivingStep.BATCH))
            .isLessThan(steps.indexOf(ReceivingStep.SERIAL))
    }

    @Test
    fun `an item with no location dimension skips the location prompt`() {
        val steps = stepsFor(ReceivingCapture(item = item(location = false)))

        assertThat(steps).doesNotContain(ReceivingStep.LOCATION)
    }

    @Test
    fun `before the item is known the flow still plans to ask for a location`() {
        // Nothing is known yet, so the sequence is a best guess used only to render progress.
        // Guessing "location required" matches the conservative default the lookup itself uses.
        val steps = stepsFor(ReceivingCapture())

        assertThat(steps).contains(ReceivingStep.LOCATION)
    }

    @Test
    fun `the flow resumes at the first gap, not at the furthest step reached`() {
        // A capture with a quantity but no location: the operator stepped back and cleared it.
        // Resuming at CONFIRM here would post a receipt with no location.
        val capture = ReceivingCapture(
            purchId = "PO-1",
            vendorPackingSlipId = "DN-1",
            line = line(),
            item = item(),
            quantity = BigDecimal("5"),
            locationId = null,
        )

        assertThat(nextIncompleteStep(capture)).isEqualTo(ReceivingStep.LOCATION)
    }

    @Test
    fun `a serial tracked line is not complete until there is one serial per unit`() {
        val base = ReceivingCapture(
            purchId = "PO-1",
            vendorPackingSlipId = "DN-1",
            line = line(),
            item = item(serial = true),
            quantity = BigDecimal("3"),
            locationId = "RECV-01",
        )

        assertThat(nextIncompleteStep(base)).isEqualTo(ReceivingStep.SERIAL)
        assertThat(base.serialsOutstanding).isEqualTo(3)

        val two = base.copy(serialIds = listOf("SN-1", "SN-2"))
        assertThat(nextIncompleteStep(two)).isEqualTo(ReceivingStep.SERIAL)
        assertThat(two.serialsOutstanding).isEqualTo(1)

        val three = base.copy(serialIds = listOf("SN-1", "SN-2", "SN-3"))
        assertThat(nextIncompleteStep(three)).isEqualTo(ReceivingStep.CONFIRM)
        assertThat(three.serialsOutstanding).isEqualTo(0)
    }

    @Test
    fun `an item resolved without a line leaves the item step unanswered`() {
        // Both halves land together in the real flow. If only one ever did, the flow would
        // walk past the item step holding facts about an item it has no line to receive onto.
        val halfAnswered =
            ReceivingCapture(purchId = "PO-1", vendorPackingSlipId = "DN-1", item = item())

        assertThat(nextIncompleteStep(halfAnswered)).isEqualTo(ReceivingStep.ITEM)
    }

    @Test
    fun `a trailing zero in the quantity does not change how many serials are needed`() {
        // 3 and 3.000000 are the same number of physical units, and BigDecimal equality is not.
        val capture = ReceivingCapture(
            purchId = "PO-1",
            vendorPackingSlipId = "DN-1",
            line = line(),
            item = item(serial = true),
            quantity = BigDecimal("3.000000"),
            locationId = "RECV-01",
            serialIds = listOf("SN-1", "SN-2", "SN-3"),
        )

        assertThat(capture.serialsComplete()).isTrue()
    }

    @Test
    fun `the confirm step is terminal and never counts as answered`() {
        val complete = ReceivingCapture(
            purchId = "PO-1",
            vendorPackingSlipId = "DN-1",
            line = line(),
            item = item(),
            quantity = BigDecimal("1"),
            locationId = "RECV-01",
        )

        assertThat(nextIncompleteStep(complete)).isEqualTo(ReceivingStep.CONFIRM)
    }

    @Test
    fun `batch and a new batch's expiry come before the bin location`() {
        val capture = ReceivingCapture(
            purchId = "PO-1",
            line = line(),
            item = item(batch = true, location = true),
            quantity = BigDecimal("5"),
            batchIsNew = true,
        )

        val steps = stepsFor(capture)

        assertThat(steps.indexOf(ReceivingStep.BATCH)).isLessThan(steps.indexOf(ReceivingStep.LOCATION))
        assertThat(steps.indexOf(ReceivingStep.BATCH_EXPIRY_DATE)).isLessThan(steps.indexOf(ReceivingStep.LOCATION))
        assertThat(nextIncompleteStep(capture)).isEqualTo(ReceivingStep.BATCH)
    }

    @Test
    fun `a line that carries its bin is never asked for one`() {
        val capture = ReceivingCapture(
            purchId = "PO-1",
            line = line().copy(locationId = "BIN-1"),
            item = item(batch = true, location = true),
            quantity = BigDecimal("5"),
        )

        assertThat(stepsFor(capture)).doesNotContain(ReceivingStep.LOCATION)
    }
}
