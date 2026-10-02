package com.d365fo.mobileops.domain.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * One unit a quantity can be entered in, from the item's unit conversion setup in D365FO
 * (product-specific, variant-specific or standard): 1 [unitId] = [factor] of the line's unit.
 * The line's own unit is in the list with factor 1.
 */
data class ItemUnit(val unitId: String, val factor: BigDecimal, val decimals: Int)

/** An entered quantity, converted into the line's unit. */
data class ConvertedQuantity(val entered: BigDecimal, val enteredUnit: String, val quantity: BigDecimal) {
    val wasConverted: Boolean get() = quantity.compareTo(entered) != 0 || enteredUnit.isNotBlank()
}

object UnitConversion {

    private val ENTRY = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*([A-Za-z][A-Za-z0-9_.\-]*)?\s*$""")

    /**
     * Parses "3", "3 BOX" or "3box" and converts to the line's unit with the factor from
     * [units]. The result is rounded to [lineDecimals], the line unit's own precision, as
     * standard conversion rounds. Null with a reason when it cannot be converted.
     */
    fun parse(
        value: String,
        units: List<ItemUnit>,
        selectedUnit: String?,
        lineUnit: String,
        lineDecimals: Int = decimalsOf(units, lineUnit),
    ): Result<ConvertedQuantity> {
        val m = ENTRY.matchEntire(value)
            ?: return Result.failure(IllegalArgumentException("\"$value\" is not a number"))
        val entered = m.groupValues[1].replace(',', '.').toBigDecimal()
        val typedUnit = m.groupValues[2].takeIf { it.isNotBlank() }
        val unitId = typedUnit ?: selectedUnit ?: lineUnit
        if (unitId.equals(lineUnit, ignoreCase = true)) {
            return Result.success(ConvertedQuantity(entered, "", entered))
        }
        val unit = units.firstOrNull { it.unitId.equals(unitId, ignoreCase = true) }
            ?: return Result.failure(
                IllegalArgumentException(
                    "$unitId has no conversion to $lineUnit for this item" +
                        units.takeIf { it.size > 1 }?.let { " - use ${it.joinToString { u -> u.unitId }}" }.orEmpty(),
                ),
            )
        val converted = entered.multiply(unit.factor).setScale(lineDecimals.coerceAtLeast(0), RoundingMode.HALF_UP)
        return Result.success(ConvertedQuantity(entered, unit.unitId, converted.stripTrailingZeros()))
    }

    /** A pack barcode's quantity in its own unit, as line-unit quantity; null when no conversion applies. */
    fun packInLineUnit(quantity: BigDecimal, scanUnit: String?, units: List<ItemUnit>, lineUnit: String): BigDecimal? {
        if (scanUnit.isNullOrBlank() || scanUnit.equals(lineUnit, ignoreCase = true)) return null
        val unit = units.firstOrNull { it.unitId.equals(scanUnit, ignoreCase = true) } ?: return null
        return quantity.multiply(unit.factor)
            .setScale(decimalsOf(units, lineUnit).coerceAtLeast(0), RoundingMode.HALF_UP)
            .stripTrailingZeros()
    }

    private fun decimalsOf(units: List<ItemUnit>, unitId: String): Int =
        units.firstOrNull { it.unitId.equals(unitId, ignoreCase = true) }?.decimals ?: 6
}
