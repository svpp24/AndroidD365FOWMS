package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** HSMobileOpsCountRequestSvc - the counts of a warehouse (Hazard, Wall to wall, Blind), their items and results. */
@Serializable
data class CountRequestServiceRequest(@SerialName("_request") val request: CountRequestBody)

@Serializable
data class CountRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("UserName") val userName: String = "",
    @SerialName("DeviceId") val deviceId: String = "",
    @SerialName("WarehouseId") val warehouseId: String = "",
    @SerialName("MasterId") val masterId: String = "",
)

@Serializable
data class CountRequestServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
    @SerialName("MasterId") val masterId: String? = null,
    @SerialName("Reference") val reference: String? = null,
    /** Open, Counting, CreatingJournal, JournalCreated, Posted or Cancelled. */
    @SerialName("Status") val status: String? = null,
    @SerialName("JournalId") val journalId: String? = null,
    @SerialName("Posted") val posted: Boolean = false,
    /** A JSON array: [CountSummaryDto], [CountItemDto] or [CountResultDto], by operation. */
    @SerialName("PayloadJson") val payloadJson: String? = null,
)

/** One count of the warehouse (listCounts). Every value is a string. */
@Serializable
data class CountSummaryDto(
    @SerialName("MasterId") val masterId: String = "",
    @SerialName("Type") val type: String = "",
    @SerialName("Reference") val reference: String = "",
    @SerialName("Description") val description: String = "",
    @SerialName("DueUtc") val dueUtc: String = "",
    @SerialName("StartedUtc") val startedUtc: String = "",
    @SerialName("Status") val status: String = "",
    @SerialName("JournalId") val journalId: String = "",
    @SerialName("ItemCount") val itemCount: String = "0",
    @SerialName("CounterCount") val counterCount: String = "0",
    @SerialName("LineCount") val lineCount: String = "0",
    @SerialName("MyStatus") val myStatus: String = "",
)

/** What a hazard or blind count asks for (getCountItems): an item, a location, or both. */
@Serializable
data class CountItemDto(
    @SerialName("LineNum") val lineNum: String = "0",
    @SerialName("ItemId") val itemId: String = "",
    @SerialName("ItemName") val itemName: String = "",
    @SerialName("ConfigId") val configId: String = "",
    @SerialName("SizeId") val sizeId: String = "",
    @SerialName("ColorId") val colorId: String = "",
    @SerialName("StyleId") val styleId: String = "",
    @SerialName("LocationId") val locationId: String = "",
)

/** One counting journal line once the journal exists (getCountResults). */
@Serializable
data class CountResultDto(
    @SerialName("ItemId") val itemId: String = "",
    @SerialName("ItemName") val itemName: String = "",
    @SerialName("ConfigId") val configId: String = "",
    @SerialName("SizeId") val sizeId: String = "",
    @SerialName("ColorId") val colorId: String = "",
    @SerialName("StyleId") val styleId: String = "",
    @SerialName("BatchId") val batchId: String = "",
    @SerialName("SerialId") val serialId: String = "",
    @SerialName("LocationId") val locationId: String = "",
    @SerialName("Counted") val counted: String = "0",
    @SerialName("OnHand") val onHand: String = "0",
    @SerialName("Variance") val variance: String = "0",
)
