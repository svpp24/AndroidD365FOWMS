package com.d365fo.mobileops.presentation.modules.cyclecounting.supervisor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.domain.counting.supervisor.VarianceLine
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar
import com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton
import com.d365fo.mobileops.presentation.common.components.ConfirmButton
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton as OutlinedButton
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import java.math.BigDecimal

/**
 * Supervisor variance review: counted vs system quantity and value per line, from D365FO, with
 * filters (over tolerance, ABC class, counter) and approve / reject / request recount / post.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupervisorVarianceScreen(
    onBack: () -> Unit,
    viewModel: SupervisorVarianceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            text = { Text(message) },
            confirmButton = { ConfirmButton(onClick = viewModel::dismissMessage) { Text("OK") } },
        )
    }
    if (state.confirmPost) {
        AlertDialog(
            onDismissRequest = viewModel::cancelPost,
            title = { Text("Post ${state.journalId}?") },
            text = {
                Text(
                    "The counting journal is posted in D365FO and on-hand is adjusted. A posted journal " +
                        "can't be reopened or counted again.",
                )
            },
            confirmButton = { ConfirmButton(onClick = viewModel::post) { Text("Post journal") } },
            dismissButton = { CancelOutlinedButton(onClick = viewModel::cancelPost) { Text("Cancel") } },
        )
    }
    if (state.recountDialog) {
        RecountDialog(
            lines = state.selected.size,
            onConfirm = viewModel::requestRecount,
            onDismiss = viewModel::cancelRecount,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Variance · ${state.journalId}") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.error != null -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(WarehouseDimens.ScreenPadding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ErrorBanner(message = state.error.orEmpty())
                    OutlinedButton(onClick = viewModel::load, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                }
                else -> VarianceContent(state, viewModel)
            }
        }
    }
}

@Composable
private fun VarianceContent(state: SupervisorVarianceState, viewModel: SupervisorVarianceViewModel) {
    val all = state.variance?.lines.orEmpty()
    val currency = state.variance?.currencyCode.orEmpty()
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = WarehouseDimens.ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "${all.count { it.counted }} of ${all.size} lines counted · ${state.overToleranceCount} over tolerance",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.posted) {
                Text("Posted in D365FO.", color = WarehouseStatusColors.green, style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = state.filter.overToleranceOnly,
                    onClick = { viewModel.setFilter(state.filter.copy(overToleranceOnly = !state.filter.overToleranceOnly)) },
                    label = { Text("Over tolerance") },
                )
                state.abcClasses.forEach { abc ->
                    FilterChip(
                        selected = state.filter.abcClass == abc,
                        onClick = { viewModel.setFilter(state.filter.copy(abcClass = abc.takeIf { state.filter.abcClass != it })) },
                        label = { Text("Class $abc") },
                    )
                }
                state.counters.forEach { counter ->
                    FilterChip(
                        selected = state.filter.countedBy == counter,
                        onClick = { viewModel.setFilter(state.filter.copy(countedBy = counter.takeIf { state.filter.countedBy != it })) },
                        label = { Text(counter) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${state.selected.size} selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarehouseStatusColors.text3,
                )
                TextButton(onClick = viewModel::selectAllVisible) { Text("Select shown") }
                if (state.selected.isNotEmpty()) TextButton(onClick = viewModel::clearSelection) { Text("Clear") }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = WarehouseDimens.ScreenPadding, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(state.lines, key = { it.lineNumber }) { line ->
                VarianceRow(
                    line = line,
                    currency = currency,
                    selected = line.lineNumber in state.selected,
                    onToggle = { viewModel.toggle(line.lineNumber) },
                )
            }
        }

        Column(
            modifier = Modifier.padding(WarehouseDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val canAct = state.selected.isNotEmpty() && !state.busy && !state.posted
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::approve, enabled = canAct, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Approve") }
                OutlinedButton(onClick = viewModel::reject, enabled = canAct, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Reject") }
                OutlinedButton(onClick = viewModel::openRecount, enabled = canAct, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Recount") }
            }
            Button(
                onClick = viewModel::askPost,
                enabled = !state.busy && !state.posted,
                modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
            ) { Text(if (state.busy) "Working…" else "Post journal") }
        }
    }
}

@Composable
private fun VarianceRow(line: VarianceLine, currency: String, selected: Boolean, onToggle: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(line.itemNumber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                if (line.itemName != line.itemNumber) Text(line.itemName, style = MaterialTheme.typography.bodySmall)
                Text(
                    text = listOfNotNull(
                        line.locationId?.let { "Loc $it" },
                        line.batchId?.let { "Batch $it" },
                        line.serialId?.let { "Serial $it" },
                        line.abcClass?.let { "Class $it" },
                        line.countedBy?.let { "by $it" },
                        line.reviewStatus.takeIf { it != "PENDING" }?.lowercase(),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = WarehouseStatusColors.text3,
                )
                Text(
                    text = if (line.counted) {
                        "Counted ${plain(line.countedQty)} · System ${plain(line.systemQty)}"
                    } else {
                        "Not counted · System ${plain(line.systemQty)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = signed(line.varianceQty),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        line.overTolerance -> MaterialTheme.colorScheme.error
                        line.varianceQty == 0.0 -> WarehouseStatusColors.green
                        else -> WarehouseStatusColors.amber
                    },
                )
                Text(
                    text = "${signed(line.varianceValue)} $currency".trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = WarehouseStatusColors.text3,
                )
                if (line.overTolerance) {
                    Text("Over tolerance", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun RecountDialog(lines: Int, onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var assignTo by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Request recount") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$lines line${if (lines == 1) "" else "s"} will be counted again, blind. D365FO makes sure it isn't the previous counter.")
                OutlinedTextField(assignTo, { assignTo = it.trim() }, label = { Text("Assign to (optional user name)") }, singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") })
            }
        },
        confirmButton = { ConfirmButton(onClick = { onConfirm(assignTo, note) }) { Text("Request recount") } },
        dismissButton = { CancelOutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun plain(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
private fun signed(value: Double): String = (if (value > 0) "+" else "") + plain(value)
