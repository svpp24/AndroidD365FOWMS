package com.d365fo.mobileops.counting

import com.d365fo.mobileops.data.repository.AssignedWarehouse
import com.d365fo.mobileops.data.repository.WorkerSession
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.domain.counting.supervisor.AccuracyGroupBy
import com.d365fo.mobileops.domain.counting.supervisor.AccuracyReport
import com.d365fo.mobileops.domain.counting.supervisor.AccuracyRow
import com.d365fo.mobileops.domain.counting.supervisor.JournalVariance
import com.d365fo.mobileops.domain.counting.supervisor.ReviewAction
import com.d365fo.mobileops.domain.counting.supervisor.SupervisorCountRepository
import com.d365fo.mobileops.domain.counting.supervisor.SupervisorResult
import com.d365fo.mobileops.presentation.modules.cyclecounting.supervisor.AccuracyPeriod
import com.d365fo.mobileops.presentation.modules.cyclecounting.supervisor.AccuracyReportViewModel
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class AccuracyReportViewModelTest {

    private class FakeRepository : SupervisorCountRepository {
        val calls = mutableListOf<List<Any>>()
        var answer: NetworkResult<AccuracyReport> = NetworkResult.Success(
            AccuracyReport(
                currencyCode = "AED",
                countedLines = 10,
                accurateLines = 9,
                overallPct = 90.0,
                rows = listOf(AccuracyRow("A-01", "A-01", 10, 9, 90.0, 12.5, "Class A: 2%")),
            ),
        )

        override suspend fun variance(journalId: String): NetworkResult<JournalVariance> = error("unused")
        override suspend fun review(journalId: String, lineNumbers: List<Double>, action: ReviewAction, assignTo: String?, note: String?): SupervisorResult = error("unused")
        override suspend fun post(journalId: String): SupervisorResult = error("unused")
        override suspend fun accuracy(warehouseId: String, from: String, to: String, groupBy: AccuracyGroupBy): NetworkResult<AccuracyReport> {
            calls += listOf(warehouseId, from, to, groupBy)
            return answer
        }
    }

    private val repository = FakeRepository()
    private lateinit var viewModel: AccuracyReportViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val session = mockk<WorkerSessionRepository>()
        val worker = mockk<WorkerSession>()
        val warehouse = mockk<AssignedWarehouse>()
        every { warehouse.warehouseId } returns "WH1"
        every { worker.selected } returns warehouse
        every { session.session } returns MutableStateFlow(worker)
        viewModel = AccuracyReportViewModel(repository, session).also { it.today = { LocalDate.of(2026, 10, 2) } }
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `report and per-class tolerance come from the server as-is`() {
        viewModel.load()
        val report = viewModel.state.value.report!!
        assertThat(report.overallPct).isEqualTo(90.0)
        assertThat(report.rows.single().tolerance).isEqualTo("Class A: 2%")
    }

    @Test
    fun `period and grouping are sent to the server`() {
        viewModel.setPeriod(AccuracyPeriod.WEEK)
        viewModel.setGroupBy(AccuracyGroupBy.ABC_CLASS)

        assertThat(repository.calls.last()).containsExactly("WH1", "2026-09-26", "2026-10-02", AccuracyGroupBy.ABC_CLASS).inOrder()
    }

    @Test
    fun `a grouping the server can't do yet is explained`() {
        repository.answer = NetworkResult.Error(
            AppFailure.BusinessRule("This server can't group accuracy by counter yet.", "GROUPING_NOT_AVAILABLE"),
        )
        viewModel.setGroupBy(AccuracyGroupBy.COUNTER)

        assertThat(viewModel.state.value.error).contains("can't group accuracy by counter")
        assertThat(viewModel.state.value.report).isNull()
    }
}
