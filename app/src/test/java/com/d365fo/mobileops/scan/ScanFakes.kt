package com.d365fo.mobileops.scan

import com.d365fo.mobileops.data.local.dao.ScannedItem
import com.d365fo.mobileops.data.local.entity.TrackingRequirement
import com.d365fo.mobileops.domain.model.RawScan
import com.d365fo.mobileops.domain.model.ScanSource
import com.d365fo.mobileops.presentation.common.scan.BinRef
import com.d365fo.mobileops.presentation.common.scan.ItemRef
import com.d365fo.mobileops.presentation.common.scan.ScanLookups
import com.d365fo.mobileops.presentation.common.scan.TransferDirection

/** The on-device tables, as a few known rows - what a synced AAC handheld would hold. */
class FakeScanLookups : ScanLookups {
    val barcodes = mutableMapOf(
        // GTIN-14 of a GS1 label, and a plain EAN-13 on the same kind of item.
        "09506000134352" to scanned("09506000134352", "BSR-11397-0", tracking = TrackingRequirement.BATCH),
        "5012345678900" to scanned("5012345678900", "BSR-20001-0"),
        "4006381333931" to scanned("4006381333931", "BSR-99999-0", active = false),
    )
    val items = mutableMapOf("BSR-10113-0" to ItemRef("BSR-10113-0", "Item by number"))
    val locations = mutableSetOf("A-01-02", "RECEIVING")
    val bins = mutableMapOf("BIN-07" to BinRef("BIN-07", "A-01-02"))
    val transfers = mutableMapOf(
        "BSP-TO-000000043" to TransferDirection.SHIP,
        "BSP-TO-000000044" to TransferDirection.RECEIVE,
    )
    val calls = mutableListOf<String>()

    override suspend fun barcode(code: String) = barcodes[code].also { calls += "barcode:$code" }
    override suspend fun itemNumber(code: String) = items[code].also { calls += "item:$code" }
    override suspend fun location(code: String) = code.takeIf { it in locations }.also { calls += "location:$code" }
    override suspend fun bin(code: String) = bins[code].also { calls += "bin:$code" }
    override suspend fun transferDirection(transferId: String) = transfers[transferId]

    companion object {
        fun scanned(
            barcode: String,
            itemId: String,
            tracking: TrackingRequirement = TrackingRequirement.NONE,
            active: Boolean = true,
        ) = ScannedItem(
            barcode = barcode,
            itemId = itemId,
            itemName = "Item $itemId",
            inventoryUnit = "pcs",
            purchaseUnit = null,
            quantityPerScan = "1",
            scanUnit = "pcs",
            trackingRequirement = tracking,
            locationMandatory = false,
            itemActive = active,
            barcodeActive = true,
        )
    }
}

fun scan(value: String, at: Long = 0L) = RawScan(value, ScanSource.IMAGER_BROADCAST, scannedAt = at)
