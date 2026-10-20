package com.d365fo.mobileops.dataenquiry

import com.d365fo.mobileops.domain.model.DeliveryTolerance
import com.d365fo.mobileops.domain.model.OrderStatus
import com.d365fo.mobileops.domain.model.PurchaseOrder
import com.d365fo.mobileops.domain.model.PurchaseOrderLine
import com.d365fo.mobileops.domain.model.TrackingRequirement
import com.d365fo.mobileops.domain.repository.OnDemandLookupRepository
import com.d365fo.mobileops.domain.repository.OrderLookup
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.common.scan.ScanFeedback
import com.d365fo.mobileops.presentation.modules.dataenquiry.PoLinesViewModel
import com.d365fo.mobileops.util.NetworkResult
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PoLinesViewModelTest {

    private val lookups = mockk<OnDemandLookupRepository>()
    private val scanner = mockk<HidScannerBridge>()
    private lateinit var viewModel: PoLinesViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { scanner.scans } returns MutableSharedFlow()
        viewModel = PoLinesViewModel(lookups, scanner, mockk<ScanFeedback>(relaxed = true))
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an unconfirmed order says so and still lists its lines`() {
        answer(OrderLookup(order(listOf(line())), 1, linesInline = true, blockReason = "NOT_CONFIRMED"))

        viewModel.lookUp("PO-1")

        val state = viewModel.state.value
        assertThat(state.blockNotice).startsWith("Not confirmed yet")
        assertThat(state.lines).hasSize(1)
        assertThat(state.linesInline).isTrue()
    }

    @Test
    fun `an unconfirmed order from an older AOS keeps its reason when its lines were withheld`() {
        answer(OrderLookup(order(emptyList()), 1, linesInline = false, blockReason = "NOT_CONFIRMED"))

        viewModel.lookUp("PO-1")

        val state = viewModel.state.value
        assertThat(state.blockNotice).startsWith("Not confirmed yet")
        assertThat(state.linesInline).isFalse()
    }

    @Test
    fun `a receivable order carries no notice, and a failed lookup clears the last one`() {
        answer(OrderLookup(order(emptyList()), 1, linesInline = false, blockReason = "NOT_APPROVED"))
        viewModel.lookUp("PO-1")
        assertThat(viewModel.state.value.blockNotice).startsWith("Not approved yet")

        answer(OrderLookup(order(listOf(line())), 1, linesInline = true))
        viewModel.lookUp("PO-2")
        assertThat(viewModel.state.value.blockNotice).isNull()

        answer(OrderLookup(order(emptyList()), 1, linesInline = false, blockReason = "NOT_OPEN"))
        viewModel.lookUp("PO-3")
        coEvery { lookups.lookupOrder(any()) } returns
            NetworkResult.Error(com.d365fo.mobileops.util.AppFailure.BusinessRule("No purchase order PO-4"))
        coEvery { lookups.lookupLinesForItem(any(), any()) } returns NetworkResult.Success(emptyList())
        viewModel.lookUp("PO-4")
        assertThat(viewModel.state.value.blockNotice).isNull()
    }

    private fun answer(lookup: OrderLookup) {
        coEvery { lookups.lookupOrder(any()) } returns NetworkResult.Success(lookup)
        // A scan on an order whose lines came per item is first tried as an item on it.
        coEvery { lookups.lookupLinesForItem(any(), any()) } returns NetworkResult.Success(emptyList())
    }

    private fun order(lines: List<PurchaseOrderLine>) = PurchaseOrder(
        purchId = "PO-1",
        vendorAccount = "V1",
        vendorName = "Vendor",
        status = OrderStatus.BACKORDER,
        siteId = null,
        warehouseId = null,
        deliveryDate = null,
        approved = false,
        tolerance = DeliveryTolerance.NONE,
        lines = lines,
    )

    private fun line() = PurchaseOrderLine(
        lineId = "PO-1:1.0",
        purchId = "PO-1",
        lineNumber = 1.0,
        itemId = "ITEM-A",
        itemName = "Item A",
        purchaseUnit = "ea",
        inventoryUnit = "ea",
        unitConversionFactor = BigDecimal.ONE,
        orderedQuantity = BigDecimal.TEN,
        receivedQuantity = BigDecimal.ZERO,
        uncommittedQuantity = BigDecimal.ZERO,
        siteId = null,
        warehouseId = null,
        locationId = null,
        tolerance = DeliveryTolerance.NONE,
        tracking = TrackingRequirement.NONE,
    )
}
