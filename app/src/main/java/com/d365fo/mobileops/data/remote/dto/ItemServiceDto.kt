package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** HSMobileOpsItemSvc - item, barcode and warehouse reads, so the app needs no OData. */
@Serializable
data class ItemServiceRequest(@SerialName("_request") val request: ItemRequestBody)

@Serializable
data class ItemRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("ItemId") val itemId: String,
)

@Serializable
data class ItemServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String = "",
    /** Blank = not a released product in this company. */
    @SerialName("ItemId") val itemId: String = "",
    @SerialName("ItemName") val itemName: String = "",
    @SerialName("InventUnit") val inventUnit: String = "",
    @SerialName("PurchUnit") val purchUnit: String = "",
    @SerialName("BatchActive") val batchActive: Boolean = false,
    @SerialName("SerialActive") val serialActive: Boolean = false,
    @SerialName("LocationActive") val locationActive: Boolean = false,
)

@Serializable
data class BarcodeServiceRequest(@SerialName("_request") val request: BarcodeRequestBody)

@Serializable
data class BarcodeRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("Barcode") val barcode: String,
)

@Serializable
data class BarcodeServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String = "",
    /** Blank = not a barcode of any item. */
    @SerialName("ItemId") val itemId: String = "",
    @SerialName("Quantity") val quantity: Double = 0.0,
    @SerialName("UnitId") val unitId: String = "",
    @SerialName("ConfigId") val configId: String = "",
    @SerialName("SizeId") val sizeId: String = "",
    @SerialName("ColorId") val colorId: String = "",
    @SerialName("StyleId") val styleId: String = "",
)

@Serializable
data class WarehousesServiceRequest(@SerialName("_request") val request: WarehousesRequestBody)

@Serializable
data class WarehousesRequestBody(@SerialName("DataAreaId") val dataAreaId: String)

@Serializable
data class WarehouseRowDto(
    @SerialName("WarehouseId") val warehouseId: String = "",
    @SerialName("WarehouseName") val warehouseName: String = "",
    @SerialName("SiteId") val siteId: String = "",
)

@Serializable
data class WarehousesServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String = "",
    @SerialName("Warehouses") val warehouses: List<WarehouseRowDto> = emptyList(),
)
