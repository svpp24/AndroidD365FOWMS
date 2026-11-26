package com.d365fo.mobileops.countcontrol

import androidx.lifecycle.SavedStateHandle
import com.d365fo.mobileops.data.countcontrol.LiveCountControlRepository
import com.d365fo.mobileops.data.remote.dto.CountControlPayload
import com.d365fo.mobileops.data.remote.dto.CountControlServiceResponse
import com.d365fo.mobileops.data.remote.dto.CountedTwicePayload
import com.d365fo.mobileops.data.repository.AssignedWarehouse
import com.d365fo.mobileops.data.repository.WorkerSession
import com.d365fo.mobileops.domain.countcontrol.BinCount
import com.d365fo.mobileops.domain.countcontrol.BinDecision
import com.d365fo.mobileops.domain.countcontrol.CountControlOverview
import com.d365fo.mobileops.domain.countcontrol.CountControlRepository
import com.d365fo.mobileops.domain.countcontrol.CountedTwice
import com.d365fo.mobileops.domain.countcontrol.CountedTwiceBin
import com.d365fo.mobileops.domain.countcontrol.StoreBlindCount
import com.d365fo.mobileops.domain.countcontrol.StoreCounter
import com.d365fo.mobileops.domain.countcontrol.StoreJournalSummary
import com.d365fo.mobileops.domain.countcontrol.VarianceRow
import com.d365fo.mobileops.presentation.common.showsCountControl
import com.d365fo.mobileops.presentation.modules.countcontrol.CountControlViewModel
import com.d365fo.mobileops.presentation.modules.countcontrol.DecideBinViewModel
import com.d365fo.mobileops.presentation.modules.countcontrol.qty
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Count control for the store supervisor (spec section 4, 24 Nov 2026). */
@OptIn(ExperimentalCoroutinesApi::class)
class CountControlTest {

    private class FakeRepository : CountControlRepository {
        var overview: NetworkResult<CountControlOverview> = NetworkResult.Success(
            CountControlOverview("S0142", "Marina", 2, 1, 3, 38,
                StoreJournalSummary("CJ-018231", "WaitingForSupervisor", "Waiting for supervisor", 3, 412, 2, false, "", "")),
        )
        var twice: CountedTwice = CountedTwice(
            toDecide = 1, decided = 0,
            bins = listOf(
                CountedTwiceBin("11", "A-01", "03", "10220", "Olive oil 1L", 7.0, "None",
                    listOf(BinCount("11", "ravi", "Ravi", "BC-0091", "2026-11-24T08:14:00Z", 6.0, true),
                        BinCount("12", "sara", "Sara", "BC-0094", "2026-11-24T13:40:00Z", 8.0, true))),
            ),
        )
        var postAnswer: NetworkResult<String> = NetworkResult.Success("")
        val decisions = mutableListOf<Pair<String, BinDecision>>()
        val holds = mutableListOf<Boolean>()

        override suspend fun overview() = overview
        override suspend fun countedTwice(decided: Boolean): NetworkResult<CountedTwice> =
            NetworkResult.Success(if (decided) CountedTwice(1, 0, emptyList()) else twice)
        override suspend fun counters(): NetworkResult<List<StoreCounter>> = NetworkResult.Success(emptyList())
        override suspend fun blindCounts(): NetworkResult<List<StoreBlindCount>> = NetworkResult.Success(emptyList())
        override suspend fun varianceLines(): NetworkResult<List<VarianceRow>> = NetworkResult.Success(emptyList())
        override suspend fun decide(lineId: String, decision: BinDecision): NetworkResult<String> {
            decisions += lineId to decision
            return NetworkResult.Success("")
        }
        override suspend fun hold(hold: Boolean): NetworkResult<String> {
            holds += hold
            return NetworkResult.Success("")
        }
        override suspend fun post() = postAnswer
    }

    private val repository = FakeRepository()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `keeping one counter's count sends that count's line with KEEP`() {
        val vm = DecideBinViewModel(SavedStateHandle(mapOf("lineId" to "11")), repository)
        vm.load()
        assertThat(vm.state.value.bin?.title).isEqualTo("A-01 · Bin 03")

        vm.choose(DecideBinViewModel.Choice.Keep("12"))
        vm.confirm()

        assertThat(repository.decisions).containsExactly("12" to BinDecision.KEEP)
        assertThat(vm.state.value.done).isTrue()
    }

    @Test
    fun `add together and reject send the bin's line`() {
        val vm = DecideBinViewModel(SavedStateHandle(mapOf("lineId" to "12")), repository)
        vm.load()
        vm.choose(DecideBinViewModel.Choice.Sum)
        vm.confirm()

        assertThat(repository.decisions).containsExactly("11" to BinDecision.SUM)
        assertThat(vm.state.value.bin?.total).isEqualTo(14.0)
    }

    @Test
    fun `nothing is sent before a choice`() {
        val vm = DecideBinViewModel(SavedStateHandle(mapOf("lineId" to "11")), repository)
        vm.load()
        vm.confirm()

        assertThat(repository.decisions).isEmpty()
    }

    @Test
    fun `post now refused while bins are undecided shows D365FO's reason`() {
        repository.postAnswer = NetworkResult.Error(AppFailure.BusinessRule("Won't post until 2 bin(s) are decided.", "BINS_UNDECIDED"))
        val vm = CountControlViewModel(repository)
        vm.load()
        vm.post()

        assertThat(vm.state.value.outcome).isEqualTo(false to "Won't post until 2 bin(s) are decided.")
    }

    @Test
    fun `hold journal holds, and on a held journal releases`() {
        val vm = CountControlViewModel(repository)
        vm.load()
        vm.toggleHold()
        val held = (repository.overview as NetworkResult.Success).data
        repository.overview = NetworkResult.Success(held.copy(journal = held.journal!!.copy(onHold = true)))
        vm.load()
        vm.toggleHold()

        assertThat(repository.holds).containsExactly(true, false).inOrder()
    }

    @Test
    fun `the payload reads D365FO's text numbers`() {
        val overview = LiveCountControlRepository.toOverview(
            LiveCountControlRepository.PAYLOAD_JSON.decodeFromString<CountControlPayload>(
                """{"StoreId":"S0142","StoreName":"Marina","Tiles":{"BinsCountedTwice":"2","CountersCounting":"1","BlindCountsToday":"3","LinesWithVariance":"38"},
                   "StoreJournal":{"JournalId":"CJ-1","Status":"OnHold","StatusLabel":"On hold","BlindCounts":"3","Lines":"412","CountedTwice":"0","OnHold":"true","HeldBy":"sup"}}""",
            ),
        )
        assertThat(overview.binsCountedTwice).isEqualTo(2)
        assertThat(overview.journal?.onHold).isTrue()
        assertThat(overview.journal?.lines).isEqualTo(412)

        val twice = LiveCountControlRepository.toCountedTwice(
            LiveCountControlRepository.PAYLOAD_JSON.decodeFromString<CountedTwicePayload>(
                """{"ToDecide":"1","Decided":"0","Bins":[{"LineId":"5","LocationId":"A-01","BinId":"","ItemId":"10220","ItemName":"",
                   "OnHand":"7.000000","Decision":"None","Counts":[{"LineId":"5","Counter":"ravi","CounterName":"","MasterId":"BC-1","Qty":"6.000000","Included":"true"}]}]}""",
            ),
        )
        assertThat(twice.bins.single().title).isEqualTo("A-01")
        assertThat(twice.bins.single().itemName).isEqualTo("10220")
        assertThat(twice.bins.single().counts.single().counterName).isEqualTo("ravi")
        assertThat(twice.bins.single().onHand).isEqualTo(7.0)
    }

    @Test
    fun `a server without the service, and a refusal, become readable failures`() {
        val missing = LiveCountControlRepository.refusalsMapped(NetworkResult.Error(AppFailure.BusinessRule("Not found", httpCode = 404)))
        assertThat(((missing as NetworkResult.Error).failure as AppFailure.BusinessRule).d365ErrorCode).isEqualTo("NOT_AVAILABLE")

        val refused = LiveCountControlRepository.refusalsMapped(
            NetworkResult.Success(CountControlServiceResponse(success = false, errorCode = "NOT_DEFAULT_WAREHOUSE")),
        )
        assertThat((refused as NetworkResult.Error).failure.message).contains("default store")
    }

    @Test
    fun `Home shows Count control only to a cycle count supervisor in their default store`() {
        val main = AssignedWarehouse("S1", "S0142", "Marina", isDefault = true)
        val other = AssignedWarehouse("S1", "S0287", "Al Barsha", isDefault = false)
        fun session(supervisor: Boolean, selected: AssignedWarehouse, warehouses: List<AssignedWarehouse>) = WorkerSession(
            userName = "sup", displayName = "Sup", personnelNumber = "", isSupervisor = false, mustChangePassword = false,
            isCycleCountSupervisor = supervisor, warehouses = warehouses, selected = selected,
        )

        assertThat(showsCountControl(session(true, main, listOf(main, other)))).isTrue()
        assertThat(showsCountControl(session(true, other, listOf(main, other)))).isFalse()
        assertThat(showsCountControl(session(false, main, listOf(main, other)))).isFalse()
        // An only store counts as the default, as D365FO reads it.
        assertThat(showsCountControl(session(true, other, listOf(other)))).isTrue()
        assertThat(showsCountControl(null)).isFalse()
    }

    @Test
    fun `quantities lose a needless decimal`() {
        assertThat(qty(14.0)).isEqualTo("14")
        assertThat(qty(2.5)).isEqualTo("2.5")
    }
}
