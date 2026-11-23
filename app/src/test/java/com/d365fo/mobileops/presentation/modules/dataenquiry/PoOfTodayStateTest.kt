package com.d365fo.mobileops.presentation.modules.dataenquiry

import com.d365fo.mobileops.domain.model.OrderStatus
import com.d365fo.mobileops.domain.repository.PurchaseOrderSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class PoOfTodayStateTest {

    private fun po(id: String, account: String, name: String) = PurchaseOrderSummary(
        purchId = id,
        vendorAccount = account,
        status = OrderStatus.NONE,
        vendorName = name,
        siteId = null,
        warehouseId = "AAC",
        deliveryDate = null,
    )

    private val orders = listOf(
        po("BSP-PO-000161681", "19721", "BinSina - Central Purchase"),
        po("BSP-PO-000161700", "20001", "Gulf Pharma"),
    )

    private fun search(query: String) = PoOfTodayState(orders = orders, query = query).visible.map { it.purchId }

    @Test
    fun `search matches the vendor name the placeholder promises`() {
        assertEquals(listOf("BSP-PO-000161681"), search("central"))
        assertEquals(listOf("BSP-PO-000161700"), search(" gulf "))
    }

    @Test
    fun `search still matches PO number and vendor account`() {
        assertEquals(listOf("BSP-PO-000161700"), search("161700"))
        assertEquals(listOf("BSP-PO-000161681"), search("19721"))
        assertEquals(2, search("").size)
    }
}
