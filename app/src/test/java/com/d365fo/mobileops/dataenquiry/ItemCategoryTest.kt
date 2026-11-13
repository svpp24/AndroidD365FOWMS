package com.d365fo.mobileops.dataenquiry

import com.d365fo.mobileops.data.local.dao.CategoryCount
import com.d365fo.mobileops.data.local.entity.ItemEntity
import com.d365fo.mobileops.data.local.entity.TrackingRequirement
import com.d365fo.mobileops.data.repository.LocationStock
import com.d365fo.mobileops.data.repository.SkuRows
import com.d365fo.mobileops.presentation.modules.dataenquiry.CategoryNode
import com.d365fo.mobileops.presentation.modules.dataenquiry.NO_CATEGORY
import com.d365fo.mobileops.presentation.modules.dataenquiry.childCategories
import com.d365fo.mobileops.presentation.modules.dataenquiry.displayCategoryPath
import com.d365fo.mobileops.presentation.modules.dataenquiry.locationLevel
import com.d365fo.mobileops.presentation.modules.dataenquiry.locationRows
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItemCategoryTest {

    private val counts = listOf(
        CategoryCount("Health Care > Vitamins", 3),
        CategoryCount("Health Care > Pain Relief", 2),
        CategoryCount("Health Care", 1),
        CategoryCount("Beauty Care > Skin Care > Creams", 4),
        CategoryCount("", 5),
    )

    @Test
    fun `first level sums sub-categories and lists no-category last`() {
        assertEquals(
            listOf(
                CategoryNode("Beauty Care", "Beauty Care", 4),
                CategoryNode("Health Care", "Health Care", 6),
                CategoryNode(NO_CATEGORY, "No category", 5),
            ),
            childCategories(counts, ""),
        )
    }

    @Test
    fun `a category lists only its direct children`() {
        assertEquals(
            listOf(
                CategoryNode("Health Care > Pain Relief", "Pain Relief", 2),
                CategoryNode("Health Care > Vitamins", "Vitamins", 3),
            ),
            childCategories(counts, "Health Care"),
        )
        assertEquals(
            listOf(CategoryNode("Beauty Care > Skin Care", "Skin Care", 4)),
            childCategories(counts, "Beauty Care"),
        )
    }

    @Test
    fun `names drop the parent's name in front`() {
        val stored = listOf(
            CategoryCount("Beauty Care > Beauty Care.Skin Care > Beauty Care.Skin Care.Face Care", 2),
            CategoryCount("Beauty Care > Beauty Care.Lips", 1),
        )
        assertEquals(
            listOf(
                CategoryNode("Beauty Care > Beauty Care.Lips", "Lips", 1),
                CategoryNode("Beauty Care > Beauty Care.Skin Care", "Skin Care", 2),
            ),
            childCategories(stored, "Beauty Care"),
        )
        assertEquals("Beauty Care > Skin Care > Face Care", displayCategoryPath(stored[0].category))
        assertEquals("Health Care > Vitamins", displayCategoryPath("Health Care > Vitamins"))
    }

    @Test
    fun `location items form the same tree as items by category`() {
        fun item(id: String, name: String, category: String?) = ItemEntity(
            itemId = id, itemName = name, searchName = null, inventoryUnit = "EA", purchaseUnit = null, salesUnit = null,
            trackingRequirement = TrackingRequirement.NONE, locationMandatory = false, active = true, modifiedAt = 0L, category = category,
        )
        val items = listOf(
            item("B", "Beta", "Health Care > Health Care.Gsl"),
            item("A", "Alpha", "Health Care > Health Care.Gsl"),
            item("D", "Delta", "Health Care"),
            item("C", "Gamma", null),
        ).associateBy { it.itemId }
        val rows = locationRows(
            listOf("C", "B", "A", "D", "X").map { LocationStock(it, 1.0, 1.0, 0.0) },
            items,
        )
        val (top, topItems) = locationLevel(rows, "")
        assertEquals(listOf(CategoryNode("Health Care", "Health Care", 3), CategoryNode(NO_CATEGORY, "No category", 2)), top)
        assertEquals(emptyList<Any>(), topItems)
        val (sub, here) = locationLevel(rows, "Health Care")
        assertEquals(listOf(CategoryNode("Health Care > Health Care.Gsl", "Gsl", 2)), sub)
        assertEquals(listOf("D"), here.map { it.itemId })
        assertEquals(listOf("A", "B"), locationLevel(rows, "Health Care > Health Care.Gsl").second.map { it.itemId })
        assertEquals(listOf("C", "X"), locationLevel(rows, NO_CATEGORY).second.map { it.itemId })
    }

    @Test
    fun `item feed reads the category column, and its absence`() {
        fun row(text: String) = Json.parseToJsonElement(text).jsonArray
        val with = SkuRows.item(row("""["I1","Name","EA","EA","EA","0","0","1","1","Health Care > Vitamins"]"""), 1L)
        assertEquals("Health Care > Vitamins", with?.category)
        val without = SkuRows.item(row("""["I1","Name","EA","EA","EA","0","0","1","1"]"""), 1L)
        assertNull(without?.category)
        val blank = SkuRows.item(row("""["I1","Name","EA","EA","EA","0","0","1","1",""]"""), 1L)
        assertNull(blank?.category)
    }
}
