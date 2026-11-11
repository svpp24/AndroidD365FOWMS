package com.d365fo.mobileops.presentation.modules.cyclecounting.counts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.d365fo.mobileops.data.counting.RequestedItem
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

/** One first-level category of a hazard count: its items, and how many any counter has counted. */
data class CountCategory(val name: String, val items: List<RequestedItem>) {
    val counted: Int get() = items.count { it.countedByAnyone }
}

data class CountCategoriesState(
    val masterId: String = "",
    val loading: Boolean = true,
    val categories: List<CountCategory> = emptyList(),
    /** The category opened; null shows the categories. */
    val open: String? = null,
    val error: String? = null,
) {
    val itemCount: Int get() = categories.sumOf { it.items.size }
    val countedCount: Int get() = categories.sumOf { it.counted }
}

/** Items without a category in the retail product hierarchy. */
internal const val NO_CATEGORY = "No category"

/** Groups the requested items by first-level category: A to Z, items without one last. */
internal fun groupByCategory(items: List<RequestedItem>): List<CountCategory> =
    items.groupBy { it.category.ifBlank { NO_CATEGORY } }
        .map { (name, rows) -> CountCategory(name, rows.sortedBy { it.itemName.ifBlank { it.itemId }.lowercase() }) }
        .sortedWith(compareBy({ it.name == NO_CATEGORY }, { it.name.lowercase() }))

@HiltViewModel
class CountCategoriesViewModel @Inject constructor(
    private val requests: CountRequestRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val masterId = savedStateHandle.get<String>("masterId").orEmpty()
    private val _state = MutableStateFlow(CountCategoriesState(masterId = masterId))
    val state = _state.asStateFlow()

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = requests.items(masterId)) {
                is NetworkResult.Success -> _state.update { it.copy(loading = false, categories = groupByCategory(r.data)) }
                is NetworkResult.Error -> _state.update { it.copy(loading = false, error = r.failure.message) }
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun open(category: String?) = _state.update { it.copy(open = category) }
}

/**
 * A hazard count as a cycle count supervisor sees it (user, 10 Nov): the items by first-level
 * category, then a category's items by name. The supervisor does not count - this is what they
 * hand out to their counters ("you take Health Care"). Counted shows what any counter has sent.
 */
@Composable
fun CountCategoriesScreen(onBack: () -> Unit, viewModel: CountCategoriesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    val opened = state.categories.firstOrNull { it.name == state.open }
    if (opened != null) BackHandler { viewModel.open(null) }
    FocusScreen(
        title = opened?.name ?: state.masterId,
        onBack = if (opened != null) ({ viewModel.open(null) }) else onBack,
        counter = when {
            state.loading -> null
            opened != null -> "${opened.counted}/${opened.items.size}"
            else -> "${state.countedCount}/${state.itemCount}"
        },
        band = when {
            state.error != null -> BandState(BandKind.Bad, "Not loaded", state.error)
            state.loading -> BandState(BandKind.Info, "Loading", "Asking D365FO for the items of ${state.masterId}")
            opened == null -> BandState(
                BandKind.Info,
                (if (state.categories.size == 1) "1 category" else "${state.categories.size} categories") +
                    " · " + (if (state.itemCount == 1) "1 item" else "${state.itemCount} items"),
                "${state.countedCount} counted so far. Tap a category to see its items.",
            )
            else -> null
        },
        topBarTrailing = {
            IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = FocusColors.ink) }
        },
    ) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
            if (opened == null) {
                items(state.categories, key = { it.name }) { c ->
                    FocusListRow(
                        title = c.name,
                        state = if (c.counted == c.items.size) RowState.Done else RowState.Open,
                        subtitle = (if (c.items.size == 1) "1 item" else "${c.items.size} items") + " · ${c.counted} counted",
                        chevron = true,
                        onClick = { viewModel.open(c.name) },
                    )
                }
            } else {
                items(opened.items.size, key = { "item-$it" }) { index ->
                    val r = opened.items[index]
                    FocusListRow(
                        title = r.itemName.ifBlank { r.itemId },
                        subtitle = listOfNotNull(
                            r.itemId.takeIf { r.itemName.isNotBlank() },
                            listOf(r.configId, r.sizeId, r.colorId, r.styleId).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { null },
                            r.locationId.takeIf { it.isNotBlank() }?.let { "at $it" },
                        ).joinToString(" · ").ifBlank { null },
                        state = if (r.countedByAnyone) RowState.Done else RowState.Open,
                        count = if (r.countedByAnyone) "Counted" else null,
                    )
                }
            }
        }
    }
}
