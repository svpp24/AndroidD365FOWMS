package com.d365fo.mobileops.counting

import com.d365fo.mobileops.domain.model.ProductVariant
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ProductVariantTest {

    private val redLarge = ProductVariant.of(null, "L", "RED", null)

    @Test
    fun `an item-level barcode matches every variant of the item`() {
        assertThat(redLarge.acceptsScanOf(ProductVariant())).isTrue()
    }

    @Test
    fun `a variant barcode matches only its own variant`() {
        assertThat(redLarge.acceptsScanOf(ProductVariant.of(null, "L", "red", null))).isTrue()
        assertThat(redLarge.acceptsScanOf(ProductVariant.of(null, "M", "RED", null))).isFalse()
        assertThat(ProductVariant().acceptsScanOf(ProductVariant.of(null, null, "RED", null))).isFalse()
    }

    @Test
    fun `label names only the dimensions that are set`() {
        assertThat(redLarge.label).isEqualTo("Size: L · Colour: RED")
        assertThat(ProductVariant.of(" ", "", null, null).isEmpty).isTrue()
    }
}
