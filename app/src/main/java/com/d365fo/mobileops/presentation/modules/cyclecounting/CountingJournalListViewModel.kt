package com.d365fo.mobileops.presentation.modules.cyclecounting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.local.dao.CountingProgressRow
import com.d365fo.mobileops.data.local.dao.JournalDao
import com.d365fo.mobileops.data.local.entity.JournalEntity
import com.d365fo.mobileops.data.local.entity.JournalStatus
import com.d365fo.mobileops.data.repository.CountingDownloadTracker
import com.d365fo.mobileops.data.repository.CountingJournalRepository
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.domain.model.CountingJournalSummary
import com.d365fo.mobileops.domain.model.ExportJobStatus
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where one counting journal is in its life on this device. */
enum class CountPhase {
    /** Data is on the device; counting has not been started. */
    READY,

    /** Start counting was pressed; counts are being entered. */
    COUNTING,

    /** End count was pressed and the counts are on their way to D365FO. */
    SUBMITTING,

    /** D365FO accepted the counts. */
    SUBMITTED,

    /** The last submission failed or was interrupted - End count can be pressed again. */
    FAILED,
}

/** A journal whose lines are on this device, and how far the count has got. */
data class JournalCountState(
    val journalId: String,
    val phase: CountPhase,
    val total: Int,
    /** Lines with an entered quantity. */
    val withQuantity: Int,
    /** Lines marked counted without a quantity. */
    val markedOnly: Int,
    /** FAILED: why. SUBMITTED: a warning - some lines were refused - or null if all landed. */
    val error: String? = null,
) {
    val counted: Int get() = withQuantity + markedOnly
    val notCounted: Int get() = (total - counted).coerceAtLeast(0)
    /** Whole-number percentage of lines counted (quantity entered or marked), 0 for an empty journal. */
    val percent: Int get() = if (total == 0) 0 else (counted * 100) / total
}

/**
 * Open counting journals for this worker's assigned warehouse. Loads itself on entry, same as
 * PoOfTodayViewModel - there is nothing to scan here, the question is "what's open for me to
 * count", not "tell me about the thing in my hand".
 *
 * A card has two lives. Before data: "Get data" hands the journal to [CountingDownloadTracker],
 * which runs the export, download and Room import on the application scope so it survives the
 * worker navigating away (and shows on the Activity page). After data: the card's state comes
 * from Room - [countStates] - not from anything in memory, so "View lines / Start counting / End
 * count", the progress tally and a submitted journal all survive the app being restarted. End
 * count is likewise handed to the tracker and runs in the background.
 */
@HiltViewModel
class CountingJournalListViewModel @Inject constructor(
    private val journals: CountingJournalRepository,
    private val workerSession: WorkerSessionRepository,
    private val downloads: CountingDownloadTracker,
    private val journalDao: JournalDao,
) : ViewModel() {

    private val _loaded = MutableStateFlow(CountingJournalListState())

    val state: StateFlow<CountingJournalListState> = combine(
        _loaded,
        downloads.downloads,
        downloads.submissions,
        journalDao.observeCountingJournals(),
        journalDao.observeCountingProgress(),
    ) { loaded, tracked, submissions, roomJournals, progress ->
        val progressById = progress.associateBy { it.journalId }
        val countStates = roomJournals.associate { journal ->
            val submitting = submissions[journal.journalId]?.inProgress == true
            journal.journalId to journal.toCountState(progressById[journal.journalId], submitting)
        }
        loaded.copy(
            // A download's own progress or failure. For a journal with no data yet this drives
            // the whole card; for one already on the device it is a "Get data again" in flight
            // (or failed), shown alongside the counting controls.
            exportStatuses = tracked
                .filterValues { !it.imported }
                .mapValues { (_, download) -> download.status },
            // What a finished "Get data again" did, e.g. "Refreshed - 3 new lines added".
            refreshNotes = tracked.values
                .filter { it.imported && it.note != null }
                .associate { it.journalId to it.note.orEmpty() },
            countStates = countStates,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CountingJournalListState())

    init {
        viewModelScope.launch {
            val warehouseId = workerSession.session.value?.selected?.warehouseId
            _loaded.update { it.copy(warehouseId = warehouseId) }
            load(warehouseId)
        }
    }

    fun refresh() {
        viewModelScope.launch { load(_loaded.value.warehouseId) }
    }

    fun startExport(journalId: String) {
        val journal = _loaded.value.journals.firstOrNull { it.journalId == journalId } ?: return
        val warehouseId = _loaded.value.warehouseId
        if (warehouseId.isNullOrBlank()) return
        downloads.start(journal, warehouseId)
    }

    /** Records the first Start counting press. The caller then navigates to the count screen. */
    fun startCounting(journalId: String) {
        viewModelScope.launch { journals.startCounting(journalId) }
    }

    /** Unlocks a submitted journal so counts can be corrected or added; End count sends them again. */
    fun reopenCounting(journalId: String) {
        viewModelScope.launch { journals.reopenCounting(journalId) }
    }

    /** End count: sends the counted quantities back to D365FO in the background. */
    fun endCount(journalId: String) {
        val description = _loaded.value.journals.firstOrNull { it.journalId == journalId }?.description
        downloads.submit(journalId, description)
    }

    private fun JournalEntity.toCountState(progress: CountingProgressRow?, submitting: Boolean): JournalCountState {
        val phase = when (status) {
            JournalStatus.POSTED -> CountPhase.SUBMITTED
            // PENDING_POST with no live submission behind it means the app was killed mid-way.
            JournalStatus.PENDING_POST -> if (submitting) CountPhase.SUBMITTING else CountPhase.FAILED
            JournalStatus.FAILED -> CountPhase.FAILED
            JournalStatus.DRAFT -> if (countStartedAt != null) CountPhase.COUNTING else CountPhase.READY
        }
        val error = when {
            phase == CountPhase.FAILED && status == JournalStatus.PENDING_POST ->
                "The submission was interrupted. Tap End count to send it again."
            phase == CountPhase.FAILED -> sync.lastError ?: "The submission failed."
            // A submitted journal keeps what D365FO refused (see markSubmittedWithWarning).
            phase == CountPhase.SUBMITTED -> sync.lastError
            else -> null
        }
        return JournalCountState(
            journalId = journalId,
            phase = phase,
            total = progress?.total ?: 0,
            withQuantity = progress?.withQuantity ?: 0,
            markedOnly = progress?.markedOnly ?: 0,
            error = error,
        )
    }

    private suspend fun load(warehouseId: String?) {
        if (warehouseId.isNullOrBlank()) {
            _loaded.update {
                it.copy(busy = false, error = "No warehouse is assigned to you. Ask a supervisor.")
            }
            return
        }

        _loaded.update { it.copy(busy = true, error = null) }
        when (val result = journals.listAssignedJournals(warehouseId)) {
            is NetworkResult.Error -> _loaded.update {
                it.copy(busy = false, error = result.failure.message)
            }
            NetworkResult.Loading -> _loaded.update {
                it.copy(busy = false, error = "Lookup did not complete")
            }
            is NetworkResult.Success -> _loaded.update {
                it.copy(busy = false, journals = result.data)
            }
        }
    }
}

data class CountingJournalListState(
    val warehouseId: String? = null,
    val journals: List<CountingJournalSummary> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    /** A "Get data" run in progress or failed, for journals with no data on the device yet. */
    val exportStatuses: Map<String, ExportJobStatus> = emptyMap(),
    val refreshNotes: Map<String, String> = emptyMap(),
    /** Journals whose lines are on this device, keyed by journal id. Derived from Room. */
    val countStates: Map<String, JournalCountState> = emptyMap(),
)
