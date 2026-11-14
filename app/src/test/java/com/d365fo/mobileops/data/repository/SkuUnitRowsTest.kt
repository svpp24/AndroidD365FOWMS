package com.d365fo.mobileops.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The UNITS feed row (14 Nov): [itemId, inventUnit, purchUnit, salesUnit, [[unit, factor, decimals], ...]]. */
class SkuUnitRowsTest {
    private fun row(json: String) = Json.parseToJsonElement(json).jsonArray

    @Test fun unitsKeepFactorDigitsAndDropUnusable() {
        val r = SkuRows.units(row("""["IT-1","pcs","box","",[["pcs","1",0],["box","12.0000000000",0],["dz","0.0833333333",2],["bad","0",0],["","5",0]]]"""), 7L)!!
        assertEquals("IT-1", r.itemId)
        assertEquals("pcs", r.inventUnit)
        assertEquals("box", r.purchUnit)
        assertNull(r.salesUnit)
        assertEquals(listOf("pcs", "box", "dz"), r.units.map { it.unitId })
        assertEquals("12", java.math.BigDecimal(r.units[1].factor).stripTrailingZeros().toPlainString())
        assertEquals("0.0833333333", r.units[2].factor)
        assertEquals(2, r.units[2].decimals)
        assertEquals(7L, r.units[0].modifiedAt)
    }

    @Test fun rowWithoutItemIsSkipped() {
        assertNull(SkuRows.units(row("""["","pcs","","",[]]"""), 1L))
    }
}
