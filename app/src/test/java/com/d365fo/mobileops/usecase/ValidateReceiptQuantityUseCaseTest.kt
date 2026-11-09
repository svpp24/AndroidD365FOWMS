package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.domain.model.DeliveryTolerance
import com.d365fo.mobileops.domain.model.PurchaseOrderLine
import com.d365fo.mobileops.domain.model.TrackingRequirement
import com.d365fo.mobileops.domain.model.ValidationLayer
import com.d365fo.mobileops.domain.model.ValidationOutcome
import com.d365fo.mobileops.domain.model.ValidationSeverity
import com.d365fo.mobileops.domain.usecase.ValidateReceiptQuantityUseCase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

class ValidateReceiptQuantityUseCaseTest {

    private val useCase = ValidateReceiptQuantityUseCase()

    private fun line(
        ordered: String = "100",
        received: String = "0",
        uncommitted: String = "0",
        overPercent: String? = null,
        underPercent: String? = null,
        tracking: TrackingRequirement = TrackingRequirement.NONE,
        conversionFactor: String = "1",
    ) = PurchaseOrderLine(
        lineId = "PO-1:1",
        purchId = "PO-1",
        lineNumber = 1.0,
        itemId = "ITEM-001",
        itemName = "Test item",
        purchaseUnit = "ea",
        inventoryUnit = "ea",
        unitConversionFactor = BigDecimal(conversionFactor),
        orderedQuantity = BigDecimal(ordered),
        receivedQuantity = BigDecimal(received),
        uncommittedQuantity = BigDecimal(uncommitted),
        siteId = "S1",
        warehouseId = "WH1",
        locationId = "LOC-01",
        tolerance = DeliveryTolerance(
            overPercent = overPercent?.let { BigDecimal(it) },
            underPercent = underPercent?.let { BigDecimal(it) },
        ),
        tracking = tracking,
    )

    @Nested
    @DisplayName("Layer 1 — format")
    inner class Format {

        @ParameterizedTest
        @ValueSource(strings = ["", "  ", "abc", "1.2.3", "--5", "1e5"])
        fun `rejects anything that is not a number`(input: String) {
            val result = useCase(input, line())

            val outcome = result.outcome as ValidationOutcome.Invalid
            assertThat(outcome.severity).isEqualTo(ValidationSeverity.HARD)
            assertThat(outcome.layer).isEqualTo(ValidationLayer.FORMAT)
            assertThat(result.inventoryQuantity).isNull()
        }

        @Test
        fun `rejects zero and negative quantities`() {
            listOf("0", "-1", "-0.5").forEach { input ->
                val outcome = useCase(input, line()).outcome as ValidationOutcome.Invalid
                assertThat(outcome.severity).isEqualTo(ValidationSeverity.HARD)
                assertThat(outcome.message).contains("greater than zero")
            }
        }

        @Test
        fun `accepts a comma decimal separator`() {
            // European keyboards and some scanner wedges emit a comma.
            val result = useCase("12,5", line())

            assertThat(result.outcome).isEqualTo(ValidationOutcome.Valid)
            assertThat(result.inventoryQuantity).isEqualTo(BigDecimal("12.5"))
        }
    }

    @Nested
    @DisplayName("Layer 3 — unit conversion")
    inner class UnitConversion {

        @Test
        fun `multiplies by the conversion factor when entered in the purchase unit`() {
            val result = useCase("2", line(ordered = "100", conversionFactor = "12"))

            assertThat(result.inventoryQuantity).isEqualTo(BigDecimal("24"))
        }

        @Test
        fun `skips conversion when the entry is already in the inventory unit`() {
            val result = useCase(
                "2",
                line(conversionFactor = "12"),
                enteredInPurchaseUnit = false,
            )

            assertThat(result.inventoryQuantity).isEqualTo(BigDecimal("2"))
        }

        @Test
        fun `does not accumulate binary floating point error`() {
            // The reason quantities are BigDecimal: 0.1 * 3 is 0.30000000000000004 as Double.
            val result = useCase("0.1", line(conversionFactor = "3"))

            assertThat(result.inventoryQuantity).isEqualTo(BigDecimal("0.3"))
        }
    }

    @Nested
    @DisplayName("Layer 3 — tolerance")
    inner class Tolerance {

        @Test
        fun `accepts a partial receipt without invoking under-delivery tolerance`() {
            // 40 of 100 is a partial receipt, not an under-delivery. The remaining 60 stay
            // open on the order, so no tolerance applies and no warning is shown.
            val result = useCase("40", line(ordered = "100", underPercent = "0"))

            assertThat(result.outcome).isEqualTo(ValidationOutcome.Valid)
        }

        @Test
        fun `accepts receiving exactly the ordered quantity`() {
            val result = useCase("100", line(ordered = "100"))

            assertThat(result.outcome).isEqualTo(ValidationOutcome.Valid)
        }

        @Test
        fun `hard-blocks over-receipt when the order allows no over-delivery`() {
            // D365FO would refuse this posting outright, so a soft warning would only let
            // the worker queue a receipt guaranteed to dead-letter an hour later.
            val outcome = useCase("101", line(ordered = "100", overPercent = null))
                .outcome as ValidationOutcome.Invalid

            assertThat(outcome.severity).isEqualTo(ValidationSeverity.HARD)
            assertThat(outcome.message).contains("left")
        }

        @Test
        fun `hard-blocks over-receipt when the tolerance is explicitly zero`() {
            val outcome = useCase("101", line(ordered = "100", overPercent = "0"))
                .outcome as ValidationOutcome.Invalid

            assertThat(outcome.severity).isEqualTo(ValidationSeverity.HARD)
        }

        @Test
        fun `accepts over-receipt inside the configured tolerance`() {
            val result = useCase("105", line(ordered = "100", overPercent = "10"))

            assertThat(result.outcome).isEqualTo(ValidationOutcome.Valid)
            assertThat(result.resultingOverPercent).isEqualTo(BigDecimal("5.00"))
        }

        @Test
        fun `accepts over-receipt exactly at the tolerance boundary`() {
            val result = useCase("110", line(ordered = "100", overPercent = "10"))

            assertThat(result.outcome).isEqualTo(ValidationOutcome.Valid)
        }

        @Test
        fun `offers a supervisor override when over-receipt exceeds the tolerance`() {
            val outcome = useCase("115", line(ordered = "100", overPercent = "10"))
                .outcome as ValidationOutcome.Invalid

            assertThat(outcome.severity).isEqualTo(ValidationSeverity.SOFT)
            assertThat(outcome.isOverridable).isTrue()
            assertThat(outcome.message).contains("15.00%")
        }

        @Test
        fun `counts quantity already posted in D365FO toward the tolerance`() {
            // 80 already received, so receiving 25 more would put the line at 105.
            val outcome = useCase("25", line(ordered = "100", received = "80", overPercent = "0"))
                .outcome as ValidationOutcome.Invalid

            assertThat(outcome.severity).isEqualTo(ValidationSeverity.HARD)
        }

        @Test
        fun `counts quantity captured offline but not yet posted`() {
            // This is what stops two offline sessions on one order from jointly
            // over-receiving: the uncommitted local quantity counts the same as posted.
            val outcome = useCase(
                "25",
                line(ordered = "100", received = "50", uncommitted = "30", overPercent = "0"),
            ).outcome as ValidationOutcome.Invalid

            assertThat(outcome.severity).isEqualTo(ValidationSeverity.HARD)
        }
    }

    @Nested
    @DisplayName("Serialised items")
    inner class Serialised {

        @Test
        fun `rejects more than one unit on a serialised line`() {
            val outcome = useCase(
                "2",
                line(tracking = TrackingRequirement.SERIAL),
            ).outcome as ValidationOutcome.Invalid

            assertThat(outcome.severity).isEqualTo(ValidationSeverity.HARD)
            assertThat(outcome.message).contains("one at a time")
        }

        @Test
        fun `accepts a single unit on a serialised line`() {
            val result = useCase("1", line(tracking = TrackingRequirement.SERIAL))

            assertThat(result.outcome).isEqualTo(ValidationOutcome.Valid)
        }
    }

    @Nested
    @DisplayName("Short close")
    inner class ShortClose {

        @Test
        fun `is valid when the line is fully delivered`() {
            val outcome = useCase.validateShortClose(line(ordered = "100", received = "100"))

            assertThat(outcome).isEqualTo(ValidationOutcome.Valid)
        }

        @Test
        fun `is valid when the shortfall is inside the under-delivery tolerance`() {
            val outcome = useCase.validateShortClose(
                line(ordered = "100", received = "95", underPercent = "10"),
            )

            assertThat(outcome).isEqualTo(ValidationOutcome.Valid)
        }

        @Test
        fun `needs supervisor approval when the shortfall exceeds the tolerance`() {
            val outcome = useCase.validateShortClose(
                line(ordered = "100", received = "80", underPercent = "10"),
            ) as ValidationOutcome.Invalid

            assertThat(outcome.severity).isEqualTo(ValidationSeverity.SOFT)
            assertThat(outcome.message).contains("20.00%")
        }

        @Test
        fun `needs supervisor approval when no under-delivery is permitted`() {
            val outcome = useCase.validateShortClose(
                line(ordered = "100", received = "99", underPercent = null),
            ) as ValidationOutcome.Invalid

            assertThat(outcome.severity).isEqualTo(ValidationSeverity.SOFT)
        }
    }
}
