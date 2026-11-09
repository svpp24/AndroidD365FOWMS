package com.d365fo.mobileops.presentation.common.scan

import com.d365fo.mobileops.data.local.dao.ScannedItem
import com.d365fo.mobileops.domain.model.Gs1Data
import com.d365fo.mobileops.domain.model.Gs1Parser
import com.d365fo.mobileops.domain.model.RawScan
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject

/**
 * What a barcode turned out to be - the first half of [ScanRouter] (redesign brief §3.1). The
 * second half, [routeScan], decides what to do with it on the screen in front of the worker.
 */
enum class ScanKind { ITEM, PURCHASE_ORDER, TRANSFER_ORDER, COUNT_JOURNAL, LOCATION, BIN, UNKNOWN }

/** Whether this warehouse ships or receives a transfer - from its From / To warehouse. */
enum class TransferDirection { SHIP, RECEIVE }

/** An item, as far as the scan says - see [ScanIdentity.Item]. */
data class ItemRef(
    val itemId: String,
    val itemName: String,
    /** Blocked or discontinued in D365FO - the item is known, but nothing should be booked on it. */
    val active: Boolean = true,
)

/** A store-count virtual bin, and the location it belongs to (blank: any). */
data class BinRef(val binId: String, val locationId: String?)

sealed interface ScanIdentity {
    val raw: RawScan
    val kind: ScanKind

    /**
     * An item - by GS1 label, by a barcode in the synced barcode table, or by its item number.
     * The GS1 fields are what the label itself carries, so a flow can fill batch, serial and
     * expiry without asking (brief §3: ask only when there is a real choice).
     */
    data class Item(
        override val raw: RawScan,
        val item: ItemRef,
        /** The barcode row it matched - pack size, unit and variant. Null for an item number. */
        val barcode: ScannedItem? = null,
        val gs1: Gs1Data? = null,
    ) : ScanIdentity {
        override val kind get() = ScanKind.ITEM
        val batch: String? get() = gs1?.batch
        val serial: String? get() = gs1?.serial
        val expiry: LocalDate? get() = gs1?.expiry
        /** A count on the label (AI 30 / 37) - how many this one scan stands for. */
        val labelQuantity: BigDecimal? get() = gs1?.quantity
    }

    /** A purchase order, transfer order or counting journal number. */
    data class Document(
        override val raw: RawScan,
        override val kind: ScanKind,
        val documentId: String,
        /** Transfers only, when the device's copy of the order says - null if it doesn't know. */
        val direction: TransferDirection? = null,
    ) : ScanIdentity

    /** A location in the worker's warehouse, from the on-device location list. */
    data class Location(override val raw: RawScan, val locationId: String) : ScanIdentity {
        override val kind get() = ScanKind.LOCATION
    }

    /** A store-count bin label, from the on-device bin list. */
    data class Bin(override val raw: RawScan, val bin: BinRef) : ScanIdentity {
        override val kind get() = ScanKind.BIN
    }

    /**
     * Nothing on the device matches. [gs1] is set when it was a GS1 label whose GTIN is not in
     * the barcode table - so the message can say "item not in the item list", not "unreadable".
     */
    data class Unknown(override val raw: RawScan, val gs1: Gs1Data? = null) : ScanIdentity {
        override val kind get() = ScanKind.UNKNOWN
    }
}

/**
 * The on-device tables identification reads - an interface so [ScanIdentifier] is unit-tested
 * without Room. [RoomScanLookups] is the real one. All of them answer offline.
 */
interface ScanLookups {
    /** The synced barcode table. */
    suspend fun barcode(code: String): ScannedItem?
    /** An item by its number - item labels often carry the number, not a barcode. */
    suspend fun itemNumber(code: String): ItemRef?
    /** The location's own id in the worker's current warehouse, or null. */
    suspend fun location(code: String): String?
    /** A store-count bin in the worker's current warehouse, or null. */
    suspend fun bin(code: String): BinRef?
    /** Ship or receive, from the device's copy of the transfer; null when it has none. */
    suspend fun transferDirection(transferId: String): TransferDirection?
}

/**
 * Identifies a scan, in the brief's order (§3.1): GS1 application identifiers → document number
 * → location / bin → item barcode → item number → unknown.
 *
 * GS1 comes first because a GS1 element string is unambiguous - nothing else starts with an AIM
 * GS1 prefix or carries FNC1 - and it brings batch, expiry and serial with it. Document numbers
 * next because their shape alone says what they are, with no lookup. Locations and bins before
 * items so a bin label that happens to look like an item number goes where the worker meant.
 */
class ScanIdentifier @Inject constructor(
    private val lookups: ScanLookups,
) {
    suspend fun identify(raw: RawScan): ScanIdentity {
        // Case kept for GS1: batch and serial numbers may be lowercase.
        val text = raw.value.trim().trimEnd('\r', '\n').trim()
        if (text.isEmpty()) return ScanIdentity.Unknown(raw)

        Gs1Parser.parse(text)?.let { gs1 ->
            val match = gs1.gtinCandidates.firstNotNullOfOrNull { lookups.barcode(it) }
            return if (match != null) {
                ScanIdentity.Item(raw, match.toRef(), barcode = match, gs1 = gs1)
            } else {
                ScanIdentity.Unknown(raw, gs1 = gs1)
            }
        }

        val upper = text.uppercase()
        documentKind(upper)?.let { kind ->
            val direction = if (kind == ScanKind.TRANSFER_ORDER) lookups.transferDirection(upper) else null
            return ScanIdentity.Document(raw, kind, upper, direction)
        }

        // Both spellings: D365FO ids are case-insensitive, Room's default comparison is not.
        val tries = listOf(text, upper).distinct()
        tries.firstNotNullOfOrNull { lookups.location(it) }?.let { return ScanIdentity.Location(raw, it) }
        tries.firstNotNullOfOrNull { lookups.bin(it) }?.let { return ScanIdentity.Bin(raw, it) }
        tries.firstNotNullOfOrNull { lookups.barcode(it) }?.let {
            return ScanIdentity.Item(raw, it.toRef(), barcode = it)
        }
        tries.firstNotNullOfOrNull { lookups.itemNumber(it) }?.let { return ScanIdentity.Item(raw, it) }
        return ScanIdentity.Unknown(raw)
    }

    private fun ScannedItem.toRef() = ItemRef(itemId, itemName, active = itemActive && barcodeActive)

    companion object {
        /**
         * `<company>-PO-<digits>` and so on - BSP-PO-000161763, BSP-TO-000000043,
         * BSP-JN-000552008. Any company prefix, not only BSP: the number sequences are set up
         * per company, and the shape is what makes it a document, not the three letters.
         */
        private val DOCUMENT = Regex("""^[A-Z0-9]+-(PO|TO|JN)-\d+$""")

        fun documentKind(code: String): ScanKind? = when (DOCUMENT.matchEntire(code.uppercase())?.groupValues?.get(1)) {
            "PO" -> ScanKind.PURCHASE_ORDER
            "TO" -> ScanKind.TRANSFER_ORDER
            "JN" -> ScanKind.COUNT_JOURNAL
            else -> null
        }
    }
}
