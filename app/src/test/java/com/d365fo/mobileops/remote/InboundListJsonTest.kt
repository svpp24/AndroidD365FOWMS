package com.d365fo.mobileops.remote

import com.d365fo.mobileops.data.remote.dto.CountConfirmationRowDto
import com.d365fo.mobileops.data.remote.dto.CountEmptyLocationRowDto
import com.d365fo.mobileops.data.remote.dto.CountExceptionRowDto
import com.d365fo.mobileops.data.remote.dto.CountExceptionsRequestBody
import com.d365fo.mobileops.data.remote.dto.CountExceptionsServiceRequest
import com.d365fo.mobileops.data.remote.dto.DirectCountRowDto
import com.d365fo.mobileops.data.remote.dto.DirectSubmitRequestBody
import com.d365fo.mobileops.data.remote.dto.DirectSubmitServiceRequest
import com.d365fo.mobileops.data.remote.dto.ItemUnitsKeyDto
import com.d365fo.mobileops.data.remote.dto.ItemUnitsRequestBody
import com.d365fo.mobileops.data.remote.dto.ItemUnitsServiceRequest
import com.d365fo.mobileops.data.remote.dto.ReviewRequestBody
import com.d365fo.mobileops.data.remote.dto.ReviewServiceRequest
import com.d365fo.mobileops.data.remote.dto.rowsJson
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * AOS cannot bind an inbound JSON array to a List of a custom X++ class, so these requests carry
 * their lists as JSON strings the X++ contracts parse themselves. The member names inside each
 * string are the ones the contracts read (HSMobileOpsCountDirectSubmitRequest.parmLines etc.).
 */
class InboundListJsonTest {

    private val json = Json { encodeDefaults = true }

    private fun request(encoded: String): JsonObject = Json.parseToJsonElement(encoded).jsonObject["_request"]!!.jsonObject

    /** A *Json member: a string (not an array) holding a JSON array. */
    private fun embedded(request: JsonObject, member: String): JsonArray {
        val value = request[member]!!.jsonPrimitive
        assertThat(value.isString).isTrue()
        return Json.parseToJsonElement(value.content).jsonArray
    }

    @Test
    fun `submitCounts sends its lines as LinesJson, not a Lines array`() {
        val body = DirectSubmitRequestBody(
            dataAreaId = "bsp", journalId = "J1", submissionId = "S1", userId = "002", deviceId = "D1",
            submittedAtUtc = "2026-10-06T00:00:00Z", passNumber = 1, originType = "MANUAL",
            linesJson = rowsJson(
                DirectCountRowDto.serializer(),
                listOf(DirectCountRowDto(lineNumber = 3.0, countedQuantity = 2.5, markedOnly = true, countedBy = "002")),
            ),
        )
        val request = request(json.encodeToString(DirectSubmitServiceRequest.serializer(), DirectSubmitServiceRequest(body)))

        assertThat(request).doesNotContainKey("Lines")
        val row = embedded(request, "LinesJson").single().jsonObject
        assertThat(row["LineNumber"]).isEqualTo(JsonPrimitive(3.0))
        assertThat(row["CountedQuantity"]).isEqualTo(JsonPrimitive(2.5))
        assertThat(row["MarkedOnly"]).isEqualTo(JsonPrimitive(true))
        assertThat(row["CountedBy"]).isEqualTo(JsonPrimitive("002"))
    }

    @Test
    fun `submitExceptions sends all three lists as Json strings`() {
        val body = CountExceptionsRequestBody(
            dataAreaId = "bsp", journalId = "J1", submissionId = "S1", userId = "002", deviceId = "D1",
            exceptionsJson = rowsJson(
                CountExceptionRowDto.serializer(),
                listOf(CountExceptionRowDto(clientId = "c1", itemNumber = "I1", quantity = 4.0, capturedAtUtc = "t")),
            ),
            emptyLocationsJson = rowsJson(CountEmptyLocationRowDto.serializer(), listOf(CountEmptyLocationRowDto("L1", verifiedAtUtc = "t"))),
            confirmationsJson = rowsJson(CountConfirmationRowDto.serializer(), emptyList()),
        )
        val request = request(json.encodeToString(CountExceptionsServiceRequest.serializer(), CountExceptionsServiceRequest(body)))

        assertThat(request.keys).containsNoneOf("Exceptions", "EmptyLocations", "Confirmations")
        assertThat(embedded(request, "ExceptionsJson").single().jsonObject["ItemNumber"]).isEqualTo(JsonPrimitive("I1"))
        assertThat(embedded(request, "EmptyLocationsJson").single().jsonObject["LocationId"]).isEqualTo(JsonPrimitive("L1"))
        assertThat(embedded(request, "ConfirmationsJson")).isEmpty()
    }

    @Test
    fun `getItemUnits sends its keys as ItemsJson`() {
        val body = ItemUnitsRequestBody(
            dataAreaId = "bsp",
            itemsJson = rowsJson(ItemUnitsKeyDto.serializer(), listOf(ItemUnitsKeyDto("I1", sizeId = "M"))),
        )
        val request = request(json.encodeToString(ItemUnitsServiceRequest.serializer(), ItemUnitsServiceRequest(body)))

        assertThat(request).doesNotContainKey("Items")
        val key = embedded(request, "ItemsJson").single().jsonObject
        assertThat(key["ItemId"]).isEqualTo(JsonPrimitive("I1"))
        assertThat(key["SizeId"]).isEqualTo(JsonPrimitive("M"))
        assertThat(key["ConfigId"]).isEqualTo(JsonPrimitive(""))
    }

    @Test
    fun `reviewLines sends its line numbers as LineNumbersJson`() {
        val body = ReviewRequestBody(
            dataAreaId = "bsp", journalId = "J1", userId = "002", action = "APPROVE",
            lineNumbersJson = rowsJson(Double.serializer(), listOf(1.0, 12.0)),
        )
        val request = request(json.encodeToString(ReviewServiceRequest.serializer(), ReviewServiceRequest(body)))

        assertThat(request).doesNotContainKey("LineNumbers")
        assertThat(embedded(request, "LineNumbersJson")).containsExactly(JsonPrimitive(1.0), JsonPrimitive(12.0)).inOrder()
    }
}
