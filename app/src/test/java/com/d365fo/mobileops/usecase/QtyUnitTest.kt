package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.util.Formatters
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class QtyUnitTest {

    @Test
    fun `one of a plain plural is singular, keeping its case`() {
        assertThat(Formatters.qtyUnit(BigDecimal.ONE, "Pieces")).isEqualTo("1 Piece")
        assertThat(Formatters.qtyUnit(BigDecimal("1.000000"), "Boxes")).isEqualTo("1 Box")
        assertThat(Formatters.qtyUnit(BigDecimal.ONE, "PACKS")).isEqualTo("1 PACK")
    }

    @Test
    fun `other quantities and unit codes are left alone`() {
        assertThat(Formatters.qtyUnit(BigDecimal("2"), "Pieces")).isEqualTo("2 Pieces")
        assertThat(Formatters.qtyUnit(BigDecimal("1.5"), "Pack")).isEqualTo("1.5 Pack")
        assertThat(Formatters.qtyUnit(BigDecimal.ONE, "PCS")).isEqualTo("1 PCS")
        assertThat(Formatters.qtyUnit(BigDecimal.ONE, "Pack")).isEqualTo("1 Pack")
        assertThat(Formatters.qtyUnit(BigDecimal("3"), null)).isEqualTo("3")
    }
}
