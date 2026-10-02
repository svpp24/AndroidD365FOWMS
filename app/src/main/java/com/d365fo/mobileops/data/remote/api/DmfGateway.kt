package com.d365fo.mobileops.data.remote.api

import com.d365fo.mobileops.data.remote.dto.DmfRequestBody
import com.d365fo.mobileops.data.remote.dto.DmfServiceRequest
import com.d365fo.mobileops.data.remote.dto.DmfServiceResponse
import com.d365fo.mobileops.data.remote.dto.ExecutionIdRequest
import com.d365fo.mobileops.data.remote.dto.ExportToPackageRequest
import com.d365fo.mobileops.data.remote.dto.GetAzureWriteUrlRequest
import com.d365fo.mobileops.data.remote.dto.ODataActionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The DMF actions the app needs, routed through the X++ HSMobileOpsDmfSvc instead of the
 * `/data/` OData endpoint. Same signatures and [ODataActionResult] shape as the old
 * [D365ODataApi] methods, so callers' `stringValue()`/`stringListValue()` parsing is unchanged.
 */
@Singleton
class DmfGateway @Inject constructor(
    private val service: MobileOpsServiceApi,
) {
    suspend fun exportToPackage(body: ExportToPackageRequest): Response<ODataActionResult> =
        service.dmfExportToPackage(
            DmfServiceRequest(
                DmfRequestBody(
                    definitionGroupId = body.definitionGroupId,
                    packageName = body.packageName,
                    executionId = body.executionId,
                    reExecute = body.reExecute,
                    legalEntityId = body.legalEntityId,
                ),
            ),
        ).asAction()

    suspend fun getExecutionSummaryStatus(body: ExecutionIdRequest): Response<ODataActionResult> =
        service.dmfGetExecutionSummaryStatus(byExecution(body)).asAction()

    suspend fun getExportedPackageUrl(body: ExecutionIdRequest): Response<ODataActionResult> =
        service.dmfGetExportedPackageUrl(byExecution(body)).asAction()

    suspend fun getExecutionErrors(body: ExecutionIdRequest): Response<ODataActionResult> =
        service.dmfGetExecutionErrors(byExecution(body)).asAction { errorList(it) }

    suspend fun getAzureWriteUrl(body: GetAzureWriteUrlRequest): Response<ODataActionResult> =
        service.dmfGetAzureWriteUrl(
            DmfServiceRequest(DmfRequestBody(uniqueFileName = body.uniqueFileName)),
        ).asAction()

    private fun byExecution(body: ExecutionIdRequest) =
        DmfServiceRequest(DmfRequestBody(executionId = body.executionId))

    /** GetExecutionErrors returns JSON text; a list stays a list, anything else becomes one entry. */
    private fun errorList(text: String): JsonElement {
        if (text.isBlank()) return JsonArray(emptyList())
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        return parsed as? JsonArray ?: JsonArray(listOf(JsonPrimitive(text)))
    }

    private fun Response<DmfServiceResponse>.asAction(
        wrap: (String) -> JsonElement = { JsonPrimitive(it) },
    ): Response<ODataActionResult> {
        if (!isSuccessful) {
            return Response.error(errorBody() ?: "".toResponseBody(null), raw())
        }
        return Response.success(ODataActionResult(wrap(body()?.value.orEmpty())), raw())
    }
}
