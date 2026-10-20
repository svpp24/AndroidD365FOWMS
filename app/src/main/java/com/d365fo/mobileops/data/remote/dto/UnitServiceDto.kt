package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** HSMobileOpsUnitSvc/getItemUnits - every item variant of a journal in one call. */
@Serializable
data class ItemUnitsServiceRequest(@SerialName("_request") val request: ItemUnitsRequestBody)

@Serializable
data class ItemUnitsRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    /** [rowsJson] of [ItemUnitsKeyDto]s. */
    @SerialName("ItemsJson") val itemsJson: String,
)

@Serializable
data class ItemUnitsKeyDto(
    @SerialName("ItemId") val itemId: String,
    @SerialName("ConfigId") val configId: String = "",
    @SerialName("SizeId") val sizeId: String = "",
    @SerialName("ColorId") val colorId: String = "",
    @SerialName("StyleId") val styleId: String = "",
)

@Serializable
data class ItemUnitsResultDto(
    @SerialName("ItemId") val itemId: String = "",
    @SerialName("ConfigId") val configId: String = "",
    @SerialName("SizeId") val sizeId: String = "",
    @SerialName("ColorId") val colorId: String = "",
    @SerialName("StyleId") val styleId: String = "",
    @SerialName("InventoryUnit") val inventoryUnit: String = "",
    @SerialName("Units") val units: List<ItemUnitDto> = emptyList(),
)

@Serializable
data class ItemUnitsServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String = "",
    @SerialName("Items") val items: List<ItemUnitsResultDto> = emptyList(),
)
