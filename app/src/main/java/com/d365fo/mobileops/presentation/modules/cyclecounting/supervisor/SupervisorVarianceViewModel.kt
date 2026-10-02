package com.d365fo.mobileops.presentation.modules.cyclecounting.supervisor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.counting.supervisor.LiveSupervisorCountRepository
import com.d365fo.mobileops.domain.counting.supervisor.JournalVariance
import com.d365fo.mobileops.domain.counting.supervisor.ReviewAction
import com.d365fo.mobileops.domain.counting.supervisor.SupervisorCountRepository
import com.d365fo.mobileops.domain.counting.supervisor.SupervisorResult
import com.d365fo.mobileops.domain.counting.supervisor.VarianceLine
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Display-only filters over what the server sent - nothing is recalculated on the device. */
data class VarianceFilter(
    val overToleranceOnly: Boolean = false,
    val abcClass: String? = null,
    val countedBy: String? = null,
)

data class SupervisorVarianceState(
    val journalId: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val variance: JournalVariance? = null,
    val filter: VarianceFilter = VarianceFilter(),
    val selected: Set<Double> = emptySet(),
    val busy: Boolean = false,
    val message: String? = null,
    val posted: Boolean = false,
    val recountDialog: Boolean = false,
    val confirmPost: Boolean = false,
) {
    val lines: List<VarianceLine>
        get() = variance?.lines.orEmpty().filter { line ->
            (!filter.overToleranceOnly || line.overTolerance) &&
                (filter.abcClass == null || line.abcClass == filter.abcClass) &&
                (filter.countedBy == null || line.countedBy == filter.countedBy)
        }
    val abcClasses: List<String> get() = variance?.lines.orEmpty().mapNotNull { it.abcClass }.distinct().sorted()
    val counters: List<String> get() = variance?.lines.orEmpty().mapNotNull { it.countedBy }.distinct().sorted()
    val overToleranceCount: Int get() = variance?.lines.orEmpty().count { it.overTolerance }
}

/**
 * Supervisor variance review for one journal: counted, system and variance quantity and value
 * from D365FO, filters, and approve / reject / request recount / post. Reachable only for a
 * cycle-count supervisor with [com.d365fo.mobileops.domain.FeatureFlags.countSupervisor] on -
 * and the server checks again on every call.
 */
@HiltViewModel
class SupervisorVarianceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SupervisorCountRepository,
) : ViewModel() {

    private val journalId: String = checkNotNull(savedStateHandle["journalId"])
    private val _state = MutableStateFlow(SupervisorVarianceState(journalId = journalId))
    val state: StateFlow<SupervisorVarianceState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val result = repository.variance(journalId)) {
                is NetworkResult.Success -> _state.update {
                    it.copy(loading = false, variance = result.data, selected = emptySet())
                }
                is NetworkResult.Error -> _state.update { it.copy(loading = false, error = result.failure.message) }
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun setFilter(filter: VarianceFilter) = _state.update { it.copy(filter = filter, selected = emptySet()) }

    fun toggle(lineNumber: Double) = _state.update {
        it.copy(selected = if (lineNumber in it.selected) it.selected - lineNumber else it.selected + lineNumber)
    }

    fun selectAllVisible() = _state.update { s -> s.copy(selected = s.lines.mapTo(HashSet()) { it.lineNumber }) }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun approve() = review(ReviewAction.APPROVE, null, null)

    fun reject() = review(ReviewAction.REJECT, null, null)

    fun openRecount() = _state.update { it.copy(recountDialog = true) }

    fun cancelRecount() = _state.update { it.copy(recountDialog = false) }

    fun requestRecount(assignTo: String, note: String) {
        _state.update { it.copy(recountDialog = false) }
        review(ReviewAction.RECOUNT, assignTo, note)
    }

    fun askPost() = _state.update { it.copy(confirmPost = true) }

    fun cancelPost() = _state.update { it.copy(confirmPost = false) }

    fun post() {
        _state.update { it.copy(confirmPost = false, busy = true) }
        viewModelScope.launch {
            val result = repository.post(journalId)
            _state.update {
                it.copy(
                    busy = false,
                    posted = it.posted || result is SupervisorResult.Done,
                    message = describe(result, done = "$journalId has been posted."),
                )
            }
        }
    }

    private fun review(action: ReviewAction, assignTo: String?, note: String?) {
        val lines = _state.value.selected.sorted()
        if (lines.isEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = repository.review(journalId, lines, action, assignTo, note)
            val verb = when (action) {
                ReviewAction.APPROVE -> "approved"
                ReviewAction.REJECT -> "rejected"
                ReviewAction.RECOUNT -> "sent for recount"
            }
            _state.update {
                it.copy(busy = false, message = describe(result, done = "${lines.size} line${if (lines.size == 1) "" else "s"} $verb."))
            }
            if (result is SupervisorResult.Done) load()
        }
    }

    private fun describe(result: SupervisorResult, done: String): String = when (result) {
        is SupervisorResult.Done -> done
        is SupervisorResult.Unreachable -> "Couldn't reach D365FO: ${result.message}"
        is SupervisorResult.Refused -> LiveSupervisorCountRepository.messageFor(result.reason, result.message)
    }
}
