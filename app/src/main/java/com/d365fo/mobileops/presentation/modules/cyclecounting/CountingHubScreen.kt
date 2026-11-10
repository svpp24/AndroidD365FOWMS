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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ReportProblem
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
    onCountType: (com.d365fo.mobileops.data.counting.CountType) -> Unit = {},
) {

    // The Count menu in the Focus design (brief section 9): one row per kind of count.
    com.d365fo.mobileops.presentation.common.focus.FocusScreen(title = "Count", onBack = onBack) {
        androidx.compose.foundation.layout.Column(
            Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState()),
        ) {
            // Three kinds of count only (user, 10 Nov 2026): none starts with a journal - counts go to
            // the control center and the supervisor creates the journal. Cycle count, the journal
            // wall-to-wall count and Count accuracy are no longer on this menu.
            com.d365fo.mobileops.presentation.common.focus.FocusListRow(
                title = com.d365fo.mobileops.data.counting.CountType.HAZARD.label,
                subtitle = "Items an outside system asked to be counted",
                icon = Icons.Default.ReportProblem, chevron = true,
                onClick = { onCountType(com.d365fo.mobileops.data.counting.CountType.HAZARD) },
            )
            com.d365fo.mobileops.presentation.common.focus.FocusListRow(
                title = com.d365fo.mobileops.data.counting.CountType.WALL_TO_WALL.label,
                subtitle = "The whole warehouse, location by location",
                icon = Icons.Default.GridView, chevron = true,
                onClick = { onCountType(com.d365fo.mobileops.data.counting.CountType.WALL_TO_WALL) },
            )
            com.d365fo.mobileops.presentation.common.focus.FocusListRow(
                title = com.d365fo.mobileops.data.counting.CountType.BLIND.label,
                subtitle = "Items or locations your supervisor picked",
                icon = Icons.Default.VisibilityOff, chevron = true,
                onClick = { onCountType(com.d365fo.mobileops.data.counting.CountType.BLIND) },
            )
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
