package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The contract for `HSMobileOpsSkuSvc/exportSkuMaster` - sets up the two DMF export projects
 * (item master, barcodes) the bulk SKU sync reads from. See
 * `aos/HSMobileOps/AxClass/HSMobileOpsSkuService.xml`. Same division of labour as
 * [ScopeExportServiceRequest]/[ScopeExportServiceResponse]: this call only creates the
 * project(s) and hands back their names - [SkuSyncRepository][com.d365fo.mobileops.data.repository.SkuSyncRepository]
 * drives the actual export itself via the public `ExportToPackage`/`GetExecutionSummaryStatus`/
 * `GetExportedPackageUrl` OData actions (see `DmfDto.kt`), the same flow
 * `CountingJournalRepository` already uses for counting journals.
 */
@Serializable
data class SkuExportServiceRequest(
    @SerialName("_request") val request: SkuExportRequestBody,
)

@Serializable
data class SkuExportRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
)

@Serializable
data class SkuExportServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String? = null,
    @SerialName("Message") val message: String? = null,
    @SerialName("ItemDefinitionGroupName") val itemDefinitionGroupName: String? = null,
    @SerialName("BarcodeDefinitionGroupName") val barcodeDefinitionGroupName: String? = null,
)
