package com.d365fo.mobileops.counting

import com.d365fo.mobileops.domain.model.CountingLayout
import com.d365fo.mobileops.domain.model.ORIGIN_WALL_TO_WALL
import com.d365fo.mobileops.domain.model.countingLayout
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CountingLayoutTest {

    @Test
    fun `wall-to-wall is always location-first`() {
        assertThat(countingLayout(ORIGIN_WALL_TO_WALL, 40, binsRequired = false)).isEqualTo(CountingLayout.WALL_TO_WALL)
        assertThat(countingLayout(ORIGIN_WALL_TO_WALL, 1, binsRequired = true)).isEqualTo(CountingLayout.WALL_TO_WALL)
    }

    @Test
    fun `a triggered or opportunity check of one line is focused`() {
        assertThat(countingLayout("TRIGGERED", 1, binsRequired = false)).isEqualTo(CountingLayout.FOCUSED)
        assertThat(countingLayout("OPPORTUNITY", 1, binsRequired = false)).isEqualTo(CountingLayout.FOCUSED)
    }

    @Test
    fun `several lines, bins or any other origin keep the list`() {
        assertThat(countingLayout("TRIGGERED", 6, binsRequired = false)).isEqualTo(CountingLayout.STANDARD)
        assertThat(countingLayout("TRIGGERED", 1, binsRequired = true)).isEqualTo(CountingLayout.STANDARD)
        assertThat(countingLayout("SCHEDULED", 1, binsRequired = false)).isEqualTo(CountingLayout.STANDARD)
        assertThat(countingLayout("SPOT", 1, binsRequired = false)).isEqualTo(CountingLayout.STANDARD)
        assertThat(countingLayout(null, 0, binsRequired = false)).isEqualTo(CountingLayout.STANDARD)
    }
}
