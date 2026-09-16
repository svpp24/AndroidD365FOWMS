package com.d365fo.mobileops.domain.model

import java.math.BigDecimal

/** Where a scan came from. Camera scans are debounced; HID wedge input arrives as keystrokes. */
/**
 * How a barcode reached the app.
 *
 * IMAGER_BROADCAST is the vendor-service case: the scan head decodes and broadcasts an Intent,
 * with no keyboard involved. It is a separate source from HID_WEDGE because the two need entirely
 * different plumbing, and knowing which one a device actually uses is the first question to ask
 * when scanning "does not work".
 */
enum class ScanSource { CAMERA, HID_WEDGE, IMAGER_BROADCAST, MANUAL_ENTRY }

data class RawScan(
    val value: String,
    val source: ScanSource,
    val scannedAt: Long = System.currentTimeMillis(),
) {
    /**
     * Scanners commonly append a terminator and pad with whitespace, and some symbologies
     * carry an AIM identifier prefix. Normalising here means every downstream lookup sees
     * the same shape.
     */
    fun normalized(): String = value.trim().trimEnd('\r', '\n').uppercase()
}

sealed interface ScanResolution {

    /** Barcode resolved to a known, active item. */
    data class Resolved(
        val itemId: String,
        val itemName: String,
        val inventoryUnit: String,
        /** One scan represents this many [scanUnit] units — inner/outer pack barcodes. */
        val quantityPerScan: BigDecimal,
        val scanUnit: String,
        val tracking: TrackingRequirement,
        val locationMandatory: Boolean,
        val raw: RawScan,
    ) : ScanResolution

    /** The barcode is not in the local master. Not necessarily unknown to D365FO. */
    data class UnknownBarcode(val raw: RawScan) : ScanResolution

    /** Known barcode, but the item is blocked or discontinued. */
    data class ItemInactive(val itemId: String, val raw: RawScan) : ScanResolution

    /** Known active item that has no open line on the document being worked. */
    data class NotOnDocument(
        val itemId: String,
        val itemName: String,
        val documentId: String,
        val raw: RawScan,
    ) : ScanResolution
}
