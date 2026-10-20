package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** HSMobileOpsCountStageSvc - counts staged for the supervisor (Stock count control center). */
@Serializable
data class CountStageServiceRequest(@SerialName("_request") val request: CountStageRequestBody)

@Serializable
data class CountStageRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("UserName") val userName: String = "",
    @SerialName("DeviceId") val deviceId: String = "",
    @SerialName("WarehouseId") val warehouseId: String = "",
    /** A count against a counting journal; blank for a free store count. */
    @SerialName("SourceJournalId") val sourceJournalId: String = "",
    @SerialName("StageId") val stageId: String = "",
    @SerialName("SubmissionId") val submissionId: String = "",
    /** [rowsJson] of [CountStageLineDto]s. */
    @SerialName("LinesJson") val linesJson: String = "",
    /** The counter ends their count with this call. */
    @SerialName("EndCount") val endCount: Boolean = false,
)

/** One counted line. [quantity] is in the item's inventory unit; [clientLineId] keys resends. */
@Serializable
data class CountStageLineDto(
    @SerialName("ClientLineId") val clientLineId: String,
    @SerialName("ItemId") val itemId: String,
    @SerialName("ConfigId") val configId: String? = null,
    @SerialName("SizeId") val sizeId: String? = null,
    @SerialName("ColorId") val colorId: String? = null,
    @SerialName("StyleId") val styleId: String? = null,
    @SerialName("BatchId") val batchId: String? = null,
    @SerialName("SerialId") val serialId: String? = null,
    @SerialName("LocationId") val locationId: String? = null,
    @SerialName("Quantity") val quantity: Double,
    @SerialName("UnitId") val unitId: String? = null,
    @SerialName("CountedAtUtc") val countedAtUtc: String? = null,
    /** The counting journal line counted; 0 for stock found that is not on the journal. */
    @SerialName("SourceLineNum") val sourceLineNum: Double = 0.0,
)

@Serializable
data class CountStageServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
    @SerialName("MasterId") val masterId: String? = null,
    @SerialName("StageId") val stageId: String? = null,
    @SerialName("Accepted") val accepted: Int = 0,
    /** Items in the stock count counted more than once and not yet decided by the supervisor. */
    @SerialName("Duplicates") val duplicates: Int = 0,
    @SerialName("StageJournalCounts") val stageJournalCounts: Boolean = false,
)
