package com.d365fo.mobileops.domain.model

/**
 * D365FO product dimensions - configuration, size, colour, style - that identify one variant of
 * a product master. Blank fields are dimensions the product does not use. Values come from
 * D365FO (journal lines, order lines, variant barcodes); nothing here is invented on the device.
 */
data class ProductVariant(
    val configId: String? = null,
    val sizeId: String? = null,
    val colorId: String? = null,
    val styleId: String? = null,
) {
    val isEmpty: Boolean
        get() = configId.isNullOrBlank() && sizeId.isNullOrBlank() && colorId.isNullOrBlank() && styleId.isNullOrBlank()

    /** "Config X · Size L · Colour Red · Style S", only the dimensions that are set. */
    val label: String
        get() = listOfNotNull(
            configId?.takeIf { it.isNotBlank() }?.let { "Configuration: $it" },
            sizeId?.takeIf { it.isNotBlank() }?.let { "Size: $it" },
            colorId?.takeIf { it.isNotBlank() }?.let { "Colour: $it" },
            styleId?.takeIf { it.isNotBlank() }?.let { "Style: $it" },
        ).joinToString(" \u00B7 ")

    /**
     * Whether a typed search finds this variant: some dimension value starts with [needle] -
     * "L" is size L, not the "l" in the "Colour" / "Style" words of [label].
     */
    fun matches(needle: String): Boolean =
        listOfNotNull(configId, sizeId, colorId, styleId).any { it.isNotBlank() && it.startsWith(needle, ignoreCase = true) }

    /**
     * Whether a scan of [scanned] (e.g. a variant barcode) can be this variant. Every dimension
     * the scan names must match; a dimension the scan leaves blank (an item-level barcode) does
     * not narrow anything.
     */
    fun acceptsScanOf(scanned: ProductVariant): Boolean =
        same(configId, scanned.configId) && same(sizeId, scanned.sizeId) &&
            same(colorId, scanned.colorId) && same(styleId, scanned.styleId)

    private fun same(mine: String?, scanned: String?): Boolean =
        scanned.isNullOrBlank() || mine.equals(scanned, ignoreCase = true)

    companion object {
        fun of(configId: String?, sizeId: String?, colorId: String?, styleId: String?) = ProductVariant(
            configId?.trim()?.takeIf { it.isNotEmpty() },
            sizeId?.trim()?.takeIf { it.isNotEmpty() },
            colorId?.trim()?.takeIf { it.isNotEmpty() },
            styleId?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}
