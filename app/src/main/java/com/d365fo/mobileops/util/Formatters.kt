package com.d365fo.mobileops.util

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formatters {

    /** D365FO OData datetimes are ISO-8601 UTC, e.g. 2026-08-26T11:00:00Z. */
    private val odata: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    fun epochMillisToOData(epochMs: Long): String = odata.format(Instant.ofEpochMilli(epochMs))

    fun odataToEpochMillis(value: String?): Long? = runCatching {
        value?.let { Instant.parse(it).toEpochMilli() }
    }.getOrNull()

    /** D365FO date-only fields (Edm.Date) — delivery date, journal date. */
    fun epochMillisToODataDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()

    fun qty(value: BigDecimal): String = value.normalizeQty().toPlainString()

    fun shortDate(epochMs: Long, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("dd MMM", locale)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs))

    fun clockTime(epochMs: Long, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("HH:mm", locale)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs))
}
