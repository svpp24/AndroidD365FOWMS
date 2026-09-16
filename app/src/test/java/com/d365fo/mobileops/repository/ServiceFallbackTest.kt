package com.d365fo.mobileops.repository

import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.PurchLineServiceDto
import com.d365fo.mobileops.data.remote.dto.PurchOrderServiceResponse
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.repository.OnDemandLookupRepositoryImpl
import com.d365fo.mobileops.data.repository.ServiceLookupRepository
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.domain.model.TrackingRequirement
import com.d365fo.mobileops.util.NetworkResult
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Response

/**
 * When the app is allowed to fall back from the AOS service to OData, and when it must not.
 *
 * Both directions are silent if wrong. Falling back too eagerly turns a loud, diagnosable failure —
 * an expired token, a throttled environment — into an app that merely feels slow, while doubling
 * the load on a server that is already objecting. Not falling back at all makes the app unusable on
 * every environment where the AOS model has not been deployed yet, which is all of them until it is.
 */
class ServiceFallbackTest {

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

    private fun errorBody(code: Int) = Response.error<PurchOrderServiceResponse>(
        code,
        """{"error":{"message":"nope"}}""".toResponseBody("application/json".toMediaType()),
    )

    @Test
    fun `a 404 means the service is not deployed, so OData answers`() = runTest(dispatcher) {
        coEvery { service.getPurchaseOrder(any(), any()) } returns errorBody(404)

        repository.lookupOrder("PO-1")

        coVerify(exactly = 1) { fallback.lookupOrder("PO-1") }
    }

    @Test
    fun `once it has fallen back it stops probing the service`() = runTest(dispatcher) {
        coEvery { service.getPurchaseOrder(any(), any()) } returns errorBody(404)

        repository.lookupOrder("PO-1")
        repository.lookupOrder("PO-2")
        repository.lookupOrder("PO-3")

        // Only the first lookup pays the wasted round trip; the rest go straight to OData.
        coVerify(exactly = 1) { service.getPurchaseOrder(any(), any()) }
        coVerify(exactly = 1) { fallback.lookupOrder("PO-2") }
        coVerify(exactly = 1) { fallback.lookupOrder("PO-3") }
    }

    @Test
    fun `a 429 is surfaced, never retried against OData`() = runTest(dispatcher) {
        // The environment is throttling. Re-asking the same question a different way is the worst
        // possible response: it doubles the load that caused the throttle in the first place.
        coEvery { service.getPurchaseOrder(any(), any()) } returns errorBody(429)

        val result = repository.lookupOrder("PO-1")

        assertThat(result).isInstanceOf(NetworkResult.Error::class.java)
        coVerify(exactly = 0) { fallback.lookupOrder(any()) }
    }

    @Test
    fun `a 401 is surfaced, never retried against OData`() = runTest(dispatcher) {
        coEvery { service.getPurchaseOrder(any(), any()) } returns errorBody(401)

        val result = repository.lookupOrder("PO-1")

        assertThat(result).isInstanceOf(NetworkResult.Error::class.java)
        coVerify(exactly = 0) { fallback.lookupOrder(any()) }
    }

    @Test
    fun `a 500 is surfaced, never retried against OData`() = runTest(dispatcher) {
        coEvery { service.getPurchaseOrder(any(), any()) } returns errorBody(500)

        val result = repository.lookupOrder("PO-1")

        assertThat(result).isInstanceOf(NetworkResult.Error::class.java)
        coVerify(exactly = 0) { fallback.lookupOrder(any()) }
    }

    @Test
    fun `a service answer carries the dimension flags, so no item call is needed`() =
        runTest(dispatcher) {
            coEvery { service.getPurchaseOrder(any(), any()) } returns Response.success(
                PurchOrderServiceResponse(
                    success = true,
                    purchId = "PO-1",
                    vendorAccount = "1002",
                    vendorName = "Contoso",
                    receivable = true,
                    warehouseId = "100011",
                    lines = listOf(
                        PurchLineServiceDto(
                            lineNumber = 1.0,
                            itemId = "A00001",
                            itemName = "WMSSimpleItem 1",
                            purchUnit = "ea",
                            inventUnit = "ea",
                            orderedQty = 4.0,
                            receivedQty = 1.0,
                            remainingQty = 3.0,
                            batchRequired = true,
                            serialRequired = false,
                            locationRequired = true,
                        ),
                    ),
                ),
            )

            val result = repository.lookupOrder("PO-1")

            val order = (result as NetworkResult.Success).data.order
            assertThat(order.approved).isTrue()
            assertThat(order.vendorName).isEqualTo("Contoso")

            val line = order.lines.single()
            // Received quantity arrives on the line. No ProductReceiptLines join, no summing.
            assertThat(line.receivedQuantity.toInt()).isEqualTo(1)
            assertThat(line.outstandingQuantity.toInt()).isEqualTo(3)
            assertThat(line.tracking).isEqualTo(TrackingRequirement.BATCH)
            coVerify(exactly = 0) { fallback.lookupItem(any()) }
        }

    @Test
    fun `receivability is taken from the server, not re-derived on the device`() =
        runTest(dispatcher) {
            // The server says no even though nothing in the strings would tell the client that.
            coEvery { service.getPurchaseOrder(any(), any()) } returns Response.success(
                PurchOrderServiceResponse(
                    success = true,
                    purchId = "PO-1",
                    receivable = false,
                    approvalStatus = "Confirmed",
                    lines = emptyList(),
                ),
            )

            val order = (repository.lookupOrder("PO-1") as NetworkResult.Success).data.order

            assertThat(order.approved).isFalse()
        }

    @Test
    fun `a business failure from the service is reported, not fallen back from`() =
        runTest(dispatcher) {
            coEvery { service.getPurchaseOrder(any(), any()) } returns Response.success(
                PurchOrderServiceResponse(success = false, errorCode = "NOT_FOUND"),
            )

            val result = repository.lookupOrder("PO-9")

            assertThat(result).isInstanceOf(NetworkResult.Error::class.java)
            coVerify(exactly = 0) { fallback.lookupOrder(any()) }
        }
}
