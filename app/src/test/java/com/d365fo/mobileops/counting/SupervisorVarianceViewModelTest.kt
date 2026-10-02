package com.d365fo.mobileops.counting

import androidx.lifecycle.SavedStateHandle
import com.d365fo.mobileops.domain.counting.supervisor.JournalVariance
import com.d365fo.mobileops.domain.counting.supervisor.ReviewAction
import com.d365fo.mobileops.domain.counting.supervisor.SupervisorCountRepository
import com.d365fo.mobileops.domain.counting.supervisor.SupervisorRefusal
import com.d365fo.mobileops.domain.counting.supervisor.SupervisorResult
import com.d365fo.mobileops.domain.counting.supervisor.VarianceLine
import com.d365fo.mobileops.presentation.modules.cyclecounting.supervisor.SupervisorVarianceViewModel
import com.d365fo.mobileops.presentation.modules.cyclecounting.supervisor.VarianceFilter
import com.d365fo.mobileops.util.NetworkResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SupervisorVarianceViewModelTest {

    private class FakeSupervisorRepository : SupervisorCountRepository {
        var variance: NetworkResult<JournalVariance> = NetworkResult.Success(
            JournalVariance(
                "AED",
                listOf(
                    line(1.0, over = true, abc = "A", by = "counter1"),
                    line(2.0, over = false, abc = "B", by = "counter2"),
                    line(3.0, over = true, abc = "B", by = "counter2"),
                ),
            ),
        )
        var reviewAnswer: SupervisorResult = SupervisorResult.Done(1)
        var postAnswer: SupervisorResult = SupervisorResult.Done(0)
        val reviews = mutableListOf<Triple<List<Double>, ReviewAction, String?>>()

        override suspend fun variance(journalId: String) = variance
        override suspend fun review(journalId: String, lineNumbers: List<Double>, action: ReviewAction, assignTo: String?, note: String?): SupervisorResult {
            reviews += Triple(lineNumbers, action, assignTo)
            return reviewAnswer
        }
        override suspend fun post(journalId: String) = postAnswer
        override suspend fun accuracy(
            warehouseId: String,
            from: String,
            to: String,
            groupBy: com.d365fo.mobileops.domain.counting.supervisor.AccuracyGroupBy,
        ): NetworkResult<com.d365fo.mobileops.domain.counting.supervisor.AccuracyReport> =
            NetworkResult.Error(com.d365fo.mobileops.util.AppFailure.Offline())
    }

    private val repository = FakeSupervisorRepository()
    private lateinit var viewModel: SupervisorVarianceViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = SupervisorVarianceViewModel(SavedStateHandle(mapOf("journalId" to "CNT-1")), repository)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `over tolerance and class filters narrow the list without recomputing anything`() {
        viewModel.setFilter(VarianceFilter(overToleranceOnly = true))
        assertThat(viewModel.state.value.lines.map { it.lineNumber }).containsExactly(1.0, 3.0)

        viewModel.setFilter(VarianceFilter(overToleranceOnly = true, abcClass = "B"))
        assertThat(viewModel.state.value.lines.map { it.lineNumber }).containsExactly(3.0)

        viewModel.setFilter(VarianceFilter(countedBy = "counter2"))
        assertThat(viewModel.state.value.lines.map { it.lineNumber }).containsExactly(2.0, 3.0)
        assertThat(viewModel.state.value.overToleranceCount).isEqualTo(2)
    }

    @Test
    fun `recount is sent for the selected lines with the assignee`() {
        viewModel.toggle(1.0)
        viewModel.toggle(3.0)
        viewModel.requestRecount("counter9", "behind the racking")

        val (lines, action, assignTo) = repository.reviews.single()
        assertThat(lines).containsExactly(1.0, 3.0).inOrder()
        assertThat(action).isEqualTo(ReviewAction.RECOUNT)
        assertThat(assignTo).isEqualTo("counter9")
    }

    @Test
    fun `nothing selected sends nothing`() {
        viewModel.approve()
        assertThat(repository.reviews).isEmpty()
    }

    @Test
    fun `recount assigned to the previous counter is explained`() {
        repository.reviewAnswer = SupervisorResult.Refused(SupervisorRefusal.RECOUNT_SAME_USER, null)
        viewModel.toggle(1.0)
        viewModel.requestRecount("counter1", "")

        assertThat(viewModel.state.value.message).contains("person who counted the line")
    }

    @Test
    fun `posting an already posted journal is explained, not reported as success`() {
        repository.postAnswer = SupervisorResult.Refused(SupervisorRefusal.ALREADY_POSTED, null)
        viewModel.post()

        assertThat(viewModel.state.value.posted).isFalse()
        assertThat(viewModel.state.value.message).isEqualTo("This journal has already been posted.")
    }

    @Test
    fun `lines outside tolerance needing approval block posting with a clear message`() {
        repository.postAnswer = SupervisorResult.Refused(SupervisorRefusal.LINES_NEED_APPROVAL, null)
        viewModel.post()

        assertThat(viewModel.state.value.message).contains("need approval")
    }

    @Test
    fun `a successful post locks the screen's actions`() {
        viewModel.post()

        assertThat(viewModel.state.value.posted).isTrue()
        assertThat(viewModel.state.value.message).contains("has been posted")
    }

    @Test
    fun `not a supervisor on the server is shown as such`() {
        repository.variance = NetworkResult.Error(
            com.d365fo.mobileops.util.AppFailure.BusinessRule("Only a cycle-count supervisor can do this.", "NOT_SUPERVISOR"),
        )
        viewModel.load()

        assertThat(viewModel.state.value.error).contains("supervisor")
    }

    private companion object {
        fun line(n: Double, over: Boolean, abc: String, by: String) = VarianceLine(
            lineNumber = n,
            itemNumber = "ITEM-$n",
            itemName = "Item $n",
            locationId = "A-01",
            batchId = null,
            serialId = null,
            counted = true,
            countedQty = 5.0,
            systemQty = 7.0,
            varianceQty = -2.0,
            varianceValue = -20.0,
            toleranceStatus = if (over) "OVER" else "WITHIN",
            abcClass = abc,
            countedBy = by,
            reviewStatus = "PENDING",
        )
    }
}
