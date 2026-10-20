package com.d365fo.mobileops.sync

import com.d365fo.mobileops.data.local.dao.OrderCreationDao
import com.d365fo.mobileops.data.local.entity.CreatedOrderStatus
import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.OrderServiceRequest
import com.d365fo.mobileops.data.remote.dto.OrderServiceResponse
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.sync.OrderSender
import com.d365fo.mobileops.data.sync.payload.ConfirmPurchaseOrderPayload
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import retrofit2.Response

/** OrderSender.sendConfirmation: how D365FO's answer to a queued PO confirmation lands on its row. */
class OrderSenderConfirmationTest {

    private val api = mockk<MobileOpsServiceApi>()
    private val dao = mockk<OrderCreationDao>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }
    private val sender = OrderSender(api, D365ErrorMapper(json), dao, json)

    private val payload = ConfirmPurchaseOrderPayload(
        idempotencyKey = "key-1",
        dataAreaId = "BSP",
        userName = "002",
        deviceId = "dev",
        purchId = "BSP-PO-000161688",
    )

    @Test
    fun `a confirmation marks the row confirmed with the confirmation number`() = runTest {
        val request = slot<OrderServiceRequest>()
        coEvery { api.confirmPurchaseOrder(capture(request)) } returns Response.success(
            OrderServiceResponse(success = true, documentNumber = "BSP-PO-000161688", confirmationNumber = "BSP-PO-000161688-1"),
        )

        val result = sender.sendConfirmation(payload)

        assertThat(result).isInstanceOf(NetworkResult.Success::class.java)
        assertThat(request.captured.request.purchId).isEqualTo("BSP-PO-000161688")
        assertThat(request.captured.request.idempotencyKey).isEqualTo("key-1")
        coVerify {
            dao.markOrder("key-1", CreatedOrderStatus.CREATED, "BSP-PO-000161688", null, null, "Confirmation BSP-PO-000161688-1", null, any())
        }
    }

    @Test
    fun `already confirmed is a success, not a refusal`() = runTest {
        coEvery { api.confirmPurchaseOrder(any()) } returns Response.success(
            OrderServiceResponse(
                success = true,
                alreadyCreated = true,
                documentNumber = "BSP-PO-000161688",
                confirmationNumber = "BSP-PO-000161688-1",
                message = "BSP-PO-000161688 was already confirmed",
            ),
        )

        val result = sender.sendConfirmation(payload)

        assertThat(result).isInstanceOf(NetworkResult.Success::class.java)
        coVerify {
            dao.markOrder(
                "key-1", CreatedOrderStatus.CREATED, "BSP-PO-000161688", null, null,
                "BSP-PO-000161688 was already confirmed - confirmation BSP-PO-000161688-1", null, any(),
            )
        }
    }

    @Test
    fun `a refusal fails the row with D365FO's reason`() = runTest {
        coEvery { api.confirmPurchaseOrder(any()) } returns Response.success(
            OrderServiceResponse(success = false, errorCode = "NOT_APPROVED", message = "BSP-PO-1 is InReview - only an approved order can be confirmed"),
        )

        val result = sender.sendConfirmation(payload)

        assertThat(result).isInstanceOf(NetworkResult.Error::class.java)
        assertThat((result as NetworkResult.Error).failure).isInstanceOf(AppFailure.BusinessRule::class.java)
        coVerify {
            dao.markOrder("key-1", CreatedOrderStatus.FAILED, "BSP-PO-000161688", null, null, "BSP-PO-1 is InReview - only an approved order can be confirmed", null, any())
        }
    }

    @Test
    fun `D365FO busy keeps the confirmation waiting for another try`() = runTest {
        coEvery { api.confirmPurchaseOrder(any()) } returns Response.success(
            OrderServiceResponse(success = false, errorCode = "RETRY", message = "D365FO was busy"),
        )

        val result = sender.sendConfirmation(payload)

        assertThat((result as NetworkResult.Error).failure).isInstanceOf(AppFailure.Transient::class.java)
        coVerify(exactly = 0) { dao.markOrder(any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
