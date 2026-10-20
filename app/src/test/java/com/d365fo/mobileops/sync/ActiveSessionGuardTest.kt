package com.d365fo.mobileops.sync

import com.d365fo.mobileops.data.remote.api.SessionServiceApi
import com.d365fo.mobileops.data.remote.dto.SessionServiceRequest
import com.d365fo.mobileops.data.remote.dto.SessionServiceResponse
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.repository.WorkerSession
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.data.sync.ActiveSessionGuard
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import retrofit2.Response

class ActiveSessionGuardTest {

    private class FakeApi : SessionServiceApi {
        val calls = mutableListOf<String>()
        var elsewhere = false
        var otherJobs = 0
        override suspend fun peekSession(body: SessionServiceRequest): Response<SessionServiceResponse> {
            calls += "peek:${body.request.userName}"
            return Response.success(SessionServiceResponse(success = true, otherDevicePendingJobs = otherJobs))
        }
        override suspend fun startSession(body: SessionServiceRequest): Response<SessionServiceResponse> {
            calls += "start:${body.request.userName}:${body.request.deviceId}"
            return Response.success(SessionServiceResponse(success = true))
        }
        override suspend fun checkSession(body: SessionServiceRequest): Response<SessionServiceResponse> {
            calls += "check:${body.request.userName}"
            return Response.success(SessionServiceResponse(success = true, signedInElsewhere = elsewhere))
        }
        override suspend fun endSession(body: SessionServiceRequest): Response<SessionServiceResponse> {
            calls += "end:${body.request.userName}"
            return Response.success(SessionServiceResponse(success = true))
        }
    }

    private val api = FakeApi()
    private val worker = mockk<WorkerSession>().also { every { it.userName } returns "001" }
    private val session = MutableStateFlow<WorkerSession?>(worker)
    private val sessions = mockk<WorkerSessionRepository>(relaxed = true).also {
        every { it.session } returns session
        every { it.signIns } returns MutableSharedFlow()
    }
    private val store = mockk<SecureStore>().also { every { it.deviceId() } returns "DEV-A" }
    private val pending = mockk<com.d365fo.mobileops.data.sync.PendingWorkCounter>().also {
        io.mockk.coEvery { it.forUser(any()) } returns com.d365fo.mobileops.data.sync.PendingWork(jobs = 2, counts = 1)
    }

    private fun guard() = ActiveSessionGuard(
        api, D365ErrorMapper(Json { ignoreUnknownKeys = true }), sessions, CompanyProvider { "BSP" }, store, pending, TestScope(),
    )

    @Test
    fun `still the active device - stays signed in`() = runTest {
        guard().check()

        assertThat(api.calls).containsExactly("check:001")
        verify(exactly = 0) { sessions.signOutElsewhere(any()) }
    }

    @Test
    fun `signed in on another device - signs out here, naming the worker`() = runTest {
        api.elsewhere = true

        guard().check()

        verify {
            sessions.signOutElsewhere(
                match { it.startsWith("001 signed in on another device") && "2 unfinished jobs and 1 count in progress" in it },
            )
        }
    }

    @Test
    fun `nobody signed in - nothing is asked`() = runTest {
        session.value = null

        guard().check()

        assertThat(api.calls).isEmpty()
    }

    @Test
    fun `other device holds work - asks before taking over, starts only on continue`() = runTest {
        api.otherJobs = 3
        val guard = guard()

        guard.beforeTakeover("001")

        assertThat(guard.prompt.value?.work?.jobs).isEqualTo(3)
        assertThat(api.calls).containsExactly("peek:001")

        guard.check()
        assertThat(api.calls).containsExactly("peek:001")
    }

    @Test
    fun `nothing on the other device - takes over straight away`() = runTest {
        val guard = guard()

        guard.beforeTakeover("001")

        assertThat(guard.prompt.value).isNull()
        assertThat(api.calls).containsExactly("peek:001", "start:001:DEV-A").inOrder()
    }
}
