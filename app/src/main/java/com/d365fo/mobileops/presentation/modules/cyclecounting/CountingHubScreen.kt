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
import androidx.compose.material.icons.filled.CenterFocusStrong
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
 * it in the Stock count control center. Spot, cycle and wall-to-wall each open their own journals.
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
            item(key = "store") {
                CountKindCard(
                    icon = Icons.Default.Storefront,
                    tint = Color(0xFF15803D),
                    title = "Store count",
                    text = "Count the store location by location - no journal needed. Your supervisor checks the counts in the Stock count control center, which makes the counting journal.",
                    badge = null,
                    onClick = onStoreCount,
                )
            }
            item(key = "spot") {
                CountKindCard(
                    icon = Icons.Default.CenterFocusStrong,
                    tint = Color(0xFF7C3AED),
                    title = "Spot count",
                    text = if (state.canCreateSpot) "Count one item or location now, or finish a spot count." else "Finish a spot count.",
                    badge = badge(CountKind.SPOT),
                    onClick = { onJournals(CountKind.SPOT) },
                )
            }
            item(key = "cycle") {
                CountKindCard(
                    icon = Icons.Default.Autorenew,
                    tint = Color(0xFF2E6FD8),
                    title = "Cycle count",
                    text = "The counting journals D365FO plans for this store, triggered counts and recounts.",
                    badge = listOfNotNull(badge(CountKind.CYCLE), "$recounts to recount".takeIf { known && recounts > 0 })
                        .joinToString(" · ").ifEmpty { null },
                    onClick = { onJournals(CountKind.CYCLE) },
                )
            }
            item(key = "w2w") {
                CountKindCard(
                    icon = Icons.Default.GridView,
                    tint = Color(0xFFC1501E),
                    title = "Wall-to-wall count",
                    text = "Count everything in the store while it is frozen, one location at a time.",
                    badge = badge(CountKind.WALL_TO_WALL),
                    onClick = { onJournals(CountKind.WALL_TO_WALL) },
                )
            }
            if (state.canViewAccuracy) {
                item(key = "accuracy") {
                    CountKindCard(
                        icon = Icons.Default.Insights,
                        tint = WarehouseStatusColors.accent,
                        title = "Count accuracy",
                        text = "Accuracy by location, item, class or counter.",
                        badge = null,
                        onClick = onAccuracy,
                    )
                }
            }
        }
    }
}

@Composable
private fun CountKindCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    text: String,
    badge: String?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).background(tint, RoundedCornerShape(8.dp)),
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = WarehouseStatusColors.text3)
            badge?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = tint)
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
