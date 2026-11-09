package com.d365fo.mobileops.presentation.modules.ordercreate

import com.d365fo.mobileops.presentation.common.scan.ScanContext
import com.d365fo.mobileops.presentation.common.scan.ScanIdentity
import com.d365fo.mobileops.presentation.common.scan.ScanKind
import com.d365fo.mobileops.presentation.common.scan.ScanRoute
import com.d365fo.mobileops.presentation.common.scan.ScanRouter

/**
 * An order-creation screen's place on [ScanRouter] - the same pattern as PO receiving (phase
 * 4): attached while the screen is on screen ([onScreen], counted, so a dialog's own screen can
 * share it), with the context read fresh for every scan so a scan can never be routed by a
 * context one step behind. The router waits for [onRoute] before the next scan, so rapid scans
 * queue in order.
 */
internal class OrderFlowScanning(
    private val router: ScanRouter,
    private val context: () -> ScanContext,
    private val onRoute: suspend (ScanRoute) -> Unit,
) {
    private var handle: ScanRouter.Handle? = null
    private var screens = 0

    fun onScreen(visible: Boolean) {
        if (visible) {
            if (screens++ == 0) handle = router.attach(context, onRoute)
        } else if (screens > 0 && --screens == 0) {
            close()
        }
    }

    fun close() {
        handle?.close()
        handle = null
    }
}

/**
 * The code to look an item up by: the barcode row's own barcode (it carries the variant), a GS1
 * label's GTIN, else what was scanned.
 */
internal fun itemCodeFor(identity: ScanIdentity): String = when (identity) {
    is ScanIdentity.Item -> identity.barcode?.barcode ?: identity.gs1?.gtin ?: identity.item.itemId
    is ScanIdentity.Unknown -> identity.gs1?.gtin ?: identity.raw.value.trim()
    else -> identity.raw.value.trim()
}

/** "a transfer order", for the band when another process's document is scanned mid-flow. */
internal fun documentNoun(kind: ScanKind): String = when (kind) {
    ScanKind.PURCHASE_ORDER -> "a purchase order"
    ScanKind.TRANSFER_ORDER -> "a transfer order"
    ScanKind.COUNT_JOURNAL -> "a counting journal"
    ScanKind.BIN -> "a bin label"
    ScanKind.LOCATION -> "a location"
    else -> "another document"
}
