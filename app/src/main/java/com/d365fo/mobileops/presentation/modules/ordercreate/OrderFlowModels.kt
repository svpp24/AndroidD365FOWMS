package com.d365fo.mobileops.presentation.modules.ordercreate

import com.d365fo.mobileops.data.local.entity.CreatedOrderEntity
import com.d365fo.mobileops.data.local.entity.CreatedOrderStatus
import com.d365fo.mobileops.data.local.entity.VendorEntity
import com.d365fo.mobileops.data.repository.DraftLine
import com.d365fo.mobileops.data.repository.OrderItem
import com.d365fo.mobileops.data.repository.StockRow
import com.d365fo.mobileops.domain.order.qtyText
import com.d365fo.mobileops.presentation.common.components.StatusKind
import java.math.BigDecimal
import java.math.RoundingMode

/*
 * The rules the redesigned order-creation screens draw from (redesign brief §3-§4, phase 7):
 * Create PO, Return to vendor and Create transfer. Pure - no Android, no repository - so each
 * rule is a plain unit test (OrderCreateRedesignTest).
 */

/**
 * What the last scan or action did, for the status band - a line added (with Undo), a refusal,
 * an order sent. Cleared by the next scan; the band then says what the flow needs next.
 */
data class OrderNotice(
    val kind: StatusKind,
    val title: String,
    val line: String? = null,
    /** Offer Undo in the band's Undo window. */
    val undoable: Boolean = false,
    val id: Long = System.nanoTime(),
)

/** The status band's content, worked out from a screen's state. */
data class OrderBand(
    val kind: StatusKind,
    val title: String,
    val line: String? = null,
    val undoable: Boolean = false,
    /** Changes with each new result, so the band's Undo window restarts. */
    val key: Any? = null,
) {
    companion object {
        fun of(notice: OrderNotice) = OrderBand(notice.kind, notice.title, notice.line, notice.undoable, notice.id)
    }
}

/**
 * Create PO's quantity for one scan (brief §3: "create PO = item minimum × multiple"): the
 * smallest quantity D365FO's order rules allow - the minimum, rounded up to the multiple, never
 * above the maximum. With no rules at all, the item's standard order quantity, else 1.
 */
fun defaultOrderQty(min: Double, max: Double, multiple: Double, standard: Double = 0.0): Double {
    val base = when {
        min > 0 -> min
        standard > 0 -> standard
        multiple > 0 -> multiple
        else -> 1.0
    }
    val rounded = if (multiple > 0) {
        val packs = BigDecimal.valueOf(base).divide(BigDecimal.valueOf(multiple), 0, RoundingMode.CEILING)
        packs.max(BigDecimal.ONE).multiply(BigDecimal.valueOf(multiple)).toDouble()
    } else {
        base
    }
    return if (max > 0 && rounded > max) max else rounded
}

fun defaultOrderQty(item: OrderItem): Double =
    defaultOrderQty(item.minQty, item.maxQty, item.multipleQty, item.standardQty)

/** One quantity added to one line by one scan - what Undo takes back. */
data class LineAdd(val lineId: String, val qty: Double)

/**
 * Adds [line] to [lines]: onto the line with the same item, variant and stock (location, batch,
 * serial) when there is one, else as a new line at the end. A line whose price a supervisor set
 * is never merged into. Returns the lines and what was added, for Undo.
 *
 * @param repriced whether a merged line loses its price (a PO line is priced again by D365FO).
 */
fun addOrMerge(lines: List<DraftLine>, line: DraftLine, repriced: Boolean = false): Pair<List<DraftLine>, LineAdd> {
    val same = lines.indexOfFirst {
        it.itemId == line.itemId && it.variant == line.variant && it.locationId == line.locationId &&
            it.batchId == line.batchId && it.serialId == line.serialId && !it.priceOverride
    }
    if (same < 0) return (lines + line) to LineAdd(line.id, line.qty)
    val target = lines[same]
    val merged = lines.mapIndexed { i, l ->
        if (i != same) l
        else if (repriced) l.copy(qty = l.qty + line.qty, price = null, lineAmount = null)
        else l.copy(qty = l.qty + line.qty)
    }
    return merged to LineAdd(target.id, line.qty)
}

/**
 * Undo (F3): takes back [last] - the quantity the last scan added, and the line with it when
 * nothing is left on it. Without a record (a draft reopened), the last line goes.
 */
fun undoAdd(lines: List<DraftLine>, last: LineAdd?, repriced: Boolean = false): List<DraftLine> {
    if (last == null) return lines.dropLast(1)
    val line = lines.firstOrNull { it.id == last.lineId } ?: return lines.dropLast(1)
    val left = line.qty - last.qty
    return if (left <= 1e-9) {
        lines.filterNot { it.id == line.id }
    } else {
        lines.map {
            if (it.id != line.id) it
            else if (repriced) it.copy(qty = left, price = null, lineAmount = null)
            else it.copy(qty = left)
        }
    }
}

/** The line the keypad changes and the card shows: the one the last scan touched, else the last. */
fun lineInHand(lines: List<DraftLine>, last: LineAdd?): DraftLine? =
    last?.let { a -> lines.firstOrNull { it.id == a.lineId } } ?: lines.lastOrNull()

/**
 * The stock row a scan takes without asking (brief §3: "auto-pick the only location with
 * stock"): the only row with stock, or null when there is a real choice (or none).
 */
fun onlyStockRow(rows: List<StockRow>): StockRow? = rows.filter { it.available > 0 }.singleOrNull()

/**
 * The vendor list for Create PO with the last-used vendor first (one tap), the rest as found.
 * While a search is typed the list is the search's, in its own order.
 */
fun vendorsLastUsedFirst(vendors: List<VendorEntity>, lastUsed: VendorEntity?, query: String): List<VendorEntity> {
    if (lastUsed == null || query.isNotBlank()) return vendors
    return listOf(lastUsed) + vendors.filterNot { it.accountNum.equals(lastUsed.accountNum, ignoreCase = true) }
}

/** A vendor account typed or scanned, matched exactly (ignoring case) - null when it isn't one. */
fun vendorFor(code: String, vendors: List<VendorEntity>): VendorEntity? {
    val value = code.trim()
    if (value.isEmpty()) return null
    return vendors.firstOrNull { it.accountNum.equals(value, ignoreCase = true) }
}

/**
 * The band for an order this screen sent: on its way, created (with D365FO's number), or
 * refused with D365FO's own reason (brief: "a refused order shows a Bad band with the reason").
 *
 * @param noun "PO", "Return", "Transfer".
 */
fun sentOrderBand(order: CreatedOrderEntity, noun: String, online: Boolean): OrderBand {
    val lines = "${order.lineCount} " + if (order.lineCount == 1) "line" else "lines"
    return when (order.status) {
        CreatedOrderStatus.WAITING -> OrderBand(
            StatusKind.Info,
            if (online) "Sending $noun…" else "$noun saved on this device",
            "${order.title} · $lines" + if (online) "" else " · sends when D365FO answers",
            key = order.id,
        )
        CreatedOrderStatus.CREATED -> {
            val number = order.documentNumber.orEmpty()
            val message = order.message.orEmpty()
            when {
                message.startsWith("Created; not confirmed") -> OrderBand(
                    StatusKind.Warn, "$noun $number created, not confirmed".replace("  ", " "), message, key = order.id,
                )
                message.startsWith("Confirmed; packing slip not posted") -> OrderBand(
                    StatusKind.Warn, "$noun $number not posted".replace("  ", " "), message, key = order.id,
                )
                else -> OrderBand(StatusKind.Ok, "$noun $number created".replace("  ", " "), "${order.title} · $lines", key = order.id)
            }
        }
        CreatedOrderStatus.FAILED -> OrderBand(
            StatusKind.Bad,
            "$noun refused",
            order.message?.takeIf { it.isNotBlank() } ?: "D365FO gave no reason - see Activity",
            key = order.id,
        )
    }
}

/** "Scanned BSR-1009-0 · 12 pcs" with the line's total when the scan added to it. */
fun addedLine(line: DraftLine, added: Double): String {
    val unit = line.unitId.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
    return if (line.qty > added + 1e-9) "+${qtyText(added)} · ${qtyText(line.qty)}$unit on the line"
    else "${qtyText(added)}$unit"
}

fun linesWord(n: Int): String = if (n == 1) "1 line" else "$n lines"
