package com.d365fo.mobileops.domain.ai

import kotlin.math.max
import kotlin.math.min

/** One detected object, in image pixels. */
data class Detection(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Float,
    val label: String = "",
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = max(0f, width) * max(0f, height)
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2

    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    fun iou(o: Detection): Float {
        val w = min(right, o.right) - max(left, o.left)
        val h = min(bottom, o.bottom) - max(top, o.top)
        if (w <= 0 || h <= 0) return 0f
        val inter = w * h
        return inter / (area + o.area - inter)
    }

    /** Share of the smaller box covered by the overlap - catches a box inside another. */
    fun overlapOfSmaller(o: Detection): Float {
        val w = min(right, o.right) - max(left, o.left)
        val h = min(bottom, o.bottom) - max(top, o.top)
        if (w <= 0 || h <= 0) return 0f
        return (w * h) / min(area, o.area).coerceAtLeast(1f)
    }
}

/** A square-ish piece of the image to run the detector on, with its offset in the full image. */
data class Tile(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * The counting rules on top of a generic object detector: tiling for small, dense items,
 * merging duplicates, and keeping only objects like the one the worker tapped.
 */
object CountDetections {

    /**
     * The whole image plus a [grid] x [grid] set of tiles overlapping by [overlap] - a shelf of
     * small cartons is a few pixels each at the detector's input size, but fills a tile.
     */
    fun tiles(width: Int, height: Int, grid: Int = 2, overlap: Float = 0.2f): List<Tile> {
        val out = mutableListOf(Tile(0, 0, width, height))
        if (grid <= 1) return out
        val tw = (width / (grid - (grid - 1) * overlap)).toInt()
        val th = (height / (grid - (grid - 1) * overlap)).toInt()
        for (r in 0 until grid) for (c in 0 until grid) {
            val x = if (grid == 1) 0 else ((width - tw) * c / (grid - 1))
            val y = if (grid == 1) 0 else ((height - th) * r / (grid - 1))
            out += Tile(x, y, tw, th)
        }
        return out
    }

    /** Non-maximum suppression across tiles: the best of overlapping boxes survives. */
    fun merge(all: List<Detection>, iouThreshold: Float = 0.45f, containThreshold: Float = 0.8f): List<Detection> {
        val kept = mutableListOf<Detection>()
        for (d in all.sortedByDescending { it.score }) {
            if (kept.none { it.iou(d) > iouThreshold || it.overlapOfSmaller(d) > containThreshold }) kept += d
        }
        return kept
    }

    /**
     * Drops boxes that cover most of the frame (the pallet or shelf itself, not an item) and
     * those under [minScore].
     */
    fun plausible(all: List<Detection>, imageWidth: Int, imageHeight: Int, minScore: Float): List<Detection> {
        val frame = imageWidth.toFloat() * imageHeight
        return all.filter { it.score >= minScore && it.area < frame * 0.5f && it.area > frame * 0.0002f }
    }

    /**
     * Objects like [sample]: similar size (area within [sizeRatio]x) and shape. The label is used
     * only when both have one, as a generic detector names a carton inconsistently.
     */
    fun like(sample: Detection, all: List<Detection>, sizeRatio: Float = 2.2f): List<Detection> {
        val aspect = sample.width / sample.height.coerceAtLeast(1f)
        return all.filter { d ->
            val r = d.area / sample.area.coerceAtLeast(1f)
            val a = d.width / d.height.coerceAtLeast(1f)
            r in (1 / sizeRatio)..sizeRatio &&
                a / aspect in 0.5f..2f &&
                (sample.label.isBlank() || d.label.isBlank() || d.label == sample.label || d.score < 0.5f || sample.score < 0.5f)
        }
    }
}

/**
 * The worker's view of one count photo: what the model proposed, the sample filter and the
 * worker's own corrections. [count] is what goes into the line.
 */
data class AiCount(
    val detections: List<Detection>,
    val sample: Detection? = null,
    /** Model boxes the worker struck out. */
    val removed: Set<Int> = emptySet(),
    /** Marks the worker added where the model saw nothing, in image pixels. */
    val added: List<Pair<Float, Float>> = emptyList(),
) {
    /** Indexes of [detections] that count. */
    val counted: List<Int>
        get() {
            val pool = sample?.let { s -> CountDetections.like(s, detections).toSet() }
            return detections.indices.filter { i -> i !in removed && (pool == null || detections[i] in pool) }
        }

    val count: Int get() = counted.size + added.size

    /**
     * A tap: on a counted box strikes it out, on a struck or filtered-out box brings it back, on
     * an added mark removes it, anywhere else adds a mark.
     */
    fun tap(x: Float, y: Float, markRadius: Float): AiCount {
        added.indexOfFirst { (ax, ay) -> (ax - x) * (ax - x) + (ay - y) * (ay - y) <= markRadius * markRadius }
            .takeIf { it >= 0 }?.let { i -> return copy(added = added.filterIndexed { j, _ -> j != i }) }
        val hit = detections.indices.filter { detections[it].contains(x, y) }.minByOrNull { detections[it].area }
            ?: return copy(added = added + (x to y))
        val countedNow = hit in counted
        return when {
            countedNow -> copy(removed = removed + hit)
            hit in removed -> copy(removed = removed - hit)
            else -> copy(added = added + (detections[hit].centerX to detections[hit].centerY))
        }
    }

    /** Count only objects like the one at ([x], [y]); null sample counts everything. */
    fun sampleAt(x: Float, y: Float): AiCount =
        copy(sample = detections.filter { it.contains(x, y) }.minByOrNull { it.area }, removed = emptySet())
}
