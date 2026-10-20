package com.d365fo.mobileops.data.counting

import com.d365fo.mobileops.data.local.dao.CountLine
import com.d365fo.mobileops.util.Formatters

/**
 * A counting journal's lines as it stands on this device, as CSV for Excel (feedback, 12 Oct:
 * "export the journal in an excel CSV format"). One row per journal line, uncounted ones with an
 * empty Counted; values quoted where needed, so an item name with a comma stays one column.
 */
object CountCsv {
    private val header = listOf(
        "Journal", "Line", "Item", "Item name", "Configuration", "Size", "Color", "Style",
        "Location", "Batch", "Serial", "Counted", "Counted by", "Counted at",
    )

    fun of(journalId: String, lines: List<CountLine>): String = buildString {
        appendRow(header)
        lines.sortedBy { it.lineNumber }.forEach { line ->
            appendRow(
                listOf(
                    journalId,
                    line.lineNumber.toString(),
                    line.itemId,
                    line.itemName,
                    line.configId.orEmpty(),
                    line.sizeId.orEmpty(),
                    line.colorId.orEmpty(),
                    line.styleId.orEmpty(),
                    line.locationId.orEmpty(),
                    line.batchId.orEmpty(),
                    line.serialId.orEmpty(),
                    line.countedQuantity.orEmpty(),
                    line.countedBy.orEmpty(),
                    line.countedAt?.let { Formatters.isoUtc(it) }.orEmpty(),
                ),
            )
        }
    }

    private fun StringBuilder.appendRow(values: List<String>) {
        append(values.joinToString(",") { quote(it) })
        append("\r\n")
    }

    private fun quote(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
}
