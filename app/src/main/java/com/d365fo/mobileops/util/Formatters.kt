package com.d365fo.mobileops.util

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formatters {

    /** D365FO OData datetimes are ISO-8601 UTC, e.g. 2026-08-26T11:00:00Z. */
    private val odata: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    fun epochMillisToOData(epochMs: Long): String = odata.format(Instant.ofEpochMilli(epochMs))

    fun odataToEpochMillis(value: String?): Long? = runCatching {
        value?.let { Instant.parse(it).toEpochMilli() }
    }.getOrNull()

    /**
     * A D365FO custom-service datetime, e.g. "2026-09-11T12:00:00" — no zone suffix, unlike
     * OData's own format. Confirmed live against HSMobileOpsCountingSvc::listOpenJournals
     * (2026-09-23): the field is a `utcdatetime` in X++, serialized without a Z, so the naive
     * local value here is treated as UTC rather than the device's zone.
     */
    fun serviceDateTimeToEpochMillis(value: String?): Long? = runCatching {
        value?.let { LocalDateTime.parse(it).toInstant(ZoneOffset.UTC).toEpochMilli() }
    }.getOrNull() ?: runCatching { value?.let { Instant.parse(it).toEpochMilli() } }.getOrNull()

    /** ISO 8601 UTC ("2026-09-30T10:15:00Z") - how the app sends timestamps to X++. */
    fun isoUtc(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

    fun qty(value: BigDecimal): String = value.normalizeQty().toPlainString()

    /**
     * "1 Piece", "2 Pieces": the quantity with its unit, singular for exactly one. D365FO unit
     * symbols are codes, not words, so only plain English plurals in [SINGULAR] change; any
     * other symbol ("PCS", "Kg") is shown as it is.
     */
    fun qtyUnit(value: BigDecimal, unit: String?): String {
        val number = value.stripTrailingZeros().toPlainString()
        val symbol = unit.orEmpty().trim()
        if (symbol.isEmpty()) return number
        val shown = if (value.compareTo(BigDecimal.ONE) == 0) {
            SINGULAR[symbol.lowercase()]?.let { matchCase(it, symbol) } ?: symbol
        } else {
            symbol
        }
        return "$number $shown"
    }

    private val SINGULAR = mapOf(
        "pieces" to "piece", "packs" to "pack", "boxes" to "box", "bottles" to "bottle",
        "units" to "unit", "cartons" to "carton", "cases" to "case", "bags" to "bag",
        "tablets" to "tablet", "strips" to "strip", "vials" to "vial", "tubes" to "tube",
    )

    private fun matchCase(word: String, like: String): String = when {
        like.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        like.first().isUpperCase() -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }

    fun shortDate(epochMs: Long, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("dd MMM", locale)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs))

    /**
     * A yyyy-MM-dd date from a service as a worker reads it: "Today", "Tomorrow", "15 Dec", or
     * "15 Dec 2025" outside the current year. Anything that isn't a date comes back as it was.
     */
    fun dayLabel(isoDate: String, today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): String {
        val date = isoDay(isoDate) ?: return isoDate
        return when (date) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            today.minusDays(1) -> "Yesterday"
            else -> DateTimeFormatter.ofPattern(if (date.year == today.year) "dd MMM" else "dd MMM yyyy", locale).format(date)
        }
    }

    /** [isoDate] as a date, or null when it is blank, not yyyy-MM-dd, or D365FO's empty 1900-01-01. */
    fun isoDay(isoDate: String): LocalDate? =
        runCatching { LocalDate.parse(isoDate.trim().take(10)) }.getOrNull()?.takeIf { it.year > 1900 }

    fun clockTime(epochMs: Long, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("HH:mm", locale)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs))
}
