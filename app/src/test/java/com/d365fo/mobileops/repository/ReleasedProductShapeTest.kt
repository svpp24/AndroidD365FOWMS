package com.d365fo.mobileops.repository

import com.d365fo.mobileops.data.remote.api.ODataSelect
import com.d365fo.mobileops.data.remote.dto.ODataCollection
import com.d365fo.mobileops.data.remote.dto.ReleasedProductDto
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Pins the real shape of ReleasedProductsV2, because getting it wrong fails expensively.
 *
 * Asking for a property this entity does not have is not a fast 404. It is a 400 that took
 * **forty seconds** to come back on this tenant, during which the operator is looking at a
 * spinner — and the message naming the bad property is buried in a stack trace nobody reads on
 * a handheld. `ProductName` is the specific trap: it is the obvious name, it is what the app
 * asked for originally, and it does not exist.
 *
 * The payload is copied from a live response.
 */
class ReleasedProductShapeTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val liveRow = """
        {"value":[{
          "ItemNumber":"A00001",
          "ProductNumber":"A00001",
          "SearchName":"WMSSimpleItem 1",
          "ProductSearchName":"WMSSimpleItem 1",
          "InventoryUnitSymbol":"ea",
          "PurchaseUnitSymbol":"ea",
          "SalesUnitSymbol":"ea",
          "IsBatchNumberActive":"No",
          "IsSerialNumberActive":"No",
          "TrackingDimensionGroupName":"None",
          "StorageDimensionGroupName":"SiteWH",
          "ProductLifecycleStateId":"Operational",
          "dataAreaId":"usmf"
        }]}
    """.trimIndent()

    @Test
    fun `a live row decodes, and the name comes from SearchName`() {
        val product = json.decodeFromString<ODataCollection<ReleasedProductDto>>(liveRow)
            .value
            .single()

        assertThat(product.itemNumber).isEqualTo("A00001")
        assertThat(product.searchName).isEqualTo("WMSSimpleItem 1")
        assertThat(product.inventoryUnit).isEqualTo("ea")
    }

    @Test
    fun `batch and serial flags come off the product, not a dimension group join`() {
        val product = json.decodeFromString<ODataCollection<ReleasedProductDto>>(liveRow)
            .value
            .single()

        // "No" is a NoYes string, not a JSON boolean. Both flags being readable here is what
        // lets the item lookup be a single query instead of two.
        assertThat(product.batchActive).isFalse()
        assertThat(product.serialActive).isFalse()
    }

    @Test
    fun `the projection never asks for ProductName`() {
        // The regression this file exists for.
        assertThat(ODataSelect.RELEASED_PRODUCT).doesNotContain("ProductName")
    }

    @Test
    fun `the projection asks only for properties this entity publishes`() {
        val published = setOf(
            "ItemNumber", "ProductNumber", "SearchName", "ProductSearchName",
            "InventoryUnitSymbol", "PurchaseUnitSymbol", "SalesUnitSymbol",
            "IsBatchNumberActive", "IsSerialNumberActive", "TrackingDimensionGroupName",
            "StorageDimensionGroupName", "ProductLifecycleStateId", "ItemModelGroupId",
            "IsCatchWeightProduct", "dataAreaId",
        )

        val requested = ODataSelect.RELEASED_PRODUCT.split(",").map { it.trim() }

        assertThat(published).containsAtLeastElementsIn(requested)
    }

    @Test
    fun `a SiteWH storage group means no location prompt`() {
        // The location flag is inferred from the group name, since no StorageDimensionGroups
        // entity exists here. "SiteWH" tracks site and warehouse only - prompting for a
        // location against it would send a dimension D365FO does not accept for this item.
        val product = json.decodeFromString<ODataCollection<ReleasedProductDto>>(liveRow)
            .value
            .single()

        val locationRequired = product.storageDimensionGroup
            ?.contains("Loc", ignoreCase = true) ?: true

        assertThat(locationRequired).isFalse()
    }

    @Test
    fun `a SiteWHLoc storage group does require a location`() {
        val row = liveRow.replace("\"SiteWH\"", "\"SiteWHLoc\"")
        val product = json.decodeFromString<ODataCollection<ReleasedProductDto>>(row)
            .value
            .single()

        val locationRequired = product.storageDimensionGroup
            ?.contains("Loc", ignoreCase = true) ?: true

        assertThat(locationRequired).isTrue()
    }
}
