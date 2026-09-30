package com.d365fo.mobileops.util

import java.math.BigDecimal
import java.time.Instant
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
