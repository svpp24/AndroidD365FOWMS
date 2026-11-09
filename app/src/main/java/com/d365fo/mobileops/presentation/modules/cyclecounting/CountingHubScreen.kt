package com.d365fo.mobileops.presentation.modules.cyclecounting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.domain.model.CountKind
import com.d365fo.mobileops.domain.model.kind
import com.d365fo.mobileops.presentation.common.components.AppTopBar
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * Inventory count (user, 8 Nov): the kinds of count as sub-menus. Not every count starts as a
 * journal - Store count is a free count that becomes a journal only once the supervisor transfers
 * it in the Stock count control center. Cycle and wall-to-wall each open their own journals.
 * There is no Spot count (user, 8 Nov): it is the same as Store count.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountingHubScreen(
    onBack: () -> Unit,
    onStoreCount: () -> Unit,
    onJournals: (CountKind) -> Unit,
    onAccuracy: () -> Unit,
    viewModel: CountingJournalListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // How many of each kind are open for this worker - shown once D365FO has answered.
    val open = state.journals.filter { it.journalId !in state.doneIds }.groupBy { it.kind }
    val known = state.journals.isNotEmpty() || (!state.busy && state.error == null)
    fun badge(kind: CountKind): String? = if (!known) null else when (val n = open[kind].orEmpty().size) {
        0 -> null
        else -> "$n open"
    }
    val recounts = open[CountKind.CYCLE].orEmpty().sumOf { it.recountLines }

    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text("Inventory count") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = WarehouseDimens.ScreenPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // How much of the item master is on the device - counting offline looks items up there.
            item(key = "master") { ItemMasterOnDevice() }
            // The kinds of count as Harbour module cards, each with its tag.
            item(key = "store") {
                com.d365fo.mobileops.presentation.common.components.ModuleCard(
                    icon = Icons.Default.Storefront,
                    color = com.d365fo.mobileops.presentation.common.components.ModuleColor.Green,
                    title = "Store count",
                    tags = listOf("No journal needed"),
                    onClick = onStoreCount,
                )
            }
            item(key = "cycle") {
                com.d365fo.mobileops.presentation.common.components.ModuleCard(
                    icon = Icons.Default.Autorenew,
                    color = com.d365fo.mobileops.presentation.common.components.ModuleColor.Blue,
                    title = "Cycle count",
                    tags = listOfNotNull(badge(CountKind.CYCLE), "$recounts to recount".takeIf { known && recounts > 0 })
                        .ifEmpty { listOf(if (known) "None open" else "Journals") },
                    onClick = { onJournals(CountKind.CYCLE) },
                )
            }
            item(key = "w2w") {
                com.d365fo.mobileops.presentation.common.components.ModuleCard(
                    icon = Icons.Default.GridView,
                    color = com.d365fo.mobileops.presentation.common.components.ModuleColor.Sienna,
                    title = "Wall-to-wall count",
                    tags = listOf(badge(CountKind.WALL_TO_WALL) ?: if (known) "None running" else "Journals"),
                    onClick = { onJournals(CountKind.WALL_TO_WALL) },
                )
            }
            if (state.canViewAccuracy) {
                item(key = "accuracy") {
                    com.d365fo.mobileops.presentation.common.components.ModuleCard(
                        icon = Icons.Default.Insights,
                        color = com.d365fo.mobileops.presentation.common.components.ModuleColor.Petrol,
                        title = "Count accuracy",
                        tags = listOf("Supervisor"),
                        onClick = onAccuracy,
                    )
                }
            }
        }
    }
}
