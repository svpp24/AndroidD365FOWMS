package com.d365fo.mobileops.data.ai

import android.content.Context
import android.graphics.Bitmap
import com.d365fo.mobileops.domain.ai.CountDetections
import com.d365fo.mobileops.domain.ai.Detection
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device object detection for counting (EfficientDet-Lite2, int8, bundled as an asset - no
 * network, no image leaves the device). Runs on the whole photo and on overlapping tiles, so
 * small items on a dense shelf are found, then merges duplicates. Created on first use and kept,
 * since loading the model is the slow part.
 */
@Singleton
class ObjectCounter @Inject constructor(@ApplicationContext private val context: Context) {

    private var detector: ObjectDetector? = null
    private val mutex = Mutex()

    /** False when the model is not in this build - the AI count button is then hidden. */
    val isAvailable: Boolean by lazy {
        runCatching { context.assets.list("")?.contains(MODEL_ASSET) == true }.getOrDefault(false)
    }

    /**
     * One pass over the whole photo, or with [closer] also the overlapping tiles - several times
     * the work, for small items the whole-frame pass misses.
     */
    suspend fun detect(bitmap: Bitmap, closer: Boolean = false, minScore: Float = DEFAULT_MIN_SCORE): List<Detection> =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val started = System.currentTimeMillis()
                val d = detector ?: create().also { detector = it }
                val all = mutableListOf<Detection>()
                for (tile in CountDetections.tiles(bitmap.width, bitmap.height, grid = if (closer) TILE_GRID else 1)) {
                    val crop = if (tile.width == bitmap.width && tile.height == bitmap.height) bitmap
                    else Bitmap.createBitmap(bitmap, tile.left, tile.top, tile.width, tile.height)
                    val result = d.detect(BitmapImageBuilder(crop).build())
                    result.detections().forEach { det ->
                        val box = det.boundingBox()
                        val cat = det.categories().firstOrNull()
                        all += Detection(
                            left = box.left + tile.left,
                            top = box.top + tile.top,
                            right = box.right + tile.left,
                            bottom = box.bottom + tile.top,
                            score = cat?.score() ?: 0f,
                            label = cat?.categoryName().orEmpty(),
                        )
                    }
                    if (crop !== bitmap) crop.recycle()
                }
                CountDetections.merge(CountDetections.plausible(all, bitmap.width, bitmap.height, minScore)).also {
                    Timber.d("ai count: %d found in %d ms (closer=%b)", it.size, System.currentTimeMillis() - started, closer)
                }
            }
        }

    /** The detector, on the CPU (XNNPACK). */
    private fun create(): ObjectDetector {
        val started = System.currentTimeMillis()
        fun build(delegate: Delegate) = ObjectDetector.createFromOptions(
            context,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).setDelegate(delegate).build())
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(MAX_RESULTS)
                .setScoreThreshold(DETECTOR_FLOOR)
                .build(),
        )
        // CPU only: on low-end handhelds (MediaTek MT6761 / PowerVR, measured) the GPU delegate
        // is accepted but then stalls for minutes in GL setup - far slower than the CPU.
        val detector = build(Delegate.CPU)
        Timber.d("count detector loaded in %d ms", System.currentTimeMillis() - started)
        return detector
    }

    fun release() {
        detector?.close()
        detector = null
    }

    companion object {
        const val MODEL_ASSET = "count_detector.tflite"
        const val DEFAULT_MIN_SCORE = 0.25f
        private const val DETECTOR_FLOOR = 0.15f
        private const val MAX_RESULTS = 100
        private const val TILE_GRID = 2
    }
}
