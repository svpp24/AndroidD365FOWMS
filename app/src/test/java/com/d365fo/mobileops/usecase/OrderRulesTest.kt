package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.domain.order.QuantityRules
import com.d365fo.mobileops.domain.order.checkQuantity
import com.d365fo.mobileops.domain.order.qtyText
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OrderRulesTest {

    @Test
    fun `no rules - any positive quantity`() {
        assertThat(checkQuantity(3.0, QuantityRules())).isNull()
        assertThat(checkQuantity(0.0, QuantityRules())).isNotNull()
    }

    @Test
    fun `purchase order settings - min, max and multiple`() {
        val rules = QuantityRules(min = 10.0, max = 100.0, multiple = 5.0)
        assertThat(checkQuantity(5.0, rules)).contains("Minimum")
        assertThat(checkQuantity(105.0, rules)).contains("Maximum")
        assertThat(checkQuantity(12.0, rules)).contains("multiples of 5")
        assertThat(checkQuantity(15.0, rules)).isNull()
    }

    @Test
    fun `decimal multiples are not rounded wrongly`() {
        assertThat(checkQuantity(0.75, QuantityRules(multiple = 0.25))).isNull()
        assertThat(checkQuantity(0.7, QuantityRules(multiple = 0.25))).isNotNull()
    }

    @Test
    fun `transfer - more than available is refused, unknown stock is not checked`() {
        assertThat(checkQuantity(4.0, QuantityRules(available = 3.0))).contains("Only 3 available")
        assertThat(checkQuantity(3.0, QuantityRules(available = 3.0))).isNull()
        assertThat(checkQuantity(400.0, QuantityRules(available = null))).isNull()
    }

    @Test
    fun `hint and quantity text`() {
        assertThat(QuantityRules(min = 10.0, multiple = 5.0, available = 2.5).hint)
            .isEqualTo("Min 10 · multiples of 5 · 2.5 available")
        assertThat(qtyText(12.0)).isEqualTo("12")
    }
}
