package com.d365fo.mobileops.repository

import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.LineServiceResponse
import com.d365fo.mobileops.data.remote.dto.PurchLineServiceDto
import com.d365fo.mobileops.data.remote.dto.PurchOrderServiceResponse
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.repository.OnDemandLookupRepositoryImpl
import com.d365fo.mobileops.data.repository.ServiceLookupRepository
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.util.NetworkResult
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Response

/**
 * How a purchase order of thousands of lines is read.
 *
 * A PO here can carry 500–5000 lines. Sending all of them so the device can filter to the one item
 * just scanned means moving megabytes to draw a single prompt, and it degrades as the order grows —
 * so past a threshold the service reports the count and withholds the lines, and the item step asks
 * per item instead.
 *
 * The failure this guards is subtle: an empty line list with a non-zero count is a perfectly normal
 * response, and code that treats it as "nothing to receive" would refuse the largest orders in the
 * system while working fine on every small one anybody tests with.
 */
class LargeOrderLookupTest {

    private val service = mockk<MobileOpsServiceApi>()
    private val fallback = mockk<OnDemandLookupRepositoryImpl>(relaxed = true)
    private val dispatcher = StandardTestDispatcher()

    private lateinit var repository: ServiceLookupRepository

    @BeforeEach
    fun setUp() {
        repository = ServiceLookupRepository(
            service = service,
            odataFallback = fallback,
            errorMapper = D365ErrorMapper(Json { ignoreUnknownKeys = true }),
            companyProvider = CompanyProvider { "USMF" },
            ioDispatcher = dispatcher,
        )
    }

    private fun line(itemId: String, lineNumber: Double, ordered: Double, received: Double = 0.0) =
        PurchLineServiceDto(
            lineNumber = lineNumber,
            itemId = itemId,
            itemName = "Item $itemId",
            purchUnit = "ea",
            inventUnit = "ea",
            orderedQty = ordered,
            receivedQty = received,
            remainingQty = ordered - received,
        )

    @Test
    fun `a small order arrives complete in one call`() = runTest(dispatcher) {
        coEvery { service.getPurchaseOrder(any(), any()) } returns Response.success(
            PurchOrderServiceResponse(
                success = true,
                purchId = "PO-1",
                receivable = true,
                outstandingLineCount = 3,
                linesIncluded = true,
                lines = listOf(
                    line("A", 1.0, 10.0),
                    line("B", 2.0, 20.0),
                    line("C", 3.0, 30.0),
                ),
            ),
        )

        val lookup = (repository.lookupOrder("PO-1") as NetworkResult.Success).data

        assertThat(lookup.linesInline).isTrue()
        assertThat(lookup.outstandingLineCount).isEqualTo(3)
        assertThat(lookup.order.lines).hasSize(3)
    }

    @Test
    fun `a large order reports its count without sending the lines`() = runTest(dispatcher) {
        coEvery { service.getPurchaseOrder(any(), any()) } returns Response.success(
            PurchOrderServiceResponse(
                success = true,
                purchId = "PO-BIG",
                receivable = true,
                outstandingLineCount = 4800,
                linesIncluded = false,
                lines = emptyList(),
            ),
        )

        val lookup = (repository.lookupOrder("PO-BIG") as NetworkResult.Success).data

        // Empty lines, 4800 outstanding, and success. All three at once is the normal shape here.
        assertThat(lookup.order.lines).isEmpty()
        assertThat(lookup.outstandingLineCount).isEqualTo(4800)
        assertThat(lookup.linesInline).isFalse()
    }

    @Test
    fun `the per-item query returns only the lines carrying that item`() = runTest(dispatcher) {
        coEvery { service.getLinesForItem(any(), any()) } returns Response.success(
            LineServiceResponse(
                success = true,
                lines = listOf(line("A00001", 812.0, 24.0, received = 4.0)),
            ),
        )

        val lines = (repository.lookupLinesForItem("PO-BIG", "A00001") as NetworkResult.Success).data

        assertThat(lines).hasSize(1)
        val only = lines.single()
        assertThat(only.itemId).isEqualTo("A00001")
        assertThat(only.lineNumber).isEqualTo(812.0)
        assertThat(only.receivedQuantity.toInt()).isEqualTo(4)
        assertThat(only.outstandingQuantity.toInt()).isEqualTo(20)
        // The whole point: the order was never fetched to answer this.
        coVerify(exactly = 0) { service.getPurchaseOrder(any(), any()) }
    }

    @Test
    fun `an item not on the order comes back empty rather than as a failure`() =
        runTest(dispatcher) {
            coEvery { service.getLinesForItem(any(), any()) } returns Response.success(
                LineServiceResponse(success = true, errorCode = "NOT_ON_ORDER", lines = emptyList()),
            )

            val result = repository.lookupLinesForItem("PO-BIG", "WRONG-ITEM")

            // The operator is holding something from another delivery. That is an answer, and the
            // client turns it into a message; it is not a transport failure.
            assertThat(result).isInstanceOf(NetworkResult.Success::class.java)
            assertThat((result as NetworkResult.Success).data).isEmpty()
        }

    @Test
    fun `the per-item query falls back to OData when the service is not deployed`() =
        runTest(dispatcher) {
            coEvery { service.getLinesForItem(any(), any()) } returns Response.error(
                404,
                okhttp3.ResponseBody.Companion.create(null, "{}"),
            )

            repository.lookupLinesForItem("PO-1", "A00001")

            coVerify(exactly = 1) { fallback.lookupLinesForItem("PO-1", "A00001") }
        }
}
