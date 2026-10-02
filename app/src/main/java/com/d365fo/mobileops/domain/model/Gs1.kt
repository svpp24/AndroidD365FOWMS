package com.d365fo.mobileops.domain.model

import java.math.BigDecimal
import java.time.LocalDate

/**
 * What a GS1 barcode (GS1-128, GS1 DataMatrix, GS1 QR) carries that the flows use: the GTIN,
 * batch/lot (10), serial (21), expiry (17, or best-before 15), and a count (30 / 37).
 * [elements] keeps every application identifier found, for anything else a caller wants.
 */
data class Gs1Data(
    val gtin: String?,
    val batch: String?,
    val serial: String?,
    val expiry: LocalDate?,
    val quantity: BigDecimal?,
    val elements: Map<String, String>,
) {
    /** GTIN variants to try against the barcode list: GTIN-14 as is, then without leading zeros to 13 / 12 / 8. */
    val gtinCandidates: List<String>
        get() {
            val g = gtin ?: return emptyList()
            return buildList {
                add(g)
                if (g.length == 14 && g.startsWith("0")) add(g.substring(1))
                if (g.length == 14 && g.startsWith("00")) add(g.substring(2))
                if (g.length == 14 && g.startsWith("000000")) add(g.substring(6))
            }.distinct()
        }
}

/**
 * Parses GS1 element strings. Accepts the forms scanners actually send:
 * - with an AIM symbology identifier (`]C1` GS1-128, `]d2` GS1 DataMatrix, `]Q3` GS1 QR, `]e0` DataBar),
 * - FNC1 transmitted as the GS character (0x1D) between variable-length fields,
 * - the human-readable form with parentheses, e.g. `(01)09506000134352(10)ABC123(17)261231`.
 *
 * Returns null for anything that is not a GS1 element string, so a plain EAN or item number is
 * left to the normal lookup. Values keep their case - batch and serial numbers may be lowercase.
 */
object Gs1Parser {
    private const val GS = '\u001D'
    private val AIM_PREFIXES = listOf("]C1", "]d2", "]Q3", "]e0", "]J1")

    /** Fixed-length AIs (data length after the AI) - the GS1 General Specifications table. */
    private val FIXED = mapOf(
        "00" to 18, "01" to 14, "02" to 14,
        "11" to 6, "12" to 6, "13" to 6, "15" to 6, "16" to 6, "17" to 6, "20" to 2,
    )

    /** Variable-length AIs the flows read, with their maximum length. */
    private val VARIABLE = mapOf("10" to 20, "21" to 20, "22" to 20, "30" to 8, "37" to 8, "240" to 30, "241" to 30, "250" to 30, "400" to 30)

    fun parse(raw: String): Gs1Data? {
        var s = raw.trim().trimEnd('\r', '\n')
        var aim = false
        AIM_PREFIXES.firstOrNull { s.startsWith(it) }?.let { s = s.removePrefix(it); aim = true }
        if (s.startsWith(GS)) s = s.substring(1)

        val elements = when {
            s.startsWith("(") -> parseBracketed(s)
            aim || s.contains(GS) || looksLikeGs1(s) -> parseRaw(s)
            else -> null
        } ?: return null
        if (elements.isEmpty() || (!elements.containsKey("01") && !elements.containsKey("02") && !elements.containsKey("00"))) {
            return null
        }
        return Gs1Data(
            gtin = elements["01"] ?: elements["02"],
            batch = elements["10"],
            serial = elements["21"],
            expiry = (elements["17"] ?: elements["15"])?.let(::yymmdd),
            quantity = (elements["30"] ?: elements["37"])?.toBigDecimalOrNull(),
            elements = elements,
        )
    }

    /** A bare digit string starting with (01)+14 digits is GS1 even without an AIM prefix. */
    private fun looksLikeGs1(s: String): Boolean =
        s.length > 16 && s.startsWith("01") && s.substring(2, 16).all { it.isDigit() }

    private fun parseBracketed(s: String): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        val re = Regex("""\((\d{2,4})\)([^(]*)""")
        re.findAll(s).forEach { m -> out[m.groupValues[1]] = m.groupValues[2].trim() }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun parseRaw(s: String): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < s.length) {
            if (s[i] == GS) { i++; continue }
            val ai = aiAt(s, i) ?: return out.takeIf { it.isNotEmpty() }
            i += ai.length
            val fixed = FIXED[ai] ?: weightLength(ai)
            if (fixed != null) {
                if (i + fixed > s.length) return out.takeIf { it.isNotEmpty() }
                out[ai] = s.substring(i, i + fixed)
                i += fixed
            } else {
                val max = VARIABLE[ai] ?: 30
                var end = s.indexOf(GS, i).let { if (it < 0) s.length else it }
                if (end - i > max) end = i + max
                out[ai] = s.substring(i, end)
                i = end
            }
        }
        return out
    }

    /** 310n-369n: six digits, n decimals. */
    private fun weightLength(ai: String): Int? =
        if (ai.length == 4 && ai[0] == '3' && ai[1] in '1'..'6' && ai[3].isDigit()) 6 else null

    private fun aiAt(s: String, i: Int): String? {
        if (i + 2 > s.length) return null
        val two = s.substring(i, i + 2)
        if (two in FIXED || two in VARIABLE) return two
        if (i + 3 <= s.length && s.substring(i, i + 3) in VARIABLE) return s.substring(i, i + 3)
        if (i + 4 <= s.length && weightLength(s.substring(i, i + 4)) != null) return s.substring(i, i + 4)
        return null
    }

    /** GS1 dates are YYMMDD; DD = 00 means the last day of the month. */
    private fun yymmdd(v: String): LocalDate? {
        if (v.length != 6 || !v.all { it.isDigit() }) return null
        val yy = v.substring(0, 2).toInt()
        val year = if (yy >= 51) 1900 + yy else 2000 + yy
        val month = v.substring(2, 4).toInt()
        val day = v.substring(4, 6).toInt()
        return runCatching {
            val first = LocalDate.of(year, month, 1)
            if (day == 0) first.withDayOfMonth(first.lengthOfMonth()) else first.withDayOfMonth(day)
        }.getOrNull()
    }
}
