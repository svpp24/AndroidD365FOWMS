package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** HSMobileOpsSkuSvc/getSkuPage - one page of the ITEMS, BARCODES or VARIANTS feed. */
@Serializable
data class SkuPageServiceRequest(@SerialName("_request") val request: SkuPageRequestBody)

@Serializable
data class SkuPageRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("Feed") val feed: String,
    /** Resume after this key: the last ItemId (ITEMS) or RecId (BARCODES, VARIANTS). */
    @SerialName("AfterKey") val afterKey: String = "",
    /** Only rows changed since (D365FO's own clock, from ServerUtc); blank for all. */
    @SerialName("ChangedSinceUtc") val changedSinceUtc: String = "",
    @SerialName("PageSize") val pageSize: Int = 2000,
)

@Serializable
data class SkuPageServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String = "",
    @SerialName("Message") val message: String = "",
    /** A JSON array of positional arrays - columns per feed, see HSMobileOpsSkuService::getSkuPage. */
    @SerialName("RowsJson") val rowsJson: String = "[]",
    @SerialName("RowCount") val rowCount: Int = 0,
    @SerialName("NextKey") val nextKey: String = "",
    @SerialName("HasMore") val hasMore: Boolean = false,
    @SerialName("ServerUtc") val serverUtc: String = "",
)

/**
 * getSkuSnapshot's RowsJson: the company's current item snapshot (Mobile item snapshot in D365FO) -
 * each area's whole feed as one gzip file, one getSkuPage page of rows per line.
 */
@Serializable
data class SkuSnapshotManifest(
    val builtAtUtc: String = "",
    val files: List<SkuSnapshotFile> = emptyList(),
)

@Serializable
data class SkuSnapshotFile(
    /** ITEMS, BARCODES or VARIANTS. */
    val area: String = "",
    /** A short-lived download link (its own access token, no D365FO sign-in). */
    val url: String = "",
    val bytes: Long = 0L,
    val rows: Long = 0L,
    val sha256: String = "",
)
