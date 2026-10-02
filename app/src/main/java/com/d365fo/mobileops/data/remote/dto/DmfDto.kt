package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Request/response shapes for D365FO's own documented Data management package REST API —
 * unbound OData actions on `DataManagementDefinitionGroups`, called through the same `/data/`
 * root and AAD bearer token every other read in [com.d365fo.mobileops.data.remote.api.D365ODataApi]
 * already uses. See https://learn.microsoft.com/en-us/dynamics365/fin-ops-core/dev-itpro/data-entities/data-management-api
 * and `aos/README.md`'s counting section for how this replaces the custom X++ trigger/poll pair
 * this app used to guess at.
 *
 * Every action's documented response is `{"@odata.context":"...","value": <T>}`, where `<T>` is
 * usually a plain string - except Microsoft's own doc example for `ExportToPackage` shows an
 * oddly *nested* `{"value":{"value":"<executionId>"}}` shape that no other action's example
 * shares, and may simply be a documentation typo. [value] is decoded as [JsonElement] rather than
 * a fixed `String` so [stringValue] can defensively unwrap either shape instead of crashing on
 * whichever one turns out to be real.
 */
@Serializable
data class ODataActionResult(
    val value: JsonElement,
)

/** Unwraps either `{"value":"x"}` or the possibly-nested `{"value":{"value":"x"}}`. */
fun ODataActionResult.stringValue(): String? = when (val v = value) {
    is JsonPrimitive -> v.content
    is JsonObject -> (v["value"] as? JsonPrimitive)?.content
    else -> null
}

/** [GetExecutionErrors]'s response - a JSON list of error message strings. */
fun ODataActionResult.stringListValue(): List<String> = when (val v = value) {
    is kotlinx.serialization.json.JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }
    is JsonObject -> ((v["value"] as? kotlinx.serialization.json.JsonArray))
        ?.mapNotNull { (it as? JsonPrimitive)?.content }
        ?: emptyList()
    else -> emptyList()
}

@Serializable
data class GetAzureWriteUrlRequest(
    @SerialName("uniqueFileName") val uniqueFileName: String,
)

/** [ODataActionResult.stringValue] on this response is itself a JSON string, not the URL
 *  directly - decode it again to reach `BlobId`/`BlobUrl`. See [AzureWritableBlob]. */
@Serializable
data class AzureWritableBlob(
    @SerialName("BlobId") val blobId: String = "",
    @SerialName("BlobUrl") val blobUrl: String = "",
)

@Serializable
data class ExportToPackageRequest(
    @SerialName("definitionGroupId") val definitionGroupId: String,
    @SerialName("packageName") val packageName: String,
    @SerialName("executionId") val executionId: String = "",
    @SerialName("reExecute") val reExecute: Boolean = true,
    @SerialName("legalEntityId") val legalEntityId: String,
)

@Serializable
data class ImportFromPackageRequest(
    @SerialName("packageUrl") val packageUrl: String,
    @SerialName("definitionGroupId") val definitionGroupId: String,
    @SerialName("executionId") val executionId: String = "",
    @SerialName("execute") val execute: Boolean = true,
    @SerialName("overwrite") val overwrite: Boolean = true,
    @SerialName("legalEntityId") val legalEntityId: String,
)

@Serializable
data class ExecutionIdRequest(
    @SerialName("executionId") val executionId: String,
)

/**
 * [ODataActionResult.stringValue] on `GetExecutionSummaryStatus`'s response - one of
 * Unknown/NotRun/Executing/Succeeded/PartiallySucceeded/Failed/Canceled. Compared by string,
 * not decoded into an enum, so a value this app doesn't recognise yet still round-trips instead
 * of throwing - callers treat anything other than the recognised terminal/pending states as
 * still-running.
 */
object DmfExecutionStatus {
    const val SUCCEEDED = "Succeeded"
    const val PARTIALLY_SUCCEEDED = "PartiallySucceeded"
    const val FAILED = "Failed"
    const val CANCELED = "Canceled"
    const val NOT_RUN = "NotRun"
    const val EXECUTING = "Executing"
    const val UNKNOWN = "Unknown"
}

// -- HSMobileOpsDmfSvc (X++ wrappers over the same DMF actions) ---------------------------------

@Serializable
data class DmfServiceRequest(
    @SerialName("_request") val request: DmfRequestBody,
)

/** Each operation reads only the members it needs; the rest stay blank. */
@Serializable
data class DmfRequestBody(
    @SerialName("DefinitionGroupId") val definitionGroupId: String = "",
    @SerialName("PackageName") val packageName: String = "",
    @SerialName("ExecutionId") val executionId: String = "",
    @SerialName("ReExecute") val reExecute: Boolean = false,
    @SerialName("LegalEntityId") val legalEntityId: String = "",
    @SerialName("UniqueFileName") val uniqueFileName: String = "",
)

@Serializable
data class DmfServiceResponse(
    @SerialName("Value") val value: String? = null,
)
