package com.d365fo.mobileops.counting

import com.d365fo.mobileops.domain.model.COUNT_MODE_DOUBLE_BLIND
import com.d365fo.mobileops.domain.model.COUNT_MODE_SINGLE
import com.d365fo.mobileops.domain.model.keepsServerCounts
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RecountRulesTest {

    @Test
    fun `a first single count picks up counts already in D365FO`() {
        assertThat(keepsServerCounts(passNumber = 1, countMode = COUNT_MODE_SINGLE)).isTrue()
    }

    @Test
    fun `a recount never shows the previous count`() {
        assertThat(keepsServerCounts(passNumber = 2, countMode = COUNT_MODE_SINGLE)).isFalse()
    }

    @Test
    fun `a double-blind pass never shows the other counter's count`() {
        assertThat(keepsServerCounts(passNumber = 1, countMode = COUNT_MODE_DOUBLE_BLIND)).isFalse()
        assertThat(keepsServerCounts(passNumber = 2, countMode = COUNT_MODE_DOUBLE_BLIND)).isFalse()
    }
}
