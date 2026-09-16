package com.d365fo.mobileops.presentation.common.scan

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.d365fo.mobileops.util.Constants
import timber.log.Timber

/**
 * ML Kit analyzer for the camera scan path.
 *
 * Two things here exist because of how a camera behaves versus how a laser wedge behaves.
 *
 * A wedge fires once per trigger pull. A camera analyses every frame, so the same label
 * decodes twenty times a second — without [lastValue] deduping, one pallet label would
 * enqueue twenty receipt lines before the worker could lift their thumb. The same value is
 * suppressed for [Constants.SCAN_DEBOUNCE_MS]; a *different* value passes immediately, so
 * scanning two labels in quick succession still works.
 *
 * The format list is explicit rather than `FORMAT_ALL_FORMATS`: restricting the set measurably
 * speeds up decoding, and it stops the scanner resolving, say, a QR code on a shipping
 * document when the worker meant to scan the EAN beside it.
 */
class BarcodeAnalyzer(
    private val onDecoded: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_QR_CODE,
            )
            .build(),
    )

    private var lastValue: String? = null
    private var lastAt: Long = 0L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (media == null) {
            image.close()
            return
        }

        scanner.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
            .addOnSuccessListener { codes ->
                val value = codes.firstNotNullOfOrNull { it.rawValue }?.takeIf { it.isNotBlank() }
                if (value != null && accept(value)) onDecoded(value)
            }
            .addOnFailureListener { Timber.w(it, "barcode decode failed") }
            // Closing in onComplete rather than immediately: the ImageProxy backs the buffer
            // ML Kit is still reading, and releasing it early stalls the analyzer.
            .addOnCompleteListener { image.close() }
    }

    private fun accept(value: String): Boolean {
        val now = System.currentTimeMillis()
        if (value == lastValue && now - lastAt < Constants.SCAN_DEBOUNCE_MS) return false
        lastValue = value
        lastAt = now
        return true
    }

    fun release() = scanner.close()
}
