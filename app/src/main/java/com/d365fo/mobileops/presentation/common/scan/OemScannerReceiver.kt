package com.d365fo.mobileops.presentation.common.scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * Receives scans from handhelds whose imager is *not* a keyboard wedge.
 *
 * [HidScannerBridge] handles the wedge case, where the scan head types the barcode as keystrokes.
 * A great many rugged devices do not work that way: the imager is driven by a vendor service that
 * broadcasts the decoded barcode as an Intent, and no keyboard device exists for it at all.
 *
 * That is exactly the SUNMI P2mini this app runs on. Its input devices are `mtk-tpd`, `pay_key`,
 * `ACCDET`, `xctech-key` and `mtk-kpd` — not one of them an alphabetic keyboard — so
 * `HidScannerBridge.isFromScanner` could never return true and pulling the trigger did nothing at
 * all. The scan head was working the whole time; the app was listening on the wrong channel.
 *
 * Registered at runtime rather than in the manifest, deliberately. A manifest receiver for an
 * implicit broadcast is ignored from Android 8 onwards, and a scan should only be acted on while
 * the app is actually in front of the operator — a background app quietly consuming scans meant
 * for whatever is on screen is a bug, not a feature.
 */
class OemScannerReceiver(
    private val onScan: (String) -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val decoded = intent?.let(::extractBarcode)
        if (decoded.isNullOrBlank()) {
            Timber.w("scan broadcast %s carried no barcode", intent?.action)
            return
        }

        // Logged on every scan, deliberately. "Did the barcode reach the app at all" is the first
        // question asked when a handheld appears not to be scanning, and it is the one that took
        // longest to answer here — the length rather than the value, so a logcat capture taken on
        // the floor does not carry stock data off the device.
        Timber.i("scan received: %d chars via %s", decoded.length, intent?.action)
        onScan(decoded)
    }

    /**
     * Pulls the barcode out of whichever extra the vendor chose to put it in.
     *
     * The known keys are tried first; failing that, any non-blank string extra is taken. The
     * fallback is not laziness — vendors rename these between firmware revisions, and a scanner
     * that stops working after an OTA because a key was renamed is a far worse outcome than
     * occasionally reading an extra that happens to be a string. Everything read here is
     * validated downstream before it is used.
     */
    private fun extractBarcode(intent: Intent): String? {
        KNOWN_BARCODE_EXTRAS.forEach { key ->
            intent.getStringExtra(key)?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        }

        val extras = intent.extras ?: return null
        for (key in extras.keySet()) {
            @Suppress("DEPRECATION")
            val value = extras.get(key) as? String ?: continue
            if (value.isBlank()) continue
            Timber.i("scan broadcast used unrecognised extra '%s'; add it to KNOWN_BARCODE_EXTRAS", key)
            return value.trim()
        }
        return null
    }

    companion object {
        /**
         * Verified on the device this app runs on, by decompiling the scanner service rather than
         * by guessing: `com.sunmi.scanner` (ScannerHead 4.3.7) publishes exactly this action.
         *
         * Other vendors' actions are deliberately absent. Adding Zebra's or Honeywell's from
         * memory, with no device to test against, is how six wrong OData entity names got into
         * this codebase. Add them when there is hardware to confirm them on.
         */
        private const val SUNMI_DATA_CODE_RECEIVED = "com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED"

        private val ACTIONS = listOf(SUNMI_DATA_CODE_RECEIVED)

        private val KNOWN_BARCODE_EXTRAS = listOf("data", "code", "barcode", "scannerdata")

        /**
         * Registers for the foreground lifetime of the caller. Returns the receiver so it can be
         * unregistered again; leaving it registered leaks the activity it closes over.
         */
        fun register(context: Context, onScan: (String) -> Unit): OemScannerReceiver {
            val receiver = OemScannerReceiver(onScan)
            val filter = IntentFilter().apply { ACTIONS.forEach(::addAction) }

            // The scanner service is a separate app, so on Android 13+ this has to be declared
            // as an exported receiver or the broadcast is silently dropped.
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            Timber.i("listening for scan broadcasts on API %d", Build.VERSION.SDK_INT)
            return receiver
        }
    }
}
