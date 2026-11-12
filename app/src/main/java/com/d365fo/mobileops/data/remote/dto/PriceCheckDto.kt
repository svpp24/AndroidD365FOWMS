package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** HSMobileOpsPriceCheckSvc/checkPrice - one line priced by Commerce's price simulator for the store. */
@Serializable
data class PriceCheckServiceRequest(@SerialName("_request") val request: PriceCheckRequestBody)

@Serializable
data class PriceCheckRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("WarehouseId") val warehouseId: String,
    @SerialName("ItemId") val itemId: String,
    @SerialName("ConfigId") val configId: String = "",
    @SerialName("SizeId") val sizeId: String = "",
    @SerialName("ColorId") val colorId: String = "",
    @SerialName("StyleId") val styleId: String = "",
    @SerialName("Quantity") val quantity: Double,
    /** Blank = the item's sales unit. */
    @SerialName("UnitId") val unitId: String = "",
)

@Serializable
data class PriceCheckServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
    @SerialName("ItemId") val itemId: String = "",
    @SerialName("ItemName") val itemName: String = "",
    @SerialName("Quantity") val quantity: Double = 0.0,
    @SerialName("UnitId") val unitId: String = "",
    /** Price of one unit before discounts. */
    @SerialName("UnitPrice") val unitPrice: Double = 0.0,
    /** Unit price x quantity. */
    @SerialName("GrossAmount") val grossAmount: Double = 0.0,
    @SerialName("DiscountAmount") val discountAmount: Double = 0.0,
    @SerialName("DiscountPct") val discountPct: Double = 0.0,
    /** What the line comes to: gross less discount. */
    @SerialName("NetAmount") val netAmount: Double = 0.0,
    /** The discounts and promotions applied, comma separated. */
    @SerialName("OfferName") val offerName: String? = null,
    @SerialName("Currency") val currency: String? = null,
    @SerialName("PriceIncludesTax") val priceIncludesTax: Boolean = false,
    @SerialName("StoreName") val storeName: String? = null,
)
