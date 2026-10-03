package com.d365fo.mobileops.data.ai

import android.content.Context
import android.graphics.Bitmap
import com.d365fo.mobileops.domain.ai.AiCount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.random.Random

/**
 * AI count training samples waiting to upload to D365FO (HSMobileOpsAiSampleSvc), where every
 * device and app of the company shares them. Kept small on purpose:
 * - only counts the worker corrected are kept (they are what teaches a model), plus 1 in
 *   [UNCHANGED_EVERY] unchanged ones;
 * - photos are [MAX_SIDE] px JPEGs at quality [JPEG_QUALITY] (about 30-40 KB);
 * - at most [MAX_WAITING] wait on the device, and each is deleted once D365FO has it.
 */
@Singleton
class TrainingSampleStore @Inject constructor(@ApplicationContext private val context: Context) {

    /** One sample ready to upload. */
    data class Pending(val id: String, val json: JSONObject, val image: File, val annotation: File)

    private val dir: File get() = File(context.filesDir, "ai_training").apply { mkdirs() }

    private val _waiting = MutableStateFlow(0)
    val waiting: StateFlow<Int> = _waiting.asStateFlow()

    init {
        runCatching { _waiting.value = annotations().size }
    }

    /** Saves the sample when it is worth keeping; true when one was saved. */
    suspend fun save(photo: Bitmap, count: AiCount, itemId: String): Boolean = withContext(Dispatchers.IO) {
        val corrections = count.removed.size + count.added.size
        if (corrections == 0 && count.sample == null && Random.nextInt(UNCHANGED_EVERY) != 0) return@withContext false
        runCatching {
            val id = "${System.currentTimeMillis()}-${Random.nextInt(1000, 9999)}"
            val scale = minOf(1f, MAX_SIDE.toFloat() / max(photo.width, photo.height))
            val w = (photo.width * scale).toInt()
            val h = (photo.height * scale).toInt()
            val image = if (scale < 1f) {
                Bitmap.createScaledBitmap(photo, w, h, true)
            } else photo
            File(dir, "$id.jpg").outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (image !== photo) image.recycle()

            // Compact marks: [left, top, right, bottom, kept] per box, [x, y] per added mark.
            val kept = count.counted.toSet()
            val boxes = JSONArray()
            count.detections.forEachIndexed { i, d ->
                boxes.put(JSONArray().put((d.left * scale).toInt()).put((d.top * scale).toInt())
                    .put((d.right * scale).toInt()).put((d.bottom * scale).toInt()).put(if (i in kept) 1 else 0))
            }
            val added = JSONArray()
            count.added.forEach { (x, y) -> added.put(JSONArray().put((x * scale).toInt()).put((y * scale).toInt())) }
            val json = JSONObject()
                .put("itemId", itemId)
                .put("count", count.count)
                .put("corrections", corrections)
                .put("w", w)
                .put("h", h)
                .put("boxes", boxes).put("added", added)
            File(dir, "$id.json").writeText(json.toString())
            trim()
            _waiting.value = annotations().size
            true
        }.onFailure { Timber.w(it, "training sample not saved") }.getOrDefault(false)
    }

    /** Oldest first, so a backlog drains in capture order. */
    fun pending(): List<Pending> = annotations().mapNotNull { a ->
        val image = File(dir, a.nameWithoutExtension + ".jpg")
        if (!image.exists()) { a.delete(); return@mapNotNull null }
        runCatching { Pending(a.nameWithoutExtension, JSONObject(a.readText()), image, a) }.getOrNull()
    }

    /** D365FO has it (or declined it for good) - the device copy goes. */
    fun done(p: Pending) {
        p.image.delete()
        p.annotation.delete()
        _waiting.value = annotations().size
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dir.listFiles().orEmpty().forEach { it.delete() }
        _waiting.value = 0
    }

    private fun annotations(): List<File> = dir.listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }

    private fun trim() {
        val all = annotations()
        all.take(max(0, all.size - MAX_WAITING)).forEach { json ->
            json.delete()
            File(dir, json.nameWithoutExtension + ".jpg").delete()
        }
    }

    private companion object {
        const val MAX_WAITING = 50
        const val MAX_SIDE = 640
        const val JPEG_QUALITY = 70
        const val UNCHANGED_EVERY = 10
    }
}
