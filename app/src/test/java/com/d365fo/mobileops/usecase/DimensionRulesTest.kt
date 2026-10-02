package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.domain.model.DeliveryTolerance
import com.d365fo.mobileops.domain.model.DimensionRules
import com.d365fo.mobileops.domain.model.ItemDimensions
import com.d365fo.mobileops.domain.model.PurchaseOrderLine
import com.d365fo.mobileops.domain.model.ReceivingCapture
import com.d365fo.mobileops.domain.model.ReceivingStep
import com.d365fo.mobileops.domain.model.TrackingRequirement
import com.d365fo.mobileops.domain.model.canSkipStep
import com.d365fo.mobileops.domain.model.nextIncompleteStep
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Optional dimensions and serial number control, as the dimension group sets them. */
class DimensionRulesTest {

    private fun line(rules: DimensionRules) = PurchaseOrderLine(
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
        locationId = "A-01",
        tolerance = DeliveryTolerance(overPercent = null, underPercent = null),
        tracking = TrackingRequirement.BATCH_AND_SERIAL,
        rules = rules,
    )

    private fun capture(rules: DimensionRules, quantity: String = "3") = ReceivingCapture(
        purchId = "PO-1",
        line = line(rules),
        item = ItemDimensions("ITEM-1", "Test item", "ea", batchRequired = true, serialRequired = true, locationRequired = true),
        quantity = BigDecimal(quantity),
    )

    @Test
    fun `a dimension blank receipt allows can be skipped, a required one cannot`() {
        val optionalBatch = capture(DimensionRules(batchMandatory = false))
        assertThat(canSkipStep(ReceivingStep.BATCH, optionalBatch)).isTrue()
        assertThat(canSkipStep(ReceivingStep.SERIAL, optionalBatch)).isFalse()
        assertThat(canSkipStep(ReceivingStep.BATCH, capture(DimensionRules()))).isFalse()
    }

    @Test
    fun `a skipped batch moves the flow on to the serial`() {
        val c = capture(DimensionRules(batchMandatory = false))
        assertThat(nextIncompleteStep(c)).isEqualTo(ReceivingStep.BATCH)
        assertThat(nextIncompleteStep(c.copy(skipped = setOf(ReceivingStep.BATCH)))).isEqualTo(ReceivingStep.SERIAL)
    }

    @Test
    fun `serial number control asks one serial per unit, otherwise one for the line`() {
        val perUnit = capture(DimensionRules(serialPerUnit = true)).copy(serialIds = listOf("S1"))
        assertThat(perUnit.serialsComplete()).isFalse()
        assertThat(perUnit.serialsOutstanding).isEqualTo(2)

        val perLine = capture(DimensionRules(serialPerUnit = false)).copy(serialIds = listOf("S1"))
        assertThat(perLine.serialsComplete()).isTrue()
        assertThat(perLine.serialsOutstanding).isEqualTo(0)
    }
}
