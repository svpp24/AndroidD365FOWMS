package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Standard OData v4 collection envelope returned by the D365FO `/data` endpoint. */
@Serializable
data class ODataCollection<T>(
    @SerialName("@odata.context") val context: String? = null,
    @SerialName("@odata.count") val count: Long? = null,
    /** Server-driven paging link. Present when the result exceeds the service page size. */
    @SerialName("@odata.nextLink") val nextLink: String? = null,
    val value: List<T> = emptyList(),
)

/** OData error payload. D365FO nests the useful X++ text inside `innererror`. */
@Serializable
data class ODataErrorEnvelope(
    val error: ODataError? = null,
)

@Serializable
data class ODataError(
    val code: String? = null,
    val message: String? = null,
    val innererror: ODataInnerError? = null,
)

@Serializable
data class ODataInnerError(
    val message: String? = null,
    val type: String? = null,
    @SerialName("stacktrace") val stackTrace: String? = null,
    val internalexception: ODataInnerError? = null,
) {
    /** Deepest non-blank message — that is the one an X++ validation actually wrote. */
    fun deepestMessage(): String? =
        internalexception?.deepestMessage() ?: message?.takeIf { it.isNotBlank() }
}
