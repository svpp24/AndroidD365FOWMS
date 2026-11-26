package com.d365fo.mobileops.presentation.modules.countcontrol

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.domain.countcontrol.BinDecision
import com.d365fo.mobileops.domain.countcontrol.CountControlOverview
import com.d365fo.mobileops.domain.countcontrol.CountControlRepository
import com.d365fo.mobileops.domain.countcontrol.CountedTwice
import com.d365fo.mobileops.domain.countcontrol.CountedTwiceBin
import com.d365fo.mobileops.domain.countcontrol.StoreBlindCount
import com.d365fo.mobileops.domain.countcontrol.StoreCounter
import com.d365fo.mobileops.domain.countcontrol.VarianceRow
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Count control screen: tiles, the store journal, hold or release and post. */
@HiltViewModel
class CountControlViewModel @Inject constructor(
    private val repository: CountControlRepository,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val overview: CountControlOverview? = null,
        val busy: Boolean = false,
        /** The last action's outcome: done (true) or refused (false), with D365FO's words. */
        val outcome: Pair<Boolean, String>? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val result = repository.overview()) {
                is NetworkResult.Success -> _state.update { it.copy(loading = false, overview = result.data) }
                is NetworkResult.Error -> _state.update { it.copy(loading = false, error = result.failure.message) }
                NetworkResult.Loading -> Unit
            }
        }
    }

    /** Hold the store journal, or release it when it is on hold. */
    fun toggleHold() {
        val onHold = _state.value.overview?.journal?.onHold ?: return
        act(if (onHold) "Released. It posts in the nightly batch." else "On hold. The nightly batch skips it.") {
            repository.hold(!onHold)
        }
    }

    fun post() = act("Posted.") { repository.post() }

    private fun act(doneText: String, call: suspend () -> NetworkResult<String>) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, outcome = null) }
            val outcome = when (val result = call()) {
                is NetworkResult.Success -> true to result.data.ifBlank { doneText }
                is NetworkResult.Error -> false to result.failure.message
                NetworkResult.Loading -> null
            }
            _state.update { it.copy(busy = false, outcome = outcome) }
            load()
        }
    }
}

/** Counted twice: the bins to decide, or the ones decided. */
@HiltViewModel
class CountedTwiceViewModel @Inject constructor(
    private val repository: CountControlRepository,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val decided: Boolean = false,
        val result: CountedTwice? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun show(decided: Boolean) {
        _state.update { it.copy(decided = decided) }
        load()
    }

    fun load() {
        val decided = _state.value.decided
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val result = repository.countedTwice(decided)) {
                is NetworkResult.Success -> _state.update { it.copy(loading = false, result = result.data) }
                is NetworkResult.Error -> _state.update { it.copy(loading = false, error = result.failure.message) }
                NetworkResult.Loading -> Unit
            }
        }
    }
}

/** Decide a bin: keep one counter's count, add them together or reject both. */
@HiltViewModel
class DecideBinViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: CountControlRepository,
) : ViewModel() {

    /** What the supervisor picked: a count's line (keep it), or add together, or reject. */
    sealed interface Choice {
        data class Keep(val lineId: String) : Choice
        data object Sum : Choice
        data object Reject : Choice
    }

    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val bin: CountedTwiceBin? = null,
        val choice: Choice? = null,
        val busy: Boolean = false,
        val done: Boolean = false,
    )

    private val lineId: String = savedStateHandle.get<String>("lineId").orEmpty()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            // The bin may be decided already (a change of mind): look in both lists.
            var found: CountedTwiceBin? = null
            var error: String? = null
            for (decided in listOf(false, true)) {
                when (val result = repository.countedTwice(decided)) {
                    is NetworkResult.Success -> found = found ?: binOf(result.data)
                    is NetworkResult.Error -> error = result.failure.message
                    NetworkResult.Loading -> Unit
                }
                if (found != null) break
            }
            _state.update {
                it.copy(
                    loading = false,
                    bin = found,
                    error = if (found == null) error ?: "This bin is not counted twice any more." else null,
                )
            }
        }
    }

    private fun binOf(result: CountedTwice): CountedTwiceBin? =
        result.bins.firstOrNull { b -> b.lineId == lineId || b.counts.any { it.lineId == lineId } }

    fun choose(choice: Choice) = _state.update { it.copy(choice = choice) }

    fun confirm() {
        val bin = _state.value.bin ?: return
        val choice = _state.value.choice ?: return
        if (_state.value.busy) return
        val (line, decision) = when (choice) {
            is Choice.Keep -> choice.lineId to BinDecision.KEEP
            Choice.Sum -> bin.lineId to BinDecision.SUM
            Choice.Reject -> bin.lineId to BinDecision.REJECT
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (val result = repository.decide(line, decision)) {
                is NetworkResult.Success -> _state.update { it.copy(busy = false, done = true) }
                is NetworkResult.Error -> _state.update { it.copy(busy = false, error = result.failure.message) }
                NetworkResult.Loading -> Unit
            }
        }
    }
}

/** A list screen's state: counters, blind counts or variance lines. */
data class ListState<T>(
    val loading: Boolean = true,
    val error: String? = null,
    val rows: List<T> = emptyList(),
)

abstract class CountControlListViewModel<T> : ViewModel() {
    private val _state = MutableStateFlow(ListState<T>())
    val state: StateFlow<ListState<T>> = _state.asStateFlow()

    protected abstract suspend fun fetch(): NetworkResult<List<T>>

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val result = fetch()) {
                is NetworkResult.Success -> _state.update { it.copy(loading = false, rows = result.data) }
                is NetworkResult.Error -> _state.update { it.copy(loading = false, error = result.failure.message) }
                NetworkResult.Loading -> Unit
            }
        }
    }
}

@HiltViewModel
class CountersViewModel @Inject constructor(private val repository: CountControlRepository) : CountControlListViewModel<StoreCounter>() {
    override suspend fun fetch() = repository.counters()
}

@HiltViewModel
class BlindCountsViewModel @Inject constructor(private val repository: CountControlRepository) : CountControlListViewModel<StoreBlindCount>() {
    override suspend fun fetch() = repository.blindCounts()
}

@HiltViewModel
class VarianceLinesViewModel @Inject constructor(private val repository: CountControlRepository) : CountControlListViewModel<VarianceRow>() {
    /** The store journal's number, for the header line as in the mock. */
    private val _journalId = MutableStateFlow("")
    val journalId: StateFlow<String> = _journalId.asStateFlow()

    override suspend fun fetch(): NetworkResult<List<VarianceRow>> {
        (repository.overview() as? NetworkResult.Success)?.data?.journal?.journalId?.let { id -> _journalId.value = id }
        return repository.varianceLines()
    }
}
