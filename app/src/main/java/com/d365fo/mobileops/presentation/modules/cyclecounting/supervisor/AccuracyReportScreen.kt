package com.d365fo.mobileops.presentation.modules.cyclecounting.supervisor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.domain.counting.supervisor.AccuracyGroupBy
import com.d365fo.mobileops.domain.counting.supervisor.AccuracyReport
import com.d365fo.mobileops.domain.counting.supervisor.SupervisorCountRepository
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import javax.inject.Inject

/** Preset date ranges, newest-first; inclusive of today. */
enum class AccuracyPeriod(val label: String, val days: Long) {
    WEEK("7 days", 7),
    MONTH("30 days", 30),
    QUARTER("90 days", 90),
}

data class AccuracyState(
    val warehouseId: String? = null,
    val period: AccuracyPeriod = AccuracyPeriod.MONTH,
    val groupBy: AccuracyGroupBy = AccuracyGroupBy.LOCATION,
    val loading: Boolean = true,
    val error: String? = null,
    val report: AccuracyReport? = null,
)

/**
 * Supervisor accuracy report: accuracy % by location, item, ABC class or counter for a date
 * range, with the per-class tolerance the server reports - nothing computed or hardcoded here.
 */
@HiltViewModel
class AccuracyReportViewModel @Inject constructor(
    private val repository: SupervisorCountRepository,
    workerSession: WorkerSessionRepository,
) : ViewModel() {

    internal var today: () -> LocalDate = { LocalDate.now() }

    private val _state = MutableStateFlow(AccuracyState(warehouseId = workerSession.session.value?.selected?.warehouseId))
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun setPeriod(period: AccuracyPeriod) {
        _state.update { it.copy(period = period) }
        load()
    }

    fun setGroupBy(groupBy: AccuracyGroupBy) {
        _state.update { it.copy(groupBy = groupBy) }
        load()
    }

    fun load() {
        val s = _state.value
        val warehouse = s.warehouseId
        if (warehouse.isNullOrBlank()) {
            _state.update { it.copy(loading = false, error = "No warehouse is assigned to you.") }
            return
        }
        val to = today()
        val from = to.minusDays(s.period.days - 1)
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.accuracy(warehouse, from.toString(), to.toString(), s.groupBy)) {
                is NetworkResult.Success -> _state.update { it.copy(loading = false, report = result.data) }
                is NetworkResult.Error -> _state.update { it.copy(loading = false, report = null, error = result.failure.message) }
                NetworkResult.Loading -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccuracyReportScreen(onBack: () -> Unit, viewModel: AccuracyReportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Count accuracy") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.padding(horizontal = WarehouseDimens.ScreenPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccuracyPeriod.entries.forEach { period ->
                        FilterChip(selected = state.period == period, onClick = { viewModel.setPeriod(period) }, label = { Text(period.label) })
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AccuracyGroupBy.entries.forEach { group ->
                        FilterChip(selected = state.groupBy == group, onClick = { viewModel.setGroupBy(group) }, label = { Text(group.label) })
                    }
                }
                state.report?.let { report ->
                    Text(
                        text = "${percent(report.overallPct)} accurate · ${report.accurateLines} of ${report.countedLines} lines" +
                            (state.warehouseId?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    state.error != null -> ErrorBanner(
                        message = state.error.orEmpty(),
                        modifier = Modifier.align(Alignment.Center).padding(WarehouseDimens.ScreenPadding),
                    )
                    state.report?.rows.isNullOrEmpty() -> Text(
                        "No posted counts in this period.",
                        modifier = Modifier.align(Alignment.Center),
                        color = WarehouseStatusColors.text3,
                    )
                    else -> LazyColumn(
                        contentPadding = PaddingValues(horizontal = WarehouseDimens.ScreenPadding, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val currency = state.report?.currencyCode.orEmpty()
                        items(state.report?.rows.orEmpty().sortedBy { it.accuracyPct }, key = { it.key }) { row ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(row.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        Text(percent(row.accuracyPct), fontWeight = FontWeight.SemiBold)
                                    }
                                    LinearProgressIndicator(
                                        progress = { (row.accuracyPct / 100.0).toFloat().coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(
                                        text = listOfNotNull(
                                            "${row.accurateLines} of ${row.countedLines} lines",
                                            "adjusted ${plainNumber(row.varianceValue)} $currency".trim(),
                                            row.tolerance?.let { "tolerance $it" },
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = WarehouseStatusColors.text3,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun percent(value: Double): String =
    BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%"

private fun plainNumber(value: Double): String =
    BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
