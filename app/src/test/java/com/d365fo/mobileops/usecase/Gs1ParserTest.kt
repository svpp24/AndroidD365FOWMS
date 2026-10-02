package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.domain.model.Gs1Parser
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class Gs1ParserTest {

    @Test
    fun `GS1 DataMatrix with AIM prefix and GS separators`() {
        val d = Gs1Parser.parse("]d20109506000134352172612311" + "0abc123\u001D21SN-0001")!!
        assertThat(d.gtin).isEqualTo("09506000134352")
        assertThat(d.expiry).isEqualTo(LocalDate.of(2026, 12, 31))
        assertThat(d.batch).isEqualTo("abc123")
        assertThat(d.serial).isEqualTo("SN-0001")
    }

    @Test
    fun `human readable form with parentheses and a count`() {
        val d = Gs1Parser.parse("(01)09506000134352(10)L42(37)12")!!
        assertThat(d.batch).isEqualTo("L42")
        assertThat(d.quantity).isEqualTo(BigDecimal("12"))
    }

    @Test
    fun `day 00 is the last day of the month`() {
        assertThat(Gs1Parser.parse("01095060001343521727020" + "0")!!.expiry).isEqualTo(LocalDate.of(2027, 2, 28))
    }

    @Test
    fun `a plain EAN or item number is not GS1`() {
        assertThat(Gs1Parser.parse("5012345678900")).isNull()
        assertThat(Gs1Parser.parse("BSR-24583-0")).isNull()
    }

    @Test
    fun `GTIN-14 is also tried as GTIN-13`() {
        assertThat(Gs1Parser.parse("(01)05012345678900")!!.gtinCandidates).containsAtLeast("05012345678900", "5012345678900")
    }
}
