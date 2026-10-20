package com.d365fo.mobileops.domain.order

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The quantity rules an order line is checked against on the handheld, before D365FO sees it:
 * the item's purchase order settings (minimum, maximum, multiple) and, for a transfer, what is
 * available at the From warehouse. Zero means "no rule". D365FO checks again when it creates the
 * order; this only stops an obviously wrong line at the scanner.
 */
data class QuantityRules(
    val min: Double = 0.0,
    val max: Double = 0.0,
    val multiple: Double = 0.0,
    /** Null when unknown (offline) - then not checked here. */
    val available: Double? = null,
    /** Where [available] is, for the refusal: a transfer's From warehouse, a return's bin. */
    val availableWhere: String = "at the From warehouse",
) {
    /** One line for the quantity screen, e.g. "Min 10 · max 500 · multiples of 5". */
    val hint: String
        get() = listOfNotNull(
            min.takeIf { it > 0 }?.let { "Min ${qtyText(it)}" },
            max.takeIf { it > 0 }?.let { "max ${qtyText(it)}" },
            multiple.takeIf { it > 0 && it != 1.0 }?.let { "multiples of ${qtyText(it)}" },
            available?.let { "${qtyText(it)} available" },
        ).joinToString(" · ").replaceFirstChar { it.uppercase() }
}

/** Why [qty] is not allowed, or null when it is. */
fun checkQuantity(qty: Double, rules: QuantityRules): String? = when {
    qty <= 0 -> "Enter a quantity above zero"
    rules.available != null && qty > rules.available ->
        "Only ${qtyText(rules.available)} available ${rules.availableWhere}"
    rules.min > 0 && qty < rules.min -> "The minimum order quantity is ${qtyText(rules.min)}"
    rules.max > 0 && qty > rules.max -> "The maximum order quantity is ${qtyText(rules.max)}"
    rules.multiple > 0 && !isMultiple(qty, rules.multiple) -> "Order in multiples of ${qtyText(rules.multiple)}"
    else -> null
}

private fun isMultiple(qty: Double, multiple: Double): Boolean {
    val ratio = BigDecimal.valueOf(qty).divide(BigDecimal.valueOf(multiple), 6, RoundingMode.HALF_UP)
    return ratio.stripTrailingZeros().scale() <= 0
}

/** 12 not 12.0; 2.5 stays 2.5. */
fun qtyText(qty: Double): String = BigDecimal.valueOf(qty).stripTrailingZeros().toPlainString()

/** 1,234.50 */
fun amountText(amount: Double): String = String.format(java.util.Locale.US, "%,.2f", amount)
