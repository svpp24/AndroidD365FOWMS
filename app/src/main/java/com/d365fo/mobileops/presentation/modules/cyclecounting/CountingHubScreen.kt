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
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
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
    viewModel: CountingHubViewModel = hiltViewModel(),
) {
    val canViewAccuracy by viewModel.canViewAccuracy.collectAsStateWithLifecycle()

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
            // "Data up to date" moved to Settings, with a manual Sync (user, 9 Nov).
            // The kinds of count as Harbour module cards, each with its tag.
            item(key = "store") {
                com.d365fo.mobileops.presentation.common.components.ModuleCard(
                    icon = Icons.Default.Storefront,
                    color = com.d365fo.mobileops.presentation.common.components.ModuleColor.Green,
                    title = "Store count",
                    tags = emptyList(),
                    onClick = onStoreCount,
                )
            }
            item(key = "cycle") {
                com.d365fo.mobileops.presentation.common.components.ModuleCard(
                    icon = Icons.Default.Autorenew,
                    color = com.d365fo.mobileops.presentation.common.components.ModuleColor.Blue,
                    title = "Cycle count",
                    // No counts on the menus (user, 9 Nov).
                    tags = emptyList(),
                    onClick = { onJournals(CountKind.CYCLE) },
                )
            }
            item(key = "w2w") {
                com.d365fo.mobileops.presentation.common.components.ModuleCard(
                    icon = Icons.Default.GridView,
                    color = com.d365fo.mobileops.presentation.common.components.ModuleColor.Sienna,
                    title = "Wall-to-wall count",
                    tags = emptyList(),
                    onClick = { onJournals(CountKind.WALL_TO_WALL) },
                )
            }
            if (canViewAccuracy) {
                item(key = "accuracy") {
                    com.d365fo.mobileops.presentation.common.components.ModuleCard(
                        icon = Icons.Default.Insights,
                        color = com.d365fo.mobileops.presentation.common.components.ModuleColor.Petrol,
                        title = "Count accuracy",
                        tags = emptyList(),
                        onClick = onAccuracy,
                    )
                }
            }
        }
    }
}

/**
 * The hub's one question - may this worker open Count accuracy - without the journal list's
 * load: the hub used to list every count journal from D365FO just to show badges, and then the
 * Cycle count screen listed them again (9 Nov, performance).
 */
@dagger.hilt.android.lifecycle.HiltViewModel
class CountingHubViewModel @javax.inject.Inject constructor(
    private val flags: com.d365fo.mobileops.domain.FeatureFlags,
    private val workerSession: com.d365fo.mobileops.data.repository.WorkerSessionRepository,
    private val counterProfiles: com.d365fo.mobileops.data.counting.CounterProfiles,
) : androidx.lifecycle.ViewModel() {
    private val _canViewAccuracy = kotlinx.coroutines.flow.MutableStateFlow(accuracyFor(null))
    val canViewAccuracy: kotlinx.coroutines.flow.StateFlow<Boolean> = _canViewAccuracy

    init {
        if (flags.countAccuracy && flags.countExternalCounters) {
            viewModelScope.launch { _canViewAccuracy.value = accuracyFor(counterProfiles.refresh()) }
        }
    }

    // The same gate as CountingJournalListViewModel.applyCapabilities.
    private fun accuracyFor(profile: com.d365fo.mobileops.domain.counting.CounterProfile?): Boolean {
        val supervisor = workerSession.session.value?.isCycleCountSupervisor == true
        val caps = com.d365fo.mobileops.data.counting.countingCapabilities(flags, profile, supervisor)
        return flags.countAccuracy && !caps.externalCounter && (profile?.isCycleCountSupervisor ?: supervisor)
    }
}
