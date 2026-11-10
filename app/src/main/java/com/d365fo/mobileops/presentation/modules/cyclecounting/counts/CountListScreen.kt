package com.d365fo.mobileops.presentation.modules.cyclecounting.counts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
) {
    val open: List<CountSummary> get() = counts.filter { it.open }
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
        CountListState(type = CountType.fromRoute(savedStateHandle.get<String>("type")) ?: CountType.WALL_TO_WALL, warehouseId = warehouse),
    )
    val state = _state.asStateFlow()

    fun refresh() {
        val wh = warehouse ?: return _state.update { it.copy(loading = false, error = "No warehouse is assigned to you.") }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = requests.counts(wh)) {
                is NetworkResult.Success -> _state.update { s -> s.copy(loading = false, counts = r.data.filter { it.type == s.type }) }
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
    viewModel: CountListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    FocusScreen(
        title = state.type.label,
        onBack = onBack,
        counter = state.open.size.takeIf { !state.loading }?.toString(),
        band = when {
            state.error != null -> BandState(BandKind.Bad, "Not loaded", state.error)
            state.loading -> BandState(BandKind.Info, "Loading", "Asking D365FO for the counts of ${state.warehouseId.orEmpty()}")
            state.open.isEmpty() && state.type != CountType.WALL_TO_WALL ->
                BandState(BandKind.Info, "Nothing to count", "No open ${state.type.label.lowercase()} in ${state.warehouseId.orEmpty()}")
            else -> null
        },
        topBarTrailing = {
            IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = FocusColors.ink) }
        },
    ) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            val icon = when (state.type) {
                CountType.HAZARD -> Icons.Default.ReportProblem
                CountType.BLIND -> Icons.Default.VisibilityOff
                CountType.WALL_TO_WALL -> Icons.Default.GridView
            }
            if (state.type == CountType.WALL_TO_WALL) {
                FocusListRow(
                    title = "Count the whole warehouse",
                    subtitle = "Location by location. Joins the open wall to wall count of ${state.warehouseId.orEmpty()}, or starts one.",
                    icon = icon,
                    chevron = true,
                    onClick = { onCount(null, state.type) },
                )
            }
            if (state.open.isNotEmpty()) {
                FocusCaption("Open", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                state.open.forEach { c ->
                    FocusListRow(
                        title = c.masterId,
                        titleIsCode = true,
                        icon = icon,
                        state = if (c.myStatus == "Submitted") RowState.Done else RowState.Open,
                        subtitle = summaryLine(c),
                        count = when (c.myStatus) {
                            "Counting" -> "Yours"
                            "Submitted" -> "Ended"
                            else -> null
                        },
                        chevron = true,
                        onClick = { onCount(if (c.type == CountType.WALL_TO_WALL) null else c.masterId, c.type) },
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
    c.description.takeIf { it.isNotBlank() },
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
