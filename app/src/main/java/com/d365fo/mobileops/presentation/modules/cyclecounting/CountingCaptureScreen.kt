package com.d365fo.mobileops.presentation.modules.cyclecounting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar
import com.d365fo.mobileops.presentation.common.components.CountProgressBar
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * View lines: a read-only overview of every line in the journal - item number and name, counted
 * or not, and the counted quantity - searchable and filterable, so at any point in a count a
 * worker can see what has and hasn't been done. Scanning and entering quantities is the separate
 * Start counting screen ([CountingScanScreen]).
 *
 * Kept deliberately light: a row is a few lines of text with no inputs, so the list scrolls
 * smoothly even on a low-end handheld.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountingCaptureScreen(
    onBack: () -> Unit,
    viewModel: CountingCaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // A new search or filter shows its matches from the first one, not wherever the old list
    // was scrolled to.
    LaunchedEffect(state.query, state.filter) { listState.scrollToItem(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                // Not "Lines - <journal description>" - the description is the journal name's
                // own generic text (e.g. "Inventory Counting Journal", the same on every count
                // journal), not this journal's own identity, and prefixed it overflowed the top
                // bar. "View lines" matches the button that opened this screen; which journal
                // it's showing was already on the card that button was tapped from.
                title = { Text("View lines") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // All / Not counted / Counted as tabs under the bar, with their counts (Harbour).
            val filters = LineFilter.entries
            com.d365fo.mobileops.presentation.common.components.SegmentTabs(
                labels = filters.map { it.label },
                selected = filters.indexOf(state.filter),
                onSelect = { viewModel.setFilter(filters[it]) },
                badges = filters.withIndex().associate { (i, filter) ->
                    i to when (filter) {
                        LineFilter.ALL -> state.totalLines
                        LineFilter.NOT_COUNTED -> state.notCounted
                        LineFilter.COUNTED -> state.counted
                    }
                }.filterValues { it > 0 },
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = WarehouseDimens.ScreenPadding, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // How far along, in one white card: the ring, the tally, and the bar.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    com.d365fo.mobileops.presentation.common.components.CountProgressRing(
                        progress = if (state.totalLines > 0) state.counted.toFloat() / state.totalLines else 0f,
                        percent = state.percent,
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "${state.counted} of ${state.totalLines} counted",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = WarehouseStatusColors.accent,
                        )
                        Text(
                            text = "${state.notCounted} still to count",
                            style = MaterialTheme.typography.bodyMedium,
                            color = WarehouseStatusColors.text3,
                        )
                    }
                }

                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search item, name, location…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = WarehouseStatusColors.accent) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    shape = com.d365fo.mobileops.presentation.common.components.HeroFieldShape,
                    colors = com.d365fo.mobileops.presentation.common.components.heroFieldColors(),
                )

                if (state.resolvingNames) {
                    Text(
                        text = "Loading item names…",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarehouseStatusColors.text3,
                    )
                }
                com.d365fo.mobileops.presentation.common.components.ListHeader(
                    icon = Icons.Default.Checklist,
                    title = "Lines",
                    count = state.lines.size,
                )
            }

            if (state.lines.isEmpty()) {
                Text(
                    text = if (state.totalLines == 0) "This journal has no lines." else "No lines match.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(WarehouseDimens.ScreenPadding),
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(
                    start = WarehouseDimens.ScreenPadding,
                    end = WarehouseDimens.ScreenPadding,
                    bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.lines, key = { it.journalLineId }, contentType = { "line" }) { line ->
                    CountingLineRow(line)
                }
            }
        }
    }
}
