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

    private val _scans = MutableSharedFlow<RawScan>(extraBufferCapacity = 8)
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
                if (char.isLetterOrDigit() || char in ALLOWED_SYMBOLS) {
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

    fun submitManualEntry(value: String) {
        publish(RawScan(value = value, source = ScanSource.MANUAL_ENTRY))
    }

    private fun publish(scan: RawScan) {
        lastScanAtMs = System.currentTimeMillis()
        _scans.tryEmit(scan)
    }

    /**
     * True when a scan of any source was published within [withinMs].
     *
     * Some rugged handhelds' physical scan trigger fires a raw BACK key event on the same
     * press that produces the barcode broadcast - confirmed live on this device: a failed
     * lookup left the flow on its first step, and the very next thing to arrive was a BACK
     * key that popped the whole screen to Home, even though nothing was tapped or swiped. A
     * genuine user back-press never lands in the same instant as a scan, so [MainActivity]
     * uses this to tell the two apart and swallow only the phantom one.
     */
    fun justScanned(withinMs: Long = JUST_SCANNED_WINDOW_MS): Boolean =
        System.currentTimeMillis() - lastScanAtMs < withinMs

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
        const val MAX_BARCODE_LENGTH = 64
        /** Wedges type far faster than a human; 100 ms comfortably separates the two. */
        const val INTER_KEY_TIMEOUT_MS = 100L
        const val VIRTUAL_DEVICE_ID = -1
        val ALLOWED_SYMBOLS = setOf('-', '_', '.', '/', '+', ' ', '(', ')')
        /** See [justScanned] - comfortably longer than the gap between a scan and its phantom key. */
        const val JUST_SCANNED_WINDOW_MS = 500L
    }
}
