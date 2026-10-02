package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.domain.model.ItemUnit
import com.d365fo.mobileops.domain.model.UnitConversion
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class UnitConversionTest {

    private val units = listOf(
        ItemUnit("PCS", BigDecimal.ONE, 0),
        ItemUnit("BOX", BigDecimal("12"), 0),
        ItemUnit("DZ", BigDecimal("12"), 2),
    )

    private fun parse(v: String, selected: String? = null) = UnitConversion.parse(v, units, selected, "PCS")

    @Test
    fun `plain number is in the line unit`() {
        assertThat(parse("5").getOrThrow().quantity).isEqualTo(BigDecimal("5"))
    }

    @Test
    fun `typed unit converts`() {
        assertThat(parse("2 BOX").getOrThrow().quantity).isEqualTo(BigDecimal("24"))
        assertThat(parse("2box").getOrThrow().quantity).isEqualTo(BigDecimal("24"))
    }

    @Test
    fun `selected chip converts a plain number`() {
        val c = parse("3", selected = "BOX").getOrThrow()
        assertThat(c.quantity).isEqualTo(BigDecimal("36"))
        assertThat(c.enteredUnit).isEqualTo("BOX")
    }

    @Test
    fun `result rounds to the line unit decimals`() {
        assertThat(parse("0.55 DZ").getOrThrow().quantity).isEqualTo(BigDecimal("7"))
    }

    @Test
    fun `unit without conversion is refused`() {
        assertThat(parse("2 KG").isFailure).isTrue()
        assertThat(parse("abc").isFailure).isTrue()
    }

    @Test
    fun `pack barcode in another unit converts`() {
        assertThat(UnitConversion.packInLineUnit(BigDecimal.ONE, "BOX", units, "PCS")).isEqualTo(BigDecimal("12"))
        assertThat(UnitConversion.packInLineUnit(BigDecimal.ONE, "PCS", units, "PCS")).isNull()
    }
}
