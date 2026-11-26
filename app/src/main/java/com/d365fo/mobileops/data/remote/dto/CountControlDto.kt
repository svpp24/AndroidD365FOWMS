package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `HSMobileOpsCountControlSvc` (Count control for the store supervisor, 24 Nov 2026). One request
 * shape for every operation; the answer's PayloadJson carries what each operation returns, with
 * numbers as text (X++ writes every value as a string).
 */
@Serializable
data class CountControlServiceRequest(@SerialName("_request") val request: CountControlRequestBody)

@Serializable
data class CountControlRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("UserName") val userName: String,
    @SerialName("WarehouseId") val warehouseId: String,
    @SerialName("LineId") val lineId: String = "",
    @SerialName("Decision") val decision: String = "",
    @SerialName("Decided") val decided: Boolean = false,
    @SerialName("Hold") val hold: Boolean = false,
)

@Serializable
data class CountControlServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
    @SerialName("PayloadJson") val payloadJson: String? = null,
)

/** getCountControl's payload. */
@Serializable
data class CountControlPayload(
    @SerialName("StoreId") val storeId: String = "",
    @SerialName("StoreName") val storeName: String = "",
    @SerialName("Tiles") val tiles: CountControlTilesDto = CountControlTilesDto(),
    @SerialName("StoreJournal") val storeJournal: StoreJournalDto? = null,
)

@Serializable
data class CountControlTilesDto(
    @SerialName("BinsCountedTwice") val binsCountedTwice: String = "0",
    @SerialName("CountersCounting") val countersCounting: String = "0",
    @SerialName("BlindCountsToday") val blindCountsToday: String = "0",
    @SerialName("LinesWithVariance") val linesWithVariance: String = "0",
)

@Serializable
data class StoreJournalDto(
    @SerialName("JournalId") val journalId: String = "",
    @SerialName("Status") val status: String = "",
    @SerialName("StatusLabel") val statusLabel: String = "",
    @SerialName("BlindCounts") val blindCounts: String = "0",
    @SerialName("Lines") val lines: String = "0",
    @SerialName("CountedTwice") val countedTwice: String = "0",
    @SerialName("OnHold") val onHold: String = "false",
    @SerialName("HeldBy") val heldBy: String = "",
    @SerialName("PostMessage") val postMessage: String = "",
)

/** getCountedTwice's payload. */
@Serializable
data class CountedTwicePayload(
    @SerialName("ToDecide") val toDecide: String = "0",
    @SerialName("Decided") val decided: String = "0",
    @SerialName("Bins") val bins: List<CountedTwiceBinDto> = emptyList(),
)

@Serializable
data class CountedTwiceBinDto(
    @SerialName("LineId") val lineId: String = "",
    @SerialName("LocationId") val locationId: String = "",
    @SerialName("BinId") val binId: String = "",
    @SerialName("ItemId") val itemId: String = "",
    @SerialName("ItemName") val itemName: String = "",
    @SerialName("OnHand") val onHand: String = "0",
    @SerialName("Decision") val decision: String = "",
    @SerialName("Counts") val counts: List<BinCountDto> = emptyList(),
)

@Serializable
data class BinCountDto(
    @SerialName("LineId") val lineId: String = "",
    @SerialName("Counter") val counter: String = "",
    @SerialName("CounterName") val counterName: String = "",
    @SerialName("MasterId") val masterId: String = "",
    @SerialName("CountedAt") val countedAt: String = "",
    @SerialName("Qty") val qty: String = "0",
    @SerialName("Included") val included: String = "true",
)

/** getCounters' payload rows. */
@Serializable
data class CounterDto(
    @SerialName("UserName") val userName: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("MasterId") val masterId: String = "",
    @SerialName("DeviceId") val deviceId: String = "",
    @SerialName("StartedAt") val startedAt: String = "",
    @SerialName("Lines") val lines: String = "0",
    @SerialName("LastScanAt") val lastScanAt: String = "",
    @SerialName("Ended") val ended: String = "false",
)

/** getBlindCounts' payload rows. */
@Serializable
data class BlindCountDto(
    @SerialName("MasterId") val masterId: String = "",
    @SerialName("Description") val description: String = "",
    @SerialName("Status") val status: String = "",
    @SerialName("Counters") val counters: String = "0",
    @SerialName("Lines") val lines: String = "0",
    @SerialName("StartedAt") val startedAt: String = "",
    @SerialName("InJournal") val inJournal: String = "false",
    @SerialName("Message") val message: String = "",
)

/** getVarianceLines' payload rows. */
@Serializable
data class VarianceLineRowDto(
    @SerialName("ItemId") val itemId: String = "",
    @SerialName("ItemName") val itemName: String = "",
    @SerialName("LocationId") val locationId: String = "",
    @SerialName("BatchId") val batchId: String = "",
    @SerialName("SerialId") val serialId: String = "",
    @SerialName("Counted") val counted: String = "0",
    @SerialName("OnHand") val onHand: String = "0",
    @SerialName("Variance") val variance: String = "0",
)
