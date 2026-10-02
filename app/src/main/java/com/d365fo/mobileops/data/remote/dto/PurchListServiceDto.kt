package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// -- HSMobileOpsPurchListSvc - purchase order header lists (PO of today), read in X++ instead of
// the PurchaseOrderHeadersV2 OData entity.

@Serializable
data class OrdersForDateServiceRequest(@SerialName("_request") val request: OrdersForDateRequestBody)

@Serializable
data class OrdersForDateRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    /** Blank = every warehouse. */
    @SerialName("WarehouseId") val warehouseId: String = "",
    /** ISO date, yyyy-MM-dd, in the device's own day. */
    @SerialName("DeliveryDate") val deliveryDate: String,
)

@Serializable
data class OrderSummaryRowDto(
    @SerialName("PurchId") val purchId: String = "",
    @SerialName("VendorAccount") val vendorAccount: String? = null,
    /** PurchStatus symbol: Backorder, Received, Invoiced, Canceled or None. */
    @SerialName("Status") val status: String? = null,
    @SerialName("SiteId") val siteId: String? = null,
    @SerialName("WarehouseId") val warehouseId: String? = null,
    /** ISO date, yyyy-MM-dd. */
    @SerialName("DeliveryDate") val deliveryDate: String? = null,
)

@Serializable
data class OrdersForDateServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Orders") val orders: List<OrderSummaryRowDto> = emptyList(),
)
