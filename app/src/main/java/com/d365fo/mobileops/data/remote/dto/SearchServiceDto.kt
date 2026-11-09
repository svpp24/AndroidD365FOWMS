package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// -- HSMobileOpsSearchSvc - Home's search: a PO, transfer or journal number, or an item and the
// open work holding it at the warehouse.

@Serializable
data class SearchServiceRequest(@SerialName("_request") val request: SearchRequestBody)

@Serializable
data class SearchRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("WarehouseId") val warehouseId: String,
    @SerialName("Query") val query: String,
)

@Serializable
data class SearchServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("ItemId") val itemId: String? = null,
    @SerialName("ItemName") val itemName: String? = null,
    @SerialName("UnitId") val unitId: String? = null,
    @SerialName("Hits") val hits: List<SearchHitDto> = emptyList(),
)

@Serializable
data class SearchHitDto(
    /** PO, TO or JOURNAL. */
    @SerialName("Kind") val kind: String = "",
    /** ORDER: the query was this record's number. ITEM: the record holds the searched item. */
    @SerialName("Match") val match: String = "",
    @SerialName("Reference") val reference: String = "",
    @SerialName("Title") val title: String = "",
    @SerialName("Detail") val detail: String = "",
    @SerialName("Status") val status: String = "",
    @SerialName("Date") val date: String = "",
    @SerialName("Quantity") val quantity: Double = 0.0,
    @SerialName("UnitId") val unitId: String = "",
    /** IN or OUT for orders. */
    @SerialName("Direction") val direction: String = "",
)
