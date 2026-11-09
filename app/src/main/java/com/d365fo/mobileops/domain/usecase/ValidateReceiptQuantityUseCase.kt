package com.d365fo.mobileops.domain.usecase

import com.d365fo.mobileops.domain.model.PurchaseOrderLine
import com.d365fo.mobileops.domain.model.ValidationLayer
import com.d365fo.mobileops.domain.model.ValidationOutcome
import com.d365fo.mobileops.domain.model.ValidationSeverity
import com.d365fo.mobileops.domain.model.invalid
import com.d365fo.mobileops.util.deviationPercentFrom
import com.d365fo.mobileops.util.normalizeQty
import com.d365fo.mobileops.util.toQuantityOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

/**
 * Result of validating one keyed quantity, carrying the converted value so the caller does
 * not repeat the unit conversion.
 */
data class QuantityValidation(
    val outcome: ValidationOutcome,
    /** Quantity in the item's inventory unit. Null when the input could not be parsed. */
    val inventoryQuantity: BigDecimal?,
    /** Percentage over the ordered quantity this receipt would leave the line at. */
    val resultingOverPercent: BigDecimal?,
)

/**
 * Validates a received quantity against a purchase order line: layers 1 and 3 of the
 * validation framework.
 *
 * Two rules here deserve their reasoning stated, because both are easy to get wrong:
 *
 *  - **Under-delivery tolerance is not applied to a partial receipt.** Receiving 40 of 100
 *    is not an under-delivery; it is a partial receipt, and the remaining 60 stay open on
 *    the order. Under-tolerance only becomes relevant when the worker deliberately closes
 *    the line short, which is a separate, explicit action.
 *
 *  - **Over-delivery with no configured tolerance is a hard block, not a warning.** When
 *    the order permits no over-delivery at all, D365FO itself will refuse the posting. A
 *    soft warning would let the worker override into a receipt that is guaranteed to fail
 *    in the sync queue an hour later, which is the worst possible time to discover it.
 */
class ValidateReceiptQuantityUseCase @Inject constructor() {

    operator fun invoke(
        rawInput: String,
        line: PurchaseOrderLine,
        /** True when the entry is in the purchase unit and needs conversion. */
        enteredInPurchaseUnit: Boolean = true,
    ): QuantityValidation {

        // -- Layer 1: format ------------------------------------------------------------
        val entered = rawInput.toQuantityOrNull()
            ?: return QuantityValidation(
                invalid(
                    ValidationSeverity.HARD, ValidationLayer.FORMAT, FIELD,
                    "Enter a valid number", rawInput,
                ),
                inventoryQuantity = null,
                resultingOverPercent = null,
            )

        if (entered.signum() <= 0) {
            return QuantityValidation(
                invalid(
                    ValidationSeverity.HARD, ValidationLayer.FORMAT, FIELD,
                    "Quantity must be greater than zero", rawInput,
                ),
                inventoryQuantity = null,
                resultingOverPercent = null,
            )
        }

        // -- Layer 3: unit conversion ---------------------------------------------------
        val inventoryQty = if (enteredInPurchaseUnit) {
            entered.multiply(line.unitConversionFactor).setScale(6, RoundingMode.HALF_UP)
        } else {
            entered
        }.normalizeQty()

        // A serialised item is one physical unit per serial number, so a single captured
        // line cannot carry more than one.
        if (line.tracking.requiresSerial && inventoryQty > BigDecimal.ONE) {
            return QuantityValidation(
                invalid(
                    ValidationSeverity.HARD, ValidationLayer.CACHED_RULES, FIELD,
                    "Serial items: one at a time", rawInput,
                ),
                inventoryQuantity = inventoryQty,
                resultingOverPercent = null,
            )
        }

        // -- Layer 3: tolerance ---------------------------------------------------------
        val alreadyAccounted = line.receivedQuantity + line.uncommittedQuantity
        val resultingTotal = alreadyAccounted + inventoryQty
        val overBy = resultingTotal - line.orderedQuantity

        if (overBy.signum() <= 0) {
            // At or under the ordered quantity: a normal full or partial receipt.
            return QuantityValidation(ValidationOutcome.Valid, inventoryQty, null)
        }

        val overPercent = resultingTotal.deviationPercentFrom(line.orderedQuantity)
        val allowed = line.tolerance.overPercent

        return when {
            allowed == null || allowed.signum() == 0 -> QuantityValidation(
                invalid(
                    ValidationSeverity.HARD, ValidationLayer.CACHED_RULES, FIELD,
                    "Only ${line.outstandingQuantity.toPlainString()} ${line.inventoryUnit} left",
                    rawInput,
                ),
                inventoryQty,
                overPercent,
            )

            overPercent > allowed -> QuantityValidation(
                // Soft: the goods are physically on the dock. A supervisor can accept them,
                // and D365FO will take the posting because the tolerance is configurable
                // per line — but somebody has to own the decision.
                invalid(
                    ValidationSeverity.SOFT, ValidationLayer.CACHED_RULES, FIELD,
                    "${overPercent.toPlainString()}% over (max ${allowed.toPlainString()}%)",
                    rawInput,
                ),
                inventoryQty,
                overPercent,
            )

            else -> QuantityValidation(ValidationOutcome.Valid, inventoryQty, overPercent)
        }
    }

    /**
     * Separate entry point for deliberately closing a line short. This is where the
     * under-delivery percentage actually applies.
     */
    fun validateShortClose(line: PurchaseOrderLine): ValidationOutcome {
        val delivered = line.receivedQuantity + line.uncommittedQuantity
        if (delivered >= line.orderedQuantity) return ValidationOutcome.Valid

        val shortfallPercent = delivered.deviationPercentFrom(line.orderedQuantity).abs()
        val allowed = line.tolerance.underPercent

        return when {
            allowed == null || allowed.signum() == 0 -> invalid(
                ValidationSeverity.SOFT, ValidationLayer.CACHED_RULES, FIELD,
                "This order does not allow under-delivery. " +
                    "Closing it short needs supervisor approval.",
            )

            shortfallPercent > allowed -> invalid(
                ValidationSeverity.SOFT, ValidationLayer.CACHED_RULES, FIELD,
                "${shortfallPercent.toPlainString()}% short (max ${allowed.toPlainString()}%)",
            )

            else -> ValidationOutcome.Valid
        }
    }

    private companion object {
        const val FIELD = "quantity"
    }
}
