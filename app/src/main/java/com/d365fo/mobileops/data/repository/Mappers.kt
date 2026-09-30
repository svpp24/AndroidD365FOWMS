package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.data.local.entity.ItemEntity
import com.d365fo.mobileops.data.local.entity.PurchLineEntity
import com.d365fo.mobileops.data.local.entity.PurchOrderEntity
import com.d365fo.mobileops.data.local.entity.PurchOrderWithLines
import com.d365fo.mobileops.data.remote.dto.InventoryLocationDto
import com.d365fo.mobileops.data.remote.dto.ProductBarcodeDto
import com.d365fo.mobileops.data.remote.dto.PurchaseOrderHeaderDto
import com.d365fo.mobileops.data.remote.dto.PurchaseOrderLineDto
import com.d365fo.mobileops.data.remote.dto.ReleasedProductDto
import com.d365fo.mobileops.data.local.entity.BarcodeEntity
import com.d365fo.mobileops.data.local.entity.LocationEntity
import com.d365fo.mobileops.data.local.entity.PurchaseOrderStatus
import com.d365fo.mobileops.data.local.entity.TrackingRequirement as TrackingEntity
import com.d365fo.mobileops.domain.model.DeliveryTolerance
import com.d365fo.mobileops.domain.model.OrderStatus
import com.d365fo.mobileops.domain.model.PurchaseOrder
import com.d365fo.mobileops.domain.model.PurchaseOrderLine
import com.d365fo.mobileops.domain.model.TrackingRequirement
import com.d365fo.mobileops.util.Formatters
import java.math.BigDecimal

// ---------------------------------------------------------------------------------------
// Room -> domain
// ---------------------------------------------------------------------------------------

/**
 * @param uncommittedByLine locally captured, unposted quantity per purchase line. Passed in
 *   rather than read here so the mapper stays pure and the repository can fetch all of them
 *   in one query instead of one per line.
 * @param trackingByItem tracking requirement per item, from the item master.
 */
fun PurchOrderWithLines.toDomain(
    uncommittedByLine: Map<String, BigDecimal> = emptyMap(),
    trackingByItem: Map<String, TrackingRequirement> = emptyMap(),
): PurchaseOrder {
    val headerTolerance = DeliveryTolerance(
        overPercent = order.overDeliveryPercent?.toBigDecimalOrNull(),
        underPercent = order.underDeliveryPercent?.toBigDecimalOrNull(),
    )
    return PurchaseOrder(
        purchId = order.purchId,
        vendorAccount = order.vendorAccount,
        vendorName = order.vendorName,
        status = order.status.toDomain(),
        siteId = order.siteId,
        warehouseId = order.warehouseId,
        deliveryDate = order.deliveryDate,
        approved = order.approved,
        tolerance = headerTolerance,
        lines = lines.map {
            it.toDomain(
                fallbackTolerance = headerTolerance,
                uncommitted = uncommittedByLine[it.lineId] ?: BigDecimal.ZERO,
                tracking = trackingByItem[it.itemId] ?: TrackingRequirement.NONE,
            )
        },
    )
}

fun PurchLineEntity.toDomain(
    fallbackTolerance: DeliveryTolerance,
    uncommitted: BigDecimal,
    tracking: TrackingRequirement,
): PurchaseOrderLine = PurchaseOrderLine(
    lineId = lineId,
    purchId = purchId,
    lineNumber = lineNumber,
    itemId = itemId,
    itemName = itemName,
    purchaseUnit = purchaseUnit,
    inventoryUnit = inventoryUnit,
    unitConversionFactor = unitConversionFactor.toBigDecimalOrNull() ?: BigDecimal.ONE,
    orderedQuantity = orderedQuantity.toBigDecimalOrNull() ?: BigDecimal.ZERO,
    receivedQuantity = receivedQuantity.toBigDecimalOrNull() ?: BigDecimal.ZERO,
    uncommittedQuantity = uncommitted,
    siteId = siteId,
    warehouseId = warehouseId,
    locationId = locationId,
    // A line percentage of null means "inherit"; a line percentage of 0 means "none allowed"
    // and must not fall back to the header.
    tolerance = DeliveryTolerance(
        overPercent = overDeliveryPercent?.toBigDecimalOrNull() ?: fallbackTolerance.overPercent,
        underPercent = underDeliveryPercent?.toBigDecimalOrNull() ?: fallbackTolerance.underPercent,
    ),
    tracking = tracking,
)

fun PurchaseOrderStatus.toDomain(): OrderStatus = when (this) {
    PurchaseOrderStatus.NONE -> OrderStatus.NONE
    PurchaseOrderStatus.BACKORDER -> OrderStatus.BACKORDER
    PurchaseOrderStatus.RECEIVED -> OrderStatus.RECEIVED
    PurchaseOrderStatus.INVOICED -> OrderStatus.INVOICED
    PurchaseOrderStatus.CANCELED -> OrderStatus.CANCELED
}

fun TrackingEntity.toDomain(): TrackingRequirement = when (this) {
    TrackingEntity.NONE -> TrackingRequirement.NONE
    TrackingEntity.BATCH -> TrackingRequirement.BATCH
    TrackingEntity.SERIAL -> TrackingRequirement.SERIAL
    TrackingEntity.BATCH_AND_SERIAL -> TrackingRequirement.BATCH_AND_SERIAL
}

// ---------------------------------------------------------------------------------------
// DTO -> Room
// ---------------------------------------------------------------------------------------

fun PurchaseOrderHeaderDto.toEntity(): PurchOrderEntity = PurchOrderEntity(
    purchId = purchaseOrderNumber,
    vendorAccount = vendorAccount,
    // PurchaseOrderHeadersV2 carries only the account number. Resolving a name would cost a
    // second call to the vendor master per order, so the worklist shows the account.
    vendorName = "",
    status = purchaseOrderStatus.toPurchaseStatus(),
    siteId = siteId,
    warehouseId = warehouseId,
    deliveryDate = Formatters.odataToEpochMillis(requestedDeliveryDate),
    currencyCode = currencyCode,
    approved = approvalStatus.isReceivableApproval(),
    overDeliveryPercent = null,
    underDeliveryPercent = null,
    // No ModifiedDateTime on this entity, so there is nothing to stamp and no delta sync.
    modifiedAt = 0L,
)

/**
 * @param inventoryUnit resolved from the item master; the PO line only carries the purchase
 *   unit.
 * @param conversionFactor purchase-unit to inventory-unit factor.
 * @param receivedQuantity summed from ProductReceiptLinesV2 by the caller. It is a separate
 *   parameter because PurchaseOrderLinesV2 publishes only the ordered quantity - defaulting
 *   it to zero would silently present every line as fully outstanding.
 */
fun PurchaseOrderLineDto.toEntity(
    inventoryUnit: String,
    conversionFactor: BigDecimal,
    receivedQuantity: BigDecimal,
): PurchLineEntity = PurchLineEntity(
    lineId = "$purchaseOrderNumber:$lineNumber",
    purchId = purchaseOrderNumber,
    lineNumber = lineNumber,
    itemId = itemNumber,
    itemName = lineDescription.orEmpty(),
    purchaseUnit = purchaseUnit.orEmpty(),
    inventoryUnit = inventoryUnit,
    unitConversionFactor = conversionFactor.toPlainString(),
    orderedQuantity = BigDecimal.valueOf(orderedQuantity).toPlainString(),
    receivedQuantity = receivedQuantity.toPlainString(),
    siteId = siteId,
    warehouseId = warehouseId,
    locationId = locationId,
    overDeliveryPercent = overDeliveryPercentage?.let { BigDecimal.valueOf(it).toPlainString() },
    underDeliveryPercent = underDeliveryPercentage?.let { BigDecimal.valueOf(it).toPlainString() },
    lineStatus = lineStatus.toPurchaseStatus(),
    // Neither RecId nor ModifiedDateTime is exposed; the (order, line) pair is the only key.
    serverRecId = null,
    modifiedAt = 0L,
)

/**
 * Whether a DocumentApprovalStatus permits receiving.
 *
 * "Approved" is not the only value that does, and treating it as such rejects real work: a
 * confirmed order is *more* progressed than an approved one, and confirmation is the step that
 * makes a purchase order receivable in the first place. Finalized orders are still receivable
 * against outstanding lines.
 *
 * A blank value means change management is switched off on the tenant, in which case every order
 * is implicitly approved — reading blank as "not approved" would hide the entire worklist.
 *
 * Everything else — Draft, InReview, Rejected, InExternalReview — is a genuine block, and the
 * flow says so by name rather than showing an empty item list.
 */
private fun String?.isReceivableApproval(): Boolean {
    if (isNullOrBlank()) return true
    return RECEIVABLE_APPROVAL_STATES.any { it.equals(this, ignoreCase = true) }
}

private val RECEIVABLE_APPROVAL_STATES = listOf("Approved", "Confirmed", "Finalized")

private fun String?.toPurchaseStatus(): PurchaseOrderStatus = when {
    this == null -> PurchaseOrderStatus.NONE
    equals("Backorder", true) -> PurchaseOrderStatus.BACKORDER
    equals("Received", true) -> PurchaseOrderStatus.RECEIVED
    equals("Invoiced", true) -> PurchaseOrderStatus.INVOICED
    equals("Canceled", true) || equals("Cancelled", true) -> PurchaseOrderStatus.CANCELED
    else -> PurchaseOrderStatus.NONE
}

fun ReleasedProductDto.toEntity(
    tracking: TrackingEntity,
    locationMandatory: Boolean,
): ItemEntity = ItemEntity(
    itemId = itemNumber,
    // ReleasedProductsV2 has no ProductName; SearchName is the only name it publishes.
    itemName = searchName.orEmpty(),
    searchName = searchName,
    inventoryUnit = inventoryUnit.orEmpty(),
    purchaseUnit = purchaseUnit,
    salesUnit = salesUnit,
    trackingRequirement = tracking,
    locationMandatory = locationMandatory,
    // Anything not explicitly Active is treated as unavailable to receive against.
    active = lifecycleState.isNullOrBlank() || lifecycleState.equals("Active", true),
    modifiedAt = Formatters.odataToEpochMillis(modifiedDateTime) ?: 0L,
)

fun ProductBarcodeDto.toEntity(): BarcodeEntity = BarcodeEntity(
    // Scanner output is normalised to upper case, so the stored key must match.
    barcode = barcode.uppercase(),
    itemId = itemNumber,
    quantityPerScan = (quantity ?: 1.0)
        .let { if (it <= 0.0) BigDecimal.ONE else BigDecimal.valueOf(it) }
        .toPlainString(),
    unitId = unitSymbol,
    // ProductBarcodesV3 has no active flag: a retired barcode is deleted rather than
    // deactivated, so anything the pull returns is by definition in use.
    active = true,
    modifiedAt = 0L,
)

fun InventoryLocationDto.toEntity(): LocationEntity = LocationEntity(
    // WarehouseLocations publishes no site. The column stays so the schema is unchanged,
    // but it is blank and LocationDao.find no longer matches on it.
    siteId = "",
    warehouseId = warehouseId,
    locationId = locationId,
    // No name either - the id is all the operator sees, which is what is on the rack label.
    locationName = null,
    // Blocking is a per-direction cause id rather than a boolean. A location blocked for
    // input cannot be received into and one blocked for output cannot be picked from, so
    // treating either as inactive is the conservative reading that keeps it off both lists.
    active = inputBlockingCause.isNullOrBlank() && outputBlockingCause.isNullOrBlank(),
    modifiedAt = 0L,
)
