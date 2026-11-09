package com.d365fo.mobileops.counting

import com.d365fo.mobileops.data.counting.lineTotalOfBins
import com.d365fo.mobileops.data.counting.normalizeBin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountBinsTest {

    @Test
    fun `a line's count is what its bins add up to`() {
        assertEquals("12", lineTotalOfBins(listOf("5", "7")))
        assertEquals("2.5", lineTotalOfBins(listOf("1.5", "1")))
    }

    @Test
    fun `no bins left means no count, while bins counted empty mean zero`() {
        assertNull(lineTotalOfBins(emptyList()))
        assertEquals("0", lineTotalOfBins(listOf("0", "0")))
    }

    @Test
    fun `a whole number keeps its zeros`() {
        assertEquals("100", lineTotalOfBins(listOf("60", "40")))
    }

    @Test
    fun `a bin is trimmed and upper case, so typing meets what was set up`() {
        assertEquals("BIN-A1", normalizeBin("  bin-a1 "))
    }
}
