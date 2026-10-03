package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.domain.model.DeliveryTolerance
import com.d365fo.mobileops.domain.model.ItemDimensions
import com.d365fo.mobileops.domain.model.PurchaseOrderLine
import com.d365fo.mobileops.domain.model.ReceivingCapture
import com.d365fo.mobileops.domain.model.TrackingRequirement
import com.d365fo.mobileops.domain.model.ReceivingStep
import com.d365fo.mobileops.domain.model.nextIncompleteStep
import com.d365fo.mobileops.domain.model.stepsFor
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The flow against the actual purchase-to-receipt process.
 *
 * Draft → approved by a manager → **confirmed and sent to the vendor** by the clerk → vendor
 * accepts → goods arrive → warehouse receives and puts away → GRN posted. Two things follow from
 * that ordering, and both were wrong before it was written down:
 *
 *  - Approval is not the gate. Confirmation is. An approved-but-unconfirmed order is one the
 *    vendor has never been sent, so stock cannot legitimately have arrived against it.
 *  - The delivery note number is not optional. It is the vendor's reference on the posted receipt,
 *    and it is what reconciles the GRN against the vendor's paperwork afterwards.
 */
class ReceivingProcessRulesTest {

    private fun item(location: Boolean = true) = ItemDimensions(
        itemNumber = "A00001",
        productName = "Test item",
        inventoryUnit = "ea",
        batchRequired = false,
        serialRequired = false,
        locationRequired = location,
    )

    /** The item step is answered by a line *and* the item facts; the flow sets both together. */
    private fun line() = PurchaseOrderLine(
        lineId = "PO-1:1.0",
        purchId = "PO-1",
        lineNumber = 1.0,
        itemId = "A00001",
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

    // Receiving only registers now, so the vendor's delivery note is no longer asked
    // (see ReceivingStep.PACKING_SLIP) - an order goes straight to the item.

    @Test
    fun `the delivery note is no longer asked`() {
        assertThat(stepsFor(ReceivingCapture())).doesNotContain(ReceivingStep.PACKING_SLIP)
    }

    @Test
    fun `an order goes straight to the item`() {
        assertThat(nextIncompleteStep(ReceivingCapture(purchId = "PO-1"))).isEqualTo(ReceivingStep.ITEM)
    }

    @Test
    fun `with a delivery note captured the flow moves on to the item`() {
        val capture = ReceivingCapture(purchId = "PO-1", vendorPackingSlipId = "DN-99812")

        assertThat(nextIncompleteStep(capture)).isEqualTo(ReceivingStep.ITEM)
    }

    @Test
    fun `the put-away location is still the last dimension before confirm`() {
        val capture = ReceivingCapture(
            purchId = "PO-1",
            vendorPackingSlipId = "DN-1",
            line = line(),
            item = item(),
            quantity = BigDecimal("5"),
        )

        // The bulk storage location the stock ends up in. A non-WMS receipt has no separate
        // put-away transaction, so this is the only chance to record where it actually went.
        assertThat(nextIncompleteStep(capture)).isEqualTo(ReceivingStep.LOCATION)
    }

    @Test
    fun `stepping back to the item clears everything captured after it`() {
        val complete = ReceivingCapture(
            purchId = "PO-1",
            line = line(),
            item = item(),
            quantity = BigDecimal("5"),
            locationId = "BULK-01",
        )

        assertThat(nextIncompleteStep(complete)).isEqualTo(ReceivingStep.CONFIRM)

        // Correcting the item must not leave a quantity banked against the old one.
        val corrected = complete.copy(line = null, item = null, quantity = null, locationId = null)
        assertThat(nextIncompleteStep(corrected)).isEqualTo(ReceivingStep.ITEM)
    }

    @Test
    fun `an item with no location dimension skips the location`() {
        val steps = stepsFor(ReceivingCapture(item = item(location = false)))

        assertThat(steps).doesNotContain(ReceivingStep.LOCATION)
    }
}
