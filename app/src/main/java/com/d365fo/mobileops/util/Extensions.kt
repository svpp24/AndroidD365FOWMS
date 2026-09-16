package com.d365fo.mobileops.util

import java.math.BigDecimal
import java.math.RoundingMode

/** Quantities are money-grade: BigDecimal end to end, never Double. */
/**
 * Parses a keyed or scanned quantity.
 *
 * The scale bound is `0..6`, not `<= 6`. A negative scale is BigDecimal's way of saying the
 * input was in exponent form — "1e5" parses happily as 100000 with scale -5 — and a scanner
 * emitting a mangled label must not be able to turn a two-digit count into six figures. Plain
 * decimal only, at most six places, which is what D365FO reconciles against.
 */
fun String.toQuantityOrNull(): BigDecimal? =
    trim().replace(',', '.').toBigDecimalOrNull()?.takeIf { it.scale() in 0..6 }

fun BigDecimal.normalizeQty(): BigDecimal = setScale(6, RoundingMode.HALF_UP).stripTrailingZeros()

fun BigDecimal.isPositive(): Boolean = signum() > 0
fun BigDecimal.isZero(): Boolean = signum() == 0

/** Percentage deviation of [this] from [expected]; zero expected yields zero deviation. */
fun BigDecimal.deviationPercentFrom(expected: BigDecimal): BigDecimal =
    if (expected.signum() == 0) BigDecimal.ZERO
    else subtract(expected)
        .divide(expected, 6, RoundingMode.HALF_UP)
        .multiply(BigDecimal(100))
        .setScale(2, RoundingMode.HALF_UP)

fun <T> List<T>.chunkedForRoom(): List<List<T>> = chunked(Constants.DB_BATCH_SIZE)
fun <T> List<T>.chunkedForOData(): List<List<T>> = chunked(Constants.ODATA_BATCH_WRITE_SIZE)
