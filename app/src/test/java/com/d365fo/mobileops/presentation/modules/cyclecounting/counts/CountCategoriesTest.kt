package com.d365fo.mobileops.presentation.modules.cyclecounting.counts

import com.d365fo.mobileops.data.counting.RequestedItem
import org.junit.Assert.assertEquals
import org.junit.Test

class CountCategoriesTest {

    private fun item(id: String, name: String, category: String, counted: Boolean = false) =
        RequestedItem(itemId = id, itemName = name, category = category, countedByAnyone = counted)

    @Test
    fun `groups by first-level category, A to Z, uncategorised last`() {
        val groups = groupByCategory(
            listOf(
                item("3", "Zinc", "Personal Care"),
                item("1", "Aspirin", "Health Care", counted = true),
                item("9", "Loose", ""),
                item("2", "Bandage", "Health Care"),
            ),
        )
        assertEquals(listOf("Health Care", "Personal Care", NO_CATEGORY), groups.map { it.name })
        assertEquals(listOf("Aspirin", "Bandage"), groups[0].items.map { it.itemName })
        assertEquals(1, groups[0].counted)
        assertEquals(0, groups[2].counted)
    }
}
