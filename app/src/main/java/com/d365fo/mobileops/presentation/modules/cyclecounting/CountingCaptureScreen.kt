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
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.data.local.entity.JournalLineEntity
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = WarehouseDimens.ScreenPadding, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${state.counted} of ${state.totalLines} counted",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${state.percent}%",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (state.totalLines > 0) {
                    CountProgressBar(progress = state.counted.toFloat() / state.totalLines)
                }

                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search item, name, location…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LineFilter.entries.forEach { filter ->
                        val count = when (filter) {
                            LineFilter.ALL -> state.totalLines
                            LineFilter.NOT_COUNTED -> state.notCounted
                            LineFilter.COUNTED -> state.counted
                        }
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { viewModel.setFilter(filter) },
                            label = { Text("${filter.label} $count", style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }

                if (state.resolvingNames) {
                    Text(
                        text = "Loading item names…",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarehouseStatusColors.text3,
                    )
                }
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
                contentPadding = PaddingValues(
                    horizontal = WarehouseDimens.ScreenPadding,
                    vertical = 6.dp,
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
