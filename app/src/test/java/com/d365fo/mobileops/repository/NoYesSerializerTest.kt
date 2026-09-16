package com.d365fo.mobileops.repository

import com.d365fo.mobileops.data.remote.dto.ODataCollection
import com.d365fo.mobileops.data.remote.dto.PurchaseOrderLineDto
import com.d365fo.mobileops.data.remote.dto.TrackingDimensionGroupDto
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * D365FO booleans arrive as the NoYes strings "Yes" and "No", not as JSON booleans.
 *
 * This was found the hard way: a plain `Boolean` field threw on `"IsLineStopped":"No"`, the
 * whole PurchaseOrderLinesV2 page failed to decode, and the worklist came back empty with no
 * error an operator could see. The payloads below are copied from a live response.
 */
class NoYesSerializerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a NoYes string decodes as a boolean`() {
        val body = """
            {"value":[{
              "PurchaseOrderNumber":"PO-0000002130","LineNumber":3,"ItemNumber":"91824",
              "OrderedPurchaseQuantity":1,"IsLineStopped":"No","dataAreaId":"usmf"
            },{
              "PurchaseOrderNumber":"PO-0000002130","LineNumber":4,"ItemNumber":"91825",
              "OrderedPurchaseQuantity":2,"IsLineStopped":"Yes","dataAreaId":"usmf"
            }]}
        """.trimIndent()

        val lines = json.decodeFromString<ODataCollection<PurchaseOrderLineDto>>(body).value

        assertThat(lines).hasSize(2)
        assertThat(lines[0].isStopped).isFalse()
        assertThat(lines[1].isStopped).isTrue()
    }

    @Test
    fun `a real JSON boolean still decodes`() {
        // Not every entity uses NoYes, and which one an entity uses is not knowable up front.
        val body = """{"value":[{"PurchaseOrderNumber":"PO-1","LineNumber":1,"IsLineStopped":true}]}"""

        val lines = json.decodeFromString<ODataCollection<PurchaseOrderLineDto>>(body).value

        assertThat(lines.single().isStopped).isTrue()
    }

    @Test
    fun `an unrecognised value costs one field, not the whole page`() {
        // The page is what matters: an operator losing every line because one column held
        // something unexpected is far worse than one flag reading as unknown.
        val body = """
            {"value":[
              {"PurchaseOrderNumber":"PO-1","LineNumber":1,"IsLineStopped":"Maybe"},
              {"PurchaseOrderNumber":"PO-1","LineNumber":2,"IsLineStopped":"No"}
            ]}
        """.trimIndent()

        val lines = json.decodeFromString<ODataCollection<PurchaseOrderLineDto>>(body).value

        assertThat(lines).hasSize(2)
        assertThat(lines[0].isStopped).isNull()
        assertThat(lines[1].isStopped).isFalse()
    }

    @Test
    fun `an absent flag is null rather than false`() {
        val body = """{"value":[{"PurchaseOrderNumber":"PO-1","LineNumber":1}]}"""

        val lines = json.decodeFromString<ODataCollection<PurchaseOrderLineDto>>(body).value

        assertThat(lines.single().isStopped).isNull()
    }

    @Test
    fun `tracking dimension flags decode from NoYes too`() {
        val body = """
            {"value":[{
              "TrackingDimensionGroupName":"SerialBatch",
              "IsBatchNumberActive":"Yes","IsSerialNumberActive":"No"
            }]}
        """.trimIndent()

        val group = json.decodeFromString<ODataCollection<TrackingDimensionGroupDto>>(body)
            .value
            .single()

        assertThat(group.batchActive).isTrue()
        assertThat(group.serialActive).isFalse()
    }
}
