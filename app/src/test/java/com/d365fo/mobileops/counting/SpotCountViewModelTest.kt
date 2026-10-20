package com.d365fo.mobileops.counting

import com.d365fo.mobileops.data.local.entity.TransferMode
import com.d365fo.mobileops.data.repository.AssignedWarehouse
import com.d365fo.mobileops.data.repository.CountingDownloadTracker
import com.d365fo.mobileops.data.repository.WorkerSession
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.domain.counting.TaskResult
import com.d365fo.mobileops.domain.model.CountingJournalSummary
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.modules.cyclecounting.spot.SpotCountViewModel
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpotCountViewModelTest {

    private val remote = FakeCountTaskRepository()
    private val tracker = mockk<CountingDownloadTracker>(relaxed = true)
    private val session = mockk<WorkerSessionRepository>()
    private val scanner = mockk<HidScannerBridge>()
    private val claimStore = FakeClaimStore()
    private lateinit var viewModel: SpotCountViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val worker = mockk<WorkerSession>()
        val warehouse = mockk<AssignedWarehouse>()
        every { warehouse.warehouseId } returns "WH1"
        every { worker.selected } returns warehouse
        every { session.session } returns MutableStateFlow(worker)
        every { scanner.scans } returns MutableSharedFlow()
        val flags = mockk<com.d365fo.mobileops.domain.FeatureFlags>()
        every { flags.countClaims } returns true
        viewModel = SpotCountViewModel(
            remote, tracker, com.d365fo.mobileops.data.counting.CountClaimManager(remote, claimStore), flags, session, scanner,
        )
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `spot creation asks the server, claims the new journal, then fetches it in direct mode`() {
        viewModel.setItem("ITEM-A")
        viewModel.setLocation("A-01")
        viewModel.create()

        assertThat(remote.spotCalls.single()).isEqualTo(Triple("WH1", "ITEM-A", "A-01"))
        val summary = slot<CountingJournalSummary>()
        verify { tracker.start(capture(summary), "WH1", null, any()) }
        assertThat(kotlinx.coroutines.runBlocking { claimStore.get(summary.captured.journalId) }).isNotNull()
        assertThat(summary.captured.journalId).isEqualTo("SPOT-1")
        assertThat(summary.captured.transferMode).isEqualTo(TransferMode.DIRECT)
        assertThat(summary.captured.originType).isEqualTo("SPOT")
        assertThat(viewModel.state.value.createdJournalId).isEqualTo("SPOT-1")
    }

    @Test
    fun `nothing to count is caught before the server`() {
        viewModel.create()

        assertThat(remote.spotCalls).isEmpty()
        assertThat(viewModel.state.value.error).contains("item, a location")
    }

    @Test
    fun `a refusal from the server is shown and nothing is fetched`() {
        remote.spotAnswer = TaskResult.Refused("NOT_ALLOWED", "You don't have permission to do this.")
        viewModel.setLocation("A-01")
        viewModel.create()

        assertThat(viewModel.state.value.error).isEqualTo("You don't have permission to do this.")
        verify(exactly = 0) { tracker.start(any(), any(), any(), any()) }
    }
}
