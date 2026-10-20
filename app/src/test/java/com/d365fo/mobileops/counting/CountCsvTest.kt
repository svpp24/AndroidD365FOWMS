package com.d365fo.mobileops.counting

import com.d365fo.mobileops.data.counting.CountCsv
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CountCsvTest {

    @Test
    fun `one row per line in line order, uncounted lines with an empty count`() {
        val csv = CountCsv.of("CNT-1", listOf(countLine(2, "B", "L2"), countLine(1, "A", "L1", counted = "3")))
        val rows = csv.trimEnd().split("\r\n")

        assertThat(rows).hasSize(3)
        assertThat(rows[0]).startsWith("Journal,Line,Item,Item name")
        assertThat(rows[1]).isEqualTo("CNT-1,1,A,A,,,,,L1,,,3,,")
        assertThat(rows[2]).isEqualTo("CNT-1,2,B,B,,,,,L2,,,,,")
    }

    @Test
    fun `a name with a comma or a quote stays one column`() {
        val line = countLine(1, "A", "L1").copy(itemName = "PANADOL, \"ADVANCE\" 48S")
        val row = CountCsv.of("CNT-1", listOf(line)).trimEnd().split("\r\n")[1]

        assertThat(row).isEqualTo("CNT-1,1,A,\"PANADOL, \"\"ADVANCE\"\" 48S\",,,,,L1,,,,,")
    }
}
