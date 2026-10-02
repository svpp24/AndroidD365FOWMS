package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.domain.ai.AiCount
import com.d365fo.mobileops.domain.ai.CountDetections
import com.d365fo.mobileops.domain.ai.Detection
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CountDetectionsTest {

    private fun box(x: Float, y: Float, w: Float = 100f, h: Float = 80f, s: Float = 0.6f, label: String = "") =
        Detection(x, y, x + w, y + h, s, label)

    @Test
    fun `duplicates from overlapping tiles merge to one`() {
        val merged = CountDetections.merge(listOf(box(0f, 0f, s = 0.7f), box(5f, 3f, s = 0.5f), box(300f, 0f)))
        assertThat(merged).hasSize(2)
        assertThat(merged.first().score).isEqualTo(0.7f)
    }

    @Test
    fun `tiles cover the image with the whole frame first`() {
        val t = CountDetections.tiles(1000, 800, grid = 2)
        assertThat(t).hasSize(5)
        assertThat(t.first().width).isEqualTo(1000)
        assertThat(t.last().left + t.last().width).isAtMost(1000)
    }

    @Test
    fun `frame sized and weak boxes are not items`() {
        val kept = CountDetections.plausible(listOf(box(0f, 0f, 900f, 700f), box(10f, 10f, s = 0.1f), box(200f, 200f)), 1000, 800, 0.3f)
        assertThat(kept).hasSize(1)
    }

    @Test
    fun `sample keeps similar objects only`() {
        val c = AiCount(listOf(box(0f, 0f), box(200f, 0f), box(400f, 0f, w = 400f, h = 300f)))
        assertThat(c.count).isEqualTo(3)
        assertThat(c.sampleAt(50f, 40f).count).isEqualTo(2)
    }

    @Test
    fun `taps strike out, restore and add`() {
        var c = AiCount(listOf(box(0f, 0f), box(200f, 0f)))
        c = c.tap(50f, 40f, 20f)
        assertThat(c.count).isEqualTo(1)
        c = c.tap(50f, 40f, 20f)
        assertThat(c.count).isEqualTo(2)
        c = c.tap(600f, 600f, 20f)
        assertThat(c.count).isEqualTo(3)
        c = c.tap(605f, 598f, 20f)
        assertThat(c.count).isEqualTo(2)
    }
}
