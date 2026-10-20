package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ReleasedProductsV2 — item master.
 *
 * Field names verified against a live instance. Two surprises worth keeping written down:
 *
 *  - **There is no `ProductName`.** The entity publishes `SearchName` and `ProductSearchName`
 *    and nothing else name-like; the translated product name lives on a separate translation
 *    entity that would cost a second query per item. `SearchName` is what an operator sees.
 *  - **Batch and serial flags are on the product itself**, as `IsBatchNumberActive` and
 *    `IsSerialNumberActive`. The app used to join TrackingDimensionGroups to learn this. It no
 *    longer does — that was an entire round trip per item lookup for data already in hand.
 */
@Serializable
data class ReleasedProductDto(
    @SerialName("dataAreaId") val dataAreaId: String? = null,
    @SerialName("ItemNumber") val itemNumber: String,
    @SerialName("SearchName") val searchName: String? = null,
    @SerialName("InventoryUnitSymbol") val inventoryUnit: String? = null,
    @SerialName("PurchaseUnitSymbol") val purchaseUnit: String? = null,
    @SerialName("SalesUnitSymbol") val salesUnit: String? = null,
    @SerialName("ItemModelGroupId") val itemModelGroupId: String? = null,
    @SerialName("TrackingDimensionGroupName") val trackingDimensionGroup: String? = null,
    @SerialName("StorageDimensionGroupName") val storageDimensionGroup: String? = null,
    @SerialName("IsBatchNumberActive")
    @Serializable(with = NoYesSerializer::class)
    val batchActive: Boolean? = null,
    @SerialName("IsSerialNumberActive")
    @Serializable(with = NoYesSerializer::class)
    val serialActive: Boolean? = null,
    @SerialName("IsCatchWeightProduct")
    @Serializable(with = NoYesSerializer::class)
    val catchWeight: Boolean? = null,
    @SerialName("ProductLifecycleStateId") val lifecycleState: String? = null,
    @SerialName("ModifiedDateTime") val modifiedDateTime: String? = null,
)

/**
 * TrackingDimensionGroups — pulled once per shift and joined client-side to
 * [ReleasedProductDto.trackingDimensionGroup]. Doing the join on the device avoids an
 * `$expand` on a 100k-row item pull.
 */
@Serializable
data class TrackingDimensionGroupDto(
    @SerialName("dataAreaId") val dataAreaId: String? = null,
    @SerialName("TrackingDimensionGroupName") val name: String,
    @SerialName("IsBatchNumberActive")
    @Serializable(with = NoYesSerializer::class)
    val batchActive: Boolean? = null,
    @SerialName("IsSerialNumberActive")
    @Serializable(with = NoYesSerializer::class)
    val serialActive: Boolean? = null,
)

/** BarCodes / EcoResProductBarcode. */
@Serializable
data class ProductBarcodeDto(
    @SerialName("dataAreaId") val dataAreaId: String? = null,
    @SerialName("Barcode") val barcode: String,
    @SerialName("ItemNumber") val itemNumber: String = "",
    @SerialName("ProductQuantity") val quantity: Double? = null,
    @SerialName("ProductQuantityUnitSymbol") val unitSymbol: String? = null,
    /** No IsActive on this entity. Retired barcodes are removed rather than flagged. */
    @SerialName("IsDefaultScannedBarcode")
    @Serializable(with = NoYesSerializer::class)
    val isDefaultScanned: Boolean? = null,
)

/** InventoryWarehouses joined with the site. */
@Serializable
data class WarehouseDto(
    @SerialName("dataAreaId") val dataAreaId: String? = null,
    @SerialName("WarehouseId") val warehouseId: String,
    @SerialName("WarehouseName") val warehouseName: String? = null,
    @SerialName("OperationalSiteId") val siteId: String? = null,
    /**
     * Whether the warehouse runs Advanced Warehouse Management.
     *
     * Verified field name on a live instance. This app is non-WMS: receiving into a WMS
     * warehouse goes through directed work and license plates, not a product receipt against
     * PurchLine, so a warehouse with this set true must not be selectable.
     */
    @SerialName("AreAdvancedWarehouseManagementProcessesEnabled")
    @Serializable(with = NoYesSerializer::class)
    val isWmsEnabled: Boolean? = null,
    @SerialName("WarehouseType") val warehouseType: String? = null,
)

/** InventoryLocations — non-WMS location master. */
@Serializable
data class InventoryLocationDto(
    @SerialName("dataAreaId") val dataAreaId: String? = null,
    @SerialName("WarehouseId") val warehouseId: String = "",
    @SerialName("WarehouseLocationId") val locationId: String = "",
    /**
     * WarehouseLocations carries no site and no location name, and no IsBlocked flag.
     * Blocking is expressed as a *cause id* per direction; a non-empty cause means the
     * location is blocked for that movement.
     */
    @SerialName("InputWarehouseLocationBlockingCauseId") val inputBlockingCause: String? = null,
    @SerialName("OutputWarehouseLocationBlockingCauseId") val outputBlockingCause: String? = null,
)

/** InventOnHandV2 — on-hand inquiry. */
@Serializable
data class OnHandDto(
    @SerialName("dataAreaId") val dataAreaId: String? = null,
    @SerialName("ItemNumber") val itemNumber: String = "",
    @SerialName("InventorySiteId") val siteId: String? = null,
    @SerialName("InventoryWarehouseId") val warehouseId: String? = null,
    @SerialName("OnHandQuantity") val physicalInventory: Double = 0.0,
    @SerialName("AvailableOnHandQuantity") val availablePhysical: Double = 0.0,
    @SerialName("ReservedOnHandQuantity") val reservedPhysical: Double = 0.0,
    @SerialName("OrderedQuantity") val orderedInTotal: Double = 0.0,
    @SerialName("ReservedOrderedQuantity") val reservedOrdered: Double = 0.0,
    @SerialName("AvailableOrderedQuantity") val availableOrdered: Double = 0.0,
    @SerialName("TotalAvailableQuantity") val totalAvailable: Double = 0.0,
    @SerialName("AreWarehouseManagementProcessesUsed")
    @Serializable(with = NoYesSerializer::class)
    val wmsManaged: Boolean? = null,
)
