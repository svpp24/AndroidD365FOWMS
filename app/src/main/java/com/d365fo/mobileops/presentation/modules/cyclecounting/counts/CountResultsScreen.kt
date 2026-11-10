package com.d365fo.mobileops.presentation.modules.cyclecounting.counts

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.counting.CountRequestRepository
import com.d365fo.mobileops.data.counting.CountResults
import com.d365fo.mobileops.presentation.common.focus.BandKind
import com.d365fo.mobileops.presentation.common.focus.BandState
import com.d365fo.mobileops.presentation.common.focus.FocusListRow
import com.d365fo.mobileops.presentation.common.focus.FocusScreen
import com.d365fo.mobileops.presentation.common.focus.RowState
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CountResultsState(
    val masterId: String = "",
    val loading: Boolean = true,
    val results: CountResults? = null,
    val error: String? = null,
)

@HiltViewModel
class CountResultsViewModel @Inject constructor(
    private val requests: CountRequestRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(CountResultsState(masterId = savedStateHandle.get<String>("masterId").orEmpty()))
    val state = _state.asStateFlow()

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = requests.results(_state.value.masterId)) {
                is NetworkResult.Success -> _state.update { it.copy(loading = false, results = r.data) }
                is NetworkResult.Error -> _state.update { it.copy(loading = false, error = r.failure.message) }
                NetworkResult.Loading -> Unit
            }
        }
    }
}

/**
 * A finished count (user, 10 Nov): once the supervisor has created the journal, every line's
 * counted quantity, D365FO's on-hand and the variance - for hazard, wall to wall and blind alike.
 */
@Composable
fun CountResultsScreen(onBack: () -> Unit, viewModel: CountResultsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    val results = state.results
    val differing = results?.lines?.count { kotlin.math.abs(it.variance) > 1e-9 } ?: 0
    FocusScreen(
        title = state.masterId,
        onBack = onBack,
        counter = results?.lines?.size?.toString(),
        band = when {
            state.error != null -> BandState(BandKind.Bad, "Not loaded", state.error)
            state.loading && results == null -> BandState(BandKind.Info, "Loading", "Asking D365FO for the results")
            results == null -> null
            results.posted -> BandState(BandKind.Ok, "Posted", "Journal ${results.journalId} · " + differenceText(differing))
            else -> BandState(BandKind.Info, "Journal created", "Journal ${results.journalId}, not posted yet · " + differenceText(differing))
        },
        topBarTrailing = {
            IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = FocusColors.ink) }
        },
    ) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(results?.lines.orEmpty()) { line ->
                val off = kotlin.math.abs(line.variance) > 1e-9
                FocusListRow(
                    title = line.itemId,
                    titleIsCode = true,
                    state = if (off) RowState.Warn else RowState.Done,
                    subtitle = listOfNotNull(
                        line.itemName.takeIf { it.isNotBlank() && !it.equals(line.itemId, ignoreCase = true) },
                        line.variant.takeIf { it.isNotBlank() },
                        line.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                        line.serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" },
                        line.locationId.takeIf { it.isNotBlank() },
                        "Counted ${qty(line.counted)} · On-hand ${qty(line.onHand)}",
                    ).joinToString(" · "),
                    count = (if (line.variance > 0) "+" else "") + qty(line.variance),
                    countColor = when {
                        !off -> FocusColors.muted
                        line.variance < 0 -> FocusColors.badText
                        else -> FocusColors.warnText
                    },
                )
            }
        }
    }
}

private fun differenceText(n: Int) = when (n) {
    0 -> "no differences"
    1 -> "1 line differs"
    else -> "$n lines differ"
}

private fun qty(value: Double): String = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
