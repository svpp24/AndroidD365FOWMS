package com.d365fo.mobileops.domain.model

import java.math.BigDecimal

/**
 * Pure domain model — no Android, no Room, no serialization annotations.
 *
 * Quantities are [BigDecimal] throughout. Warehouse quantities are reconciled against
 * D365FO to six decimal places; binary floating point cannot represent 0.1 exactly, and an
 * accumulated rounding error across a 500-line counting journal produces a variance that
 * nobody can explain.
 */
data class PurchaseOrder(
    val purchId: String,
    val vendorAccount: String,
    val vendorName: String,
    val status: OrderStatus,
    val siteId: String?,
    val warehouseId: String?,
    val deliveryDate: Long?,
    val approved: Boolean,
    val tolerance: DeliveryTolerance,
    val lines: List<PurchaseOrderLine>,
) {
    val openLines: List<PurchaseOrderLine> get() = lines.filter { it.hasOutstanding }
    val lineCount: Int get() = lines.size
}

data class PurchaseOrderLine(
    val lineId: String,
    val purchId: String,
    val lineNumber: Double,
    val itemId: String,
    val itemName: String,
    /** Released product > Warehouse mobile device description lines - shown when filled in. */
    val mobileDescriptions: List<String> = emptyList(),
    val purchaseUnit: String,
    val inventoryUnit: String,
    /** Multiply a purchase-unit quantity by this to get inventory units. */
    val unitConversionFactor: BigDecimal,
    val orderedQuantity: BigDecimal,
    /** Posted on a product receipt in D365FO already. */
    val receivedQuantity: BigDecimal,
    /**
     * Captured on this device but not yet accepted by D365FO. Held separately from
     * [receivedQuantity] so the screen can show both what the server knows and what this
     * worker has added while offline.
     */
    val uncommittedQuantity: BigDecimal,
    val siteId: String?,
    val warehouseId: String?,
    val locationId: String?,
    /** Which variant of a product master the line is (product dimensions), from D365FO. */
    val variant: com.d365fo.mobileops.domain.model.ProductVariant = com.d365fo.mobileops.domain.model.ProductVariant(),
    val tolerance: DeliveryTolerance,
    val tracking: TrackingRequirement,
    /** Required vs optional per dimension for this process - see [com.d365fo.mobileops.domain.model.DimensionRules]. */
    val rules: com.d365fo.mobileops.domain.model.DimensionRules = com.d365fo.mobileops.domain.model.DimensionRules(),
    /**
     * Whether this item needs a put-away location, when the source already knows — the AOS
     * service resolves it per line (see PurchLineServiceDto.LocationRequired) alongside
     * [tracking], the same round trip the whole service exists to remove. Null means it was
     * not resolved here: the OData fallback cannot join that data this cheaply, and neither
     * can the offline Room cache, so both leave it null and the item step falls back to a
     * dedicated lookup instead. Non-null is therefore also the signal that [itemName],
     * [inventoryUnit], [purchaseUnit] and [tracking] on this line are already trustworthy
     * enough to skip that lookup entirely, not just this one field.
     */
    val locationRequired: Boolean? = null,
    /** Units the quantity can be entered in, from the item's conversion setup - see [ItemUnit]. */
    val units: List<com.d365fo.mobileops.domain.model.ItemUnit> = emptyList(),
) {
    /**
     * What this worker may still receive. Subtracting the uncommitted quantity is what
     * stops two offline receiving sessions on the same order from jointly over-receiving.
     */
    val outstandingQuantity: BigDecimal
        get() = (orderedQuantity - receivedQuantity - uncommittedQuantity).coerceAtLeast(BigDecimal.ZERO)

    val hasOutstanding: Boolean get() = outstandingQuantity.signum() > 0

}

/**
 * Over/under delivery percentages. D365FO holds these per line with a header fallback; a
 * null percentage means the order does not permit any deviation in that direction.
 */
data class DeliveryTolerance(
    val overPercent: BigDecimal?,
    val underPercent: BigDecimal?,
) {
    /** Largest quantity acceptable for an ordered quantity, including over-delivery. */
    fun maxAcceptable(ordered: BigDecimal): BigDecimal =
        overPercent?.let { ordered + ordered.multiply(it).divide(HUNDRED) } ?: ordered

    companion object {
        val NONE = DeliveryTolerance(null, null)
        private val HUNDRED = BigDecimal(100)
    }
}

enum class OrderStatus {
    NONE,
    BACKORDER,
    RECEIVED,
    INVOICED,
    CANCELED;

    val isOpen: Boolean get() = this == NONE || this == BACKORDER
}

enum class TrackingRequirement {
    NONE,
    BATCH,
    SERIAL,
    BATCH_AND_SERIAL;

    val requiresBatch: Boolean get() = this == BATCH || this == BATCH_AND_SERIAL
    val requiresSerial: Boolean get() = this == SERIAL || this == BATCH_AND_SERIAL
}
