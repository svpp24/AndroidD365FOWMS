package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// -- HSMobileOpsTransferListSvc - open transfer orders for a warehouse, and serials that left it.

@Serializable
data class TransferListServiceRequest(@SerialName("_request") val request: TransferListRequestBody)

@Serializable
data class TransferListRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("WarehouseId") val warehouseId: String,
    @SerialName("Days") val days: Int = 0,
    @SerialName("Search") val search: String = "",
)

@Serializable
data class TransferListServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Orders") val orders: List<TransferSummaryDto> = emptyList(),
    @SerialName("Serials") val serials: List<SerialHistoryDto> = emptyList(),
)

@Serializable
data class TransferSummaryDto(
    @SerialName("TransferId") val transferId: String = "",
    @SerialName("FromWarehouseId") val fromWarehouseId: String = "",
    @SerialName("ToWarehouseId") val toWarehouseId: String = "",
    @SerialName("Status") val status: String = "",
    @SerialName("ShipDate") val shipDate: String = "",
    @SerialName("ReceiveDate") val receiveDate: String = "",
    @SerialName("LineCount") val lineCount: Int = 0,
    /** OUT: ships from the requested warehouse. IN: bound for it. */
    @SerialName("Direction") val direction: String = "",
)

@Serializable
data class SerialHistoryDto(
    @SerialName("ItemId") val itemId: String = "",
    @SerialName("ItemName") val itemName: String = "",
    @SerialName("SerialId") val serialId: String = "",
    @SerialName("BatchId") val batchId: String = "",
    @SerialName("Reference") val reference: String = "",
    @SerialName("ReferenceType") val referenceType: String = "",
    @SerialName("Status") val status: String = "",
    @SerialName("TransDate") val transDate: String = "",
    @SerialName("Quantity") val quantity: Double = 0.0,
)
