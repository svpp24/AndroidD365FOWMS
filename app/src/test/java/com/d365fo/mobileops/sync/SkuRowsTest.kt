package com.d365fo.mobileops.sync

import com.d365fo.mobileops.data.local.entity.TrackingRequirement
import com.d365fo.mobileops.data.repository.SkuRows
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Test

/** The positional rows of getSkuPage - the column order is the contract with D365FO. */
class SkuRowsTest {

    private fun rows(json: String): List<JsonArray> = Json.parseToJsonElement(json).jsonArray.map { it.jsonArray }

    @Test
    fun `item row - units, tracking, location and lifecycle`() {
        val (batchSerial, plain, inactive) = rows(
            """[["HST001","HS test 1","Pcs","Box","Pcs","1","1","1","1"],
                ["Dm001","DM001","Pieces","","","0","0","0","1"],
                ["OLD1","Old","Pcs","Pcs","Pcs","1","0","0","0"]]""",
        )
        val a = SkuRows.item(batchSerial, 7L)!!
        assertThat(a.itemId).isEqualTo("HST001")
        assertThat(a.inventoryUnit).isEqualTo("Pcs")
        assertThat(a.purchaseUnit).isEqualTo("Box")
        assertThat(a.trackingRequirement).isEqualTo(TrackingRequirement.BATCH_AND_SERIAL)
        assertThat(a.locationMandatory).isTrue()
        assertThat(a.modifiedAt).isEqualTo(7L)

        val b = SkuRows.item(plain, 7L)!!
        assertThat(b.purchaseUnit).isNull()
        assertThat(b.trackingRequirement).isEqualTo(TrackingRequirement.NONE)
        assertThat(b.locationMandatory).isFalse()
        assertThat(b.active).isTrue()

        val c = SkuRows.item(inactive, 7L)!!
        assertThat(c.trackingRequirement).isEqualTo(TrackingRequirement.BATCH)
        assertThat(c.active).isFalse()
    }

    @Test
    fun `item row without an item number is skipped`() {
        assertThat(SkuRows.item(rows("""[["  ","x","Pcs","","","0","0","0","1"]]""").single(), 0L)).isNull()
    }

    @Test
    fun `barcode row - upper-cased key, pack quantity, variant, blocked`() {
        val (pack, variant, blocked) = rows(
            """[["9502930970077","BSR-47274-0","0","Pack","","","","","0"],
                ["834580a0000859m1","BSR-83458-0","12","Box","C1","L","Black","Slim","0"],
                ["X1","I1","1","","","","","","1"]]""",
        )
        val a = SkuRows.barcode(pack, 0L)!!
        assertThat(a.quantityPerScan).isEqualTo("1")
        assertThat(a.unitId).isEqualTo("Pack")
        assertThat(a.configId).isNull()
        assertThat(a.active).isTrue()

        val b = SkuRows.barcode(variant, 0L)!!
        assertThat(b.barcode).isEqualTo("834580A0000859M1")
        assertThat(b.quantityPerScan).isEqualTo("12")
        assertThat(listOf(b.configId, b.sizeId, b.colorId, b.styleId)).containsExactly("C1", "L", "Black", "Slim").inOrder()

        assertThat(SkuRows.barcode(blocked, 0L)!!.active).isFalse()
    }

    @Test
    fun `variant row keeps blank dimensions as empty strings`() {
        val v = SkuRows.variant(rows("""[["Dm001","C1","","",""]]""").single(), 5L)!!
        assertThat(listOf(v.itemId, v.configId, v.sizeId, v.colorId, v.styleId)).containsExactly("Dm001", "C1", "", "", "").inOrder()
        assertThat(v.syncedAt).isEqualTo(5L)
    }

    @Test
    fun `delta watermark goes back by the overlap, in D365FO's format`() {
        assertThat(SkuRows.withOverlap("2026-10-09T16:59:56", 10)).isEqualTo("2026-10-09T16:49:56")
        assertThat(SkuRows.withOverlap("2026-10-09T00:05:00.123Z", 10)).isEqualTo("2026-10-08T23:55:00")
        assertThat(SkuRows.withOverlap("not a time", 10)).isEmpty()
    }
}
