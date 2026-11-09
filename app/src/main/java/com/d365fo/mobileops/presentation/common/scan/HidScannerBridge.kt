package com.d365fo.mobileops.presentation.common.scan

import android.view.InputDevice
import android.view.KeyEvent
import com.d365fo.mobileops.domain.model.RawScan
import com.d365fo.mobileops.domain.model.ScanSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Intercepts hardware-wedge scanner input at the Activity level.
 *
 * Rugged handhelds (Zebra, Honeywell, Datalogic) present their imager as a HID keyboard
 * that types the barcode and finishes with Enter. Left alone, those keystrokes go to
 * whatever field happens to hold focus — which is how a scan ends up half-typed into a
 * quantity box. Consuming them centrally and republishing as a [RawScan] means the screen
 * decides what a scan means, rather than the focus system deciding for it.
 *
 * Physical keyboards are distinguished from the on-screen one by device source, so a
 * warehouse tablet with a docked keyboard still allows manual typing.
 */
@Singleton
class HidScannerBridge @Inject constructor() {

    private val buffer = StringBuilder(BUFFER_INITIAL_CAPACITY)
    private var lastKeyAt = 0L
    private var lastScanAtMs = 0L

    // 64, not 8: tryEmit discards a scan once the buffer is full behind a slow collector, and a
    // worker scanning a stack of labels can queue more than 8 while a lookup runs (redesign brief
    // §3: rapid scanning never drops one). ScanRouter takes each one straight into its own
    // unbounded queue; a drop that still happens is logged in publish().
    private val _scans = MutableSharedFlow<RawScan>(extraBufferCapacity = 64)
    val scans: SharedFlow<RawScan> = _scans.asSharedFlow()

    /** Screens that own a text field can suspend interception while the worker types. */
    @Volatile
    var enabled: Boolean = true

    /**
     * Called from `Activity.dispatchKeyEvent`.
     *
     * @return true when the event was consumed and must not reach the view hierarchy.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!enabled) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (!isFromScanner(event)) return false

        val now = System.currentTimeMillis()

        // A wedge emits an entire barcode in a few milliseconds. A gap longer than the
        // threshold means these are unrelated keystrokes, so the partial buffer is dropped
        // rather than being prepended to the next scan.
        if (now - lastKeyAt > INTER_KEY_TIMEOUT_MS) {
            buffer.setLength(0)
        }
        lastKeyAt = now

        return when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB -> {
                emitBuffered()
                true
            }

            else -> {
                val char = event.unicodeChar.toChar()
                // GS (0x1D) is how a scanner sends GS1's FNC1 separator - keep it for Gs1Parser.
                if (char.isLetterOrDigit() || char in ALLOWED_SYMBOLS || char == '\u001D') {
                    if (buffer.length < MAX_BARCODE_LENGTH) buffer.append(char)
                    true
                } else {
                    // Modifier and navigation keys pass through untouched.
                    false
                }
            }
        }
    }

    private fun emitBuffered() {
        val value = buffer.toString()
        buffer.setLength(0)
        if (value.isBlank()) return
        publish(RawScan(value = value, source = ScanSource.HID_WEDGE))
    }

    /**
     * A scan that arrived as a vendor broadcast rather than as keystrokes.
     *
     * Published on the same flow as every other source, so no screen needs to know or care which
     * kind of scanner the device it is running on happens to have.
     */
    fun submitBroadcastScan(value: String) {
        publish(RawScan(value = value, source = ScanSource.IMAGER_BROADCAST))
    }

    /** Camera and manual paths publish through the same flow so screens have one input. */
    fun submitCameraScan(value: String) {
        publish(RawScan(value = value, source = ScanSource.CAMERA))
    }

    /** A code typed in the scan pill: Enter is treated exactly like a scan (redesign brief section 4). */
    fun submitTypedScan(value: String) {
        if (value.isBlank()) return
        publish(RawScan(value = value.trim(), source = ScanSource.MANUAL_ENTRY))
    }

    private fun publish(scan: RawScan) {
        lastScanAtMs = System.currentTimeMillis()
        // A dialog on top that is waiting for a scan gets it - not the screen underneath, which
        // would take a bin for a transfer order ("No transfer order AAC").
        modalCapture?.let { capture -> capture(scan.value); return }
        if (!_scans.tryEmit(scan)) timber.log.Timber.w("scan dropped: buffer full (%d chars)", scan.value.length)
    }

    /**
     * Set while a dialog takes scans itself (Report discrepancy's location); null otherwise.
     * Whoever sets it clears it when the dialog closes.
     */
    @Volatile
    var modalCapture: ((String) -> Unit)? = null

    /**
     * True when a scan of any source was published within [withinMs].
     *
     * Some rugged handhelds' physical scan trigger fires a phantom system Back invocation on
     * the same press that produces the barcode broadcast - confirmed live on this device with
     * diagnostic tracing: a failed lookup left the flow on its first step, and roughly a
     * second later - well after the lookup's own response - Compose's BackHandler fired and
     * popped the whole screen to Home, even though nothing was tapped or swiped. Traced with
     * logging in MainActivity.dispatchKeyEvent first, which proved this does *not* arrive as a
     * KEYCODE_BACK KeyEvent at all - on this app's target SDK, Android routes back through the
     * predictive-back OnBackInvokedCallback channel instead, which bypasses dispatchKeyEvent
     * entirely. So the suppression has to live at the one place both a genuine back-press and
     * this phantom one are guaranteed to surface identically: each screen's own BackHandler
     * callback (see ReceivingFlowScreen/TransferFlowScreen), not the Activity's key dispatch.
     * A genuine user back-press essentially never lands within a second of an actual scan, so
     * this only ever swallows the phantom one.
     */
    fun justScanned(withinMs: Long = JUST_SCANNED_WINDOW_MS): Boolean {
        val elapsed = System.currentTimeMillis() - lastScanAtMs
        // Temporary diagnostic - the fixed window has missed this live at least twice despite
        // being in the right place, so the actual gap needs to be measured directly rather
        // than guessed at again. Remove once the real gap (and a window that reliably covers
        // it) is confirmed.
        timber.log.Timber.tag("BackKeyDiag").d("justScanned check: elapsed=%dms window=%dms", elapsed, withinMs)
        return elapsed < withinMs
    }

    /**
     * A wedge reports itself as a keyboard on a non-virtual device. The virtual check is
     * what keeps the soft keyboard from being mistaken for a scanner.
     */
    private fun isFromScanner(event: KeyEvent): Boolean {
        if (event.deviceId == VIRTUAL_DEVICE_ID) return false
        val device = InputDevice.getDevice(event.deviceId) ?: return false
        return device.sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD &&
            device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
    }

    private companion object {
        const val BUFFER_INITIAL_CAPACITY = 32
        /** GS1 DataMatrix element strings run well past 64 characters. */
        const val MAX_BARCODE_LENGTH = 256
        /** Wedges type far faster than a human; 100 ms comfortably separates the two. */
        const val INTER_KEY_TIMEOUT_MS = 100L
        const val VIRTUAL_DEVICE_ID = -1
        val ALLOWED_SYMBOLS = setOf('-', '_', '.', '/', '+', ' ', '(', ')')
        /**
         * See [justScanned]. Widened from an initial 500ms after a live repro showed the
         * phantom back invocation landing ~1s after the scan - the earlier window was too
         * short to have ever caught it. 2s comfortably covers that with margin without making
         * a genuine quick back-press right after a scan a likely false-positive.
         */
        const val JUST_SCANNED_WINDOW_MS = 2_000L
    }
}
