package com.d365fo.mobileops.presentation.modules.cyclecounting.counts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.counting.CountRequestRepository
import com.d365fo.mobileops.data.counting.CountSummary
import com.d365fo.mobileops.data.counting.CountType
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.presentation.common.focus.BandKind
import com.d365fo.mobileops.presentation.common.focus.BandState
import com.d365fo.mobileops.presentation.common.focus.FocusCaption
import com.d365fo.mobileops.presentation.common.focus.FocusListRow
import com.d365fo.mobileops.presentation.common.focus.FocusScreen
import com.d365fo.mobileops.presentation.common.focus.RowState
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusType
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CountListState(
    val type: CountType = CountType.WALL_TO_WALL,
    val warehouseId: String? = null,
    val loading: Boolean = true,
    val counts: List<CountSummary> = emptyList(),
    val error: String? = null,
    /** A cycle count supervisor does not count a hazard count - they see it by category (user, 10 Nov). */
    val supervisor: Boolean = false,
    /** A blind count that waits for the supervisor - just asked for, or tapped. Shown in the band. */
    val waitingFor: String? = null,
) {
    /** Soonest due first (user, 10 Nov); counts without a due time after them. */
    val open: List<CountSummary> get() = counts.filter { it.open }
        .sortedWith(compareBy({ it.dueUtc.isBlank() }, { it.dueUtc }, { it.masterId }))
    val finished: List<CountSummary> get() = counts.filterNot { it.open }
}

@HiltViewModel
class CountListViewModel @Inject constructor(
    private val requests: CountRequestRepository,
    workerSession: WorkerSessionRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val warehouse = workerSession.session.value?.selected?.warehouseId
    private val _state = MutableStateFlow(
        CountListState(
            type = CountType.fromRoute(savedStateHandle.get<String>("type")) ?: CountType.WALL_TO_WALL,
            warehouseId = warehouse,
            supervisor = workerSession.session.value?.isCycleCountSupervisor == true,
        ),
    )
    val state = _state.asStateFlow()

    /**
     * Asks for a blind count (user, 16 Nov 2026: any worker may). It is made empty and waits for
     * the supervisor, who adds what to count in the count control center (user, 23 Nov 2026) -
     * D365FO refuses to count it before that, so it is not opened: it shows in the list as
     * waiting. One empty count is enough, so a second tap points at the one already waiting.
     */
    fun startBlind() {
        val wh = warehouse ?: return
        if (_state.value.loading) return
        _state.value.open.firstOrNull { it.waitingForItems }?.let { return showWaiting(it.masterId) }
        _state.update { it.copy(loading = true, error = null, waitingFor = null) }
        viewModelScope.launch {
            when (val r = requests.create(CountType.BLIND, wh, emptyList(), emptyList())) {
                is NetworkResult.Success -> {
                    _state.update { it.copy(waitingFor = r.data.masterId.takeIf { id -> id.isNotBlank() }) }
                    refresh()
                }
                is NetworkResult.Error -> _state.update { it.copy(loading = false, error = r.failure.message) }
                NetworkResult.Loading -> Unit
            }
        }
    }

    /** A waiting blind count was tapped: say why it does not open. */
    fun showWaiting(masterId: String) = _state.update { it.copy(waitingFor = masterId) }

    fun refresh() {
        val wh = warehouse ?: return _state.update { it.copy(loading = false, error = "No warehouse is assigned to you.") }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = requests.counts(wh)) {
                is NetworkResult.Success -> _state.update { s ->
                    s.copy(
                        loading = false,
                        counts = r.data.filter { it.type == s.type },
                        // Gone once the supervisor has added items; kept when an older AOS does not list it.
                        waitingFor = s.waitingFor?.takeUnless { id -> r.data.any { it.masterId == id && !it.waitingForItems } },
                    )
                }
                is NetworkResult.Error -> _state.update { it.copy(loading = false, error = r.failure.message) }
                NetworkResult.Loading -> Unit
            }
        }
    }
}

/**
 * One kind of count (user, 10 Nov): Hazard, Wall to wall or Blind. Open counts of the warehouse
 * can be joined - several counters count one count, each with their own lines; finished ones
 * (journal created by the supervisor) show counted, on-hand and variance. Wall to wall also
 * offers counting the whole warehouse, which joins the store's open wall to wall count.
 */
@Composable
fun CountListScreen(
    onBack: () -> Unit,
    onCount: (masterId: String?, type: CountType) -> Unit,
    onResults: (masterId: String) -> Unit,
    onCategories: (masterId: String) -> Unit = {},
    onCreate: (type: CountType) -> Unit = {},
    /** An open count tapped: straight into it (user, 17 Nov 2026). */
    onJoin: (masterId: String?, type: CountType) -> Unit = onCount,
    viewModel: CountListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    // A supervisor's wall to wall count is reviewed in D365FO; a tap says so (user, 16 Nov 2026).
    var reviewHint by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    FocusScreen(
        title = state.type.label,
        onBack = onBack,
        counter = state.open.size.takeIf { !state.loading }?.toString(),
        band = when {
            state.error != null -> BandState(BandKind.Bad, "Not loaded", state.error)
            state.loading -> BandState(BandKind.Info, "Loading", "Asking D365FO for the counts of ${state.warehouseId.orEmpty()}")
            state.waitingFor != null -> BandState(
                BandKind.Info,
                "Waiting for the supervisor",
                "${state.waitingFor} has nothing to count yet",
            )
            state.open.isEmpty() && state.type != CountType.WALL_TO_WALL ->
                BandState(BandKind.Info, "Nothing to count", "No open ${state.type.label.lowercase()} in ${state.warehouseId.orEmpty()}")
            reviewHint != null -> BandState(BandKind.Warn, "Review $reviewHint in D365FO", "Count control center")
            else -> null
        },
        topBarTrailing = {
            IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = FocusColors.ink) }
        },
    ) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            com.d365fo.mobileops.presentation.common.focus.ListTaskBox("Choose a count", com.d365fo.mobileops.presentation.common.focus.TaskIcons.Pick)
            val icon = when (state.type) {
                CountType.HAZARD -> Icons.Default.ReportProblem
                CountType.BLIND -> Icons.Default.VisibilityOff
                CountType.WALL_TO_WALL -> Icons.Default.GridView
            }
            // A cycle count supervisor creates counts (user, 10 Nov) - a record in the control
            // center every counter of the warehouse sees here - and does not count.
            // Any worker may start a blind count (user, 16 Nov 2026); the others stay with the supervisor.
            if (state.supervisor || state.type == CountType.BLIND) {
                FocusListRow(
                    title = if (state.type == CountType.WALL_TO_WALL) "Open a wall to wall count" else "New ${state.type.label.lowercase()}",
                    subtitle = if (state.type == CountType.WALL_TO_WALL) {
                        "The whole of ${state.warehouseId.orEmpty()}, for every counter here."
                    } else if (state.type == CountType.BLIND) {
                        if (state.supervisor) "Add what to count in the count control center." else "Your supervisor adds what to count. You count it then."
                    } else {
                        "By category or item. Every counter of ${state.warehouseId.orEmpty()} sees it."
                    },
                    icon = androidx.compose.material.icons.Icons.Default.Add,
                    chevron = true,
                    onClick = {
                        if (state.type == CountType.BLIND) viewModel.startBlind() else onCreate(state.type)
                    },
                )
            }
            if (state.type == CountType.WALL_TO_WALL && !state.supervisor) {
                FocusListRow(
                    title = "Count the whole warehouse",
                    subtitle = "Location by location. Joins the open wall to wall count of ${state.warehouseId.orEmpty()}, or starts one.",
                    icon = icon,
                    chevron = true,
                    onClick = { onCount(null, state.type) },
                )
            }
            if (state.open.isNotEmpty()) {
                FocusCaption(
                    if (state.supervisor && state.type == CountType.WALL_TO_WALL) "Open · review in D365FO" else "Open",
                    Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
                state.open.forEach { c ->
                    FocusListRow(
                        title = c.masterId,
                        titleIsCode = true,
                        icon = icon,
                        state = if (c.myStatus == "Submitted") RowState.Done else RowState.Open,
                        subtitle = summaryLine(c),
                        count = when {
                            state.supervisor && c.type == CountType.WALL_TO_WALL -> "Control center"
                            state.supervisor -> null
                            c.waitingForItems -> "Waiting"
                            c.myStatus == "Counting" -> "Yours"
                            c.myStatus == "Submitted" -> "Ended"
                            else -> null
                        },
                        countColor = if (state.supervisor && c.type == CountType.WALL_TO_WALL) FocusColors.navy else FocusColors.muted,
                        chevron = !(state.supervisor && c.type == CountType.WALL_TO_WALL),
                        onClick = when {
                            // Hazard and blind: the supervisor sees the items by category.
                            state.supervisor && c.type != CountType.WALL_TO_WALL -> ({ onCategories(c.masterId) })
                            state.supervisor -> ({ reviewHint = c.masterId })
                            // Nothing to count yet: D365FO would refuse the join, so say so here.
                            c.waitingForItems -> ({ viewModel.showWaiting(c.masterId) })
                            else -> ({ onJoin(if (c.type == CountType.WALL_TO_WALL) null else c.masterId, c.type) })
                        },
                    )
                }
            }
            if (state.finished.isNotEmpty()) {
                FocusCaption("Journal created", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                state.finished.forEach { c ->
                    FocusListRow(
                        title = c.masterId,
                        titleIsCode = true,
                        state = RowState.Done,
                        subtitle = summaryLine(c),
                        count = when (c.status) {
                            "Posted" -> "Posted"
                            "CreatingJournal" -> "Creating"
                            else -> "Journal"
                        },
                        chevron = c.hasResults,
                        onClick = if (c.hasResults) ({ onResults(c.masterId) }) else null,
                    )
                }
            }
            if (!state.loading && state.counts.isEmpty() && state.error == null && state.type == CountType.WALL_TO_WALL) {
                Text(
                    "No wall to wall count is open in ${state.warehouseId.orEmpty()} yet.",
                    style = FocusType.description,
                    color = FocusColors.muted,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
    }
}

private fun summaryLine(c: CountSummary): String = listOfNotNull(
    // Only a description the requester wrote: the default one just repeats the reference.
    c.description.takeIf { it.isNotBlank() && (c.reference.isBlank() || !it.contains(c.reference, ignoreCase = true)) },
    c.reference.takeIf { it.isNotBlank() }?.let { "Ref $it" },
    c.dueUtc.takeIf { it.isNotBlank() }?.let { "Due ${dueText(it)}" },
    c.itemCount.takeIf { it > 0 }?.let { if (it == 1) "1 item" else "$it items" },
    when (c.counterCount) {
        0 -> "nobody counting yet"
        1 -> "1 counter"
        else -> "${c.counterCount} counters"
    }.takeIf { c.open },
    c.journalId.takeIf { it.isNotBlank() }?.let { "Journal $it" },
).joinToString(" · ")

/** 2026-11-10T14:00:00Z in the device's time zone: "10 Nov 18:00". */
internal fun dueText(isoUtc: String): String = runCatching {
    val instant = java.time.Instant.parse(if (isoUtc.endsWith("Z")) isoUtc else isoUtc + "Z")
    java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(java.time.ZoneId.systemDefault()).format(instant)
}.getOrDefault(isoUtc)
