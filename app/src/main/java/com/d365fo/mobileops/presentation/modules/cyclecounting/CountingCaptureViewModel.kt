package com.d365fo.mobileops.presentation.modules.cyclecounting

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.local.dao.JournalDao
import com.d365fo.mobileops.data.local.entity.JournalLineEntity
import com.d365fo.mobileops.data.local.entity.JournalStatus
import com.d365fo.mobileops.data.repository.CountingJournalRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which lines the lines screen shows. */
enum class LineFilter(val label: String) {
    ALL("All"),
    NOT_COUNTED("Not counted"),
    COUNTED("Counted"),
}

/** A line is counted once it has an entered quantity OR was marked counted (an older feature). */
val JournalLineEntity.isCounted: Boolean
    get() = countedQuantity != null || markedCounted

/**
 * View lines: a read-only overview of one imported counting journal's lines - item number and
 * name, counted or not, and the quantity - with search and a counted / not counted filter, so at
 * any point in a count a worker can see what has and hasn't been done. Scanning and entering
 * quantities is a separate screen ([CountingScanViewModel]).
 *
 * All of the journal's lines are held (a counting journal is a few hundred rows) and searched /
 * filtered in memory: search is instant, works offline, and the tally is always over the whole
 * journal, not just what the current search shows.
 */
@HiltViewModel
class CountingCaptureViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val journalDao: JournalDao,
    private val repository: CountingJournalRepository,
) : ViewModel() {

    val journalId: String = checkNotNull(savedStateHandle["journalId"])

    private val _query = MutableStateFlow("")
    private val _filter = MutableStateFlow(LineFilter.ALL)
    private val _resolvingNames = MutableStateFlow(false)

    val state: StateFlow<CountingLinesState> = combine(
        journalDao.observeAllLines(journalId),
        journalDao.observeCountingJournals().map { list -> list.firstOrNull { it.journalId == journalId } },
        _query,
        _filter,
    ) { lines, journal, query, filter ->
        val visible = lines.filter { line ->
            when (filter) {
                LineFilter.ALL -> true
                LineFilter.NOT_COUNTED -> !line.isCounted
                LineFilter.COUNTED -> line.isCounted
            } && line.matches(query)
        }
        CountingLinesState(
            journalDescription = journal?.description ?: journalId,
            lines = visible,
            totalLines = lines.size,
            counted = lines.count { it.isCounted },
            query = query,
            filter = filter,
            locked = journal?.status == JournalStatus.POSTED || journal?.status == JournalStatus.PENDING_POST,
        )
    }.combine(_resolvingNames) { state, resolving -> state.copy(resolvingNames = resolving) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CountingLinesState())

    init {
        resolveNames()
    }

    /** Fills in item names for any line still showing only its item number. Silent and safe to
     *  repeat - it only asks D365FO about what is still unresolved. */
    fun resolveNames() {
        if (_resolvingNames.value) return
        viewModelScope.launch {
            _resolvingNames.value = true
            runCatching { repository.enrichItemNames(journalId) }
            _resolvingNames.value = false
        }
    }

    fun setQuery(query: String) = _query.update { query }

    fun setFilter(filter: LineFilter) = _filter.update { filter }

    private fun JournalLineEntity.matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return q.split(' ').filter { it.isNotBlank() }.all { token ->
            itemId.contains(token, ignoreCase = true) ||
                itemName.contains(token, ignoreCase = true) ||
                locationId?.contains(token, ignoreCase = true) == true ||
                batchId?.contains(token, ignoreCase = true) == true ||
                serialId?.contains(token, ignoreCase = true) == true
        }
    }
}

data class CountingLinesState(
    val journalDescription: String = "",
    /** The lines matching the current search and filter. */
    val lines: List<JournalLineEntity> = emptyList(),
    /** Every line in the journal, whatever the search shows. */
    val totalLines: Int = 0,
    val counted: Int = 0,
    val query: String = "",
    val filter: LineFilter = LineFilter.ALL,
    val locked: Boolean = false,
    val resolvingNames: Boolean = false,
) {
    val notCounted: Int get() = (totalLines - counted).coerceAtLeast(0)
    val percent: Int get() = if (totalLines == 0) 0 else (counted * 100) / totalLines
}
