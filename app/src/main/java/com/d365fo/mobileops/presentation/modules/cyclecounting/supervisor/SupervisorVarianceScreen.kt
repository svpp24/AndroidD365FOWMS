package com.d365fo.mobileops.presentation.modules.cyclecounting.supervisor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.verticalScroll
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
import com.d365fo.mobileops.presentation.common.components.SquareTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.domain.counting.supervisor.CountReasonCode
import com.d365fo.mobileops.domain.counting.supervisor.CountStatus
import com.d365fo.mobileops.domain.counting.supervisor.ReviewAction
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
 * Supervisor variance review: expected, initial, recount and counted quantity, value and status per
 * line, from D365FO, with filters (status, ABC class, counter) and approve / recount / investigate /
 * reject with a reason code and comment, then post.
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
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = null,
                    confirm = { com.d365fo.mobileops.presentation.common.components.ConfirmButton(onClick = viewModel::dismissMessage, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("OK") } },
                )
            },
        )
    }
    if (state.confirmPost) {
        AlertDialog(
            onDismissRequest = viewModel::cancelPost,
            title = { Text("Post ${state.journalId}?") },
            text = {
                Text(
                    "On-hand is adjusted. This can't be undone.",
                )
            },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = viewModel::cancelPost, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
                    confirm = { com.d365fo.mobileops.presentation.common.components.ConfirmButton(onClick = viewModel::post, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Post journal") } },
                )
            },
        )
    }
    state.decision?.let { action ->
        DecisionDialog(
            action = action,
            lines = state.selectedLines,
            reasonCodes = state.variance?.reasonCodes.orEmpty(),
            onConfirm = viewModel::confirmDecision,
            onDismiss = viewModel::cancelDecision,
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
            // The tally in a white card (Harbour): counted in the accent, what needs a decision in amber.
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Color.White, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    "${all.count { it.counted }} of ${all.size} counted",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = WarehouseStatusColors.accent,
                )
                Text(
                    listOfNotNull(
                        "${state.overToleranceCount} need a decision",
                        state.variance?.toleranceText?.let { "tolerance $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.overToleranceCount > 0) WarehouseStatusColors.amber else WarehouseStatusColors.text3,
                )
            }
            if (state.posted) {
                Text("Posted in D365FO.", color = WarehouseStatusColors.green, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                VarianceFilterMenu(state, viewModel)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = viewModel::selectAllVisible) { Text("Select all", maxLines = 1) }
                if (state.selected.isNotEmpty()) {
                    TextButton(onClick = viewModel::clearSelection) { Text("Clear (${state.selected.size})", maxLines = 1) }
                }
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

        // The dock (Harbour). While lines are selected the decisions take Post's place, so the
        // list keeps its room. Posting adjusts on-hand and can't be undone - a hold.
        val canAct = state.selected.isNotEmpty() && !state.busy && !state.posted
        com.d365fo.mobileops.presentation.common.components.FlowDock {
            if (canAct) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.d365fo.mobileops.presentation.common.components.GradientButton(
                            onClick = { viewModel.openDecision(ReviewAction.APPROVE) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Approve") }
                        com.d365fo.mobileops.presentation.common.components.DockOutlinedButton(
                            onClick = { viewModel.openDecision(ReviewAction.RECOUNT) },
                            text = "Recount",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.d365fo.mobileops.presentation.common.components.DockOutlinedButton(
                            onClick = { viewModel.openDecision(ReviewAction.INVESTIGATE) },
                            text = "Investigate",
                            color = WarehouseStatusColors.amber,
                            modifier = Modifier.weight(1f),
                        )
                        com.d365fo.mobileops.presentation.common.components.DockOutlinedButton(
                            onClick = { viewModel.openDecision(ReviewAction.REJECT) },
                            text = "Reject",
                            color = WarehouseStatusColors.red,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                com.d365fo.mobileops.presentation.common.components.HoldButton(
                    label = if (state.busy) "Working…" else "Hold to post journal",
                    onConfirm = viewModel::post,
                    enabled = !state.busy && !state.posted,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Every filter in one menu - status, class, counter and "needs decision" - instead of a sideways chip row. */
@Composable
private fun VarianceFilterMenu(state: SupervisorVarianceState, viewModel: SupervisorVarianceViewModel) {
    var open by remember { mutableStateOf(false) }
    val f = state.filter
    val active = listOfNotNull(
        "Needs decision".takeIf { f.overToleranceOnly },
        f.status?.label,
        f.abcClass?.let { "Class $it" },
        f.countedBy,
    )
    Box {
        TextButton(onClick = { open = true }) {
            Text(if (active.isEmpty()) "Filter: all lines \u25BE" else "Filter: ${active.joinToString(", ")} \u25BE", maxLines = 1)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun item(label: String, checked: Boolean, onClick: () -> Unit) = androidx.compose.material3.DropdownMenuItem(
                text = { Text(label, fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal) },
                leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
                onClick = onClick,
            )
            item("All lines", active.isEmpty()) { viewModel.setFilter(f.copy(overToleranceOnly = false, status = null, abcClass = null, countedBy = null)); open = false }
            item("Needs decision", f.overToleranceOnly) { viewModel.setFilter(f.copy(overToleranceOnly = !f.overToleranceOnly)) }
            state.statusCounts.forEach { (status, count) ->
                item("${status.label} ($count)", f.status == status) { viewModel.setFilter(f.copy(status = status.takeIf { f.status != it })) }
            }
            state.abcClasses.forEach { abc ->
                item("Class $abc", f.abcClass == abc) { viewModel.setFilter(f.copy(abcClass = abc.takeIf { f.abcClass != it })) }
            }
            state.counters.forEach { counter ->
                item("Counted by $counter", f.countedBy == counter) { viewModel.setFilter(f.copy(countedBy = counter.takeIf { f.countedBy != it })) }
            }
        }
    }
}

@Composable
private fun VarianceRow(line: VarianceLine, currency: String, selected: Boolean, onToggle: () -> Unit) {
    // A Harbour row: the item in the accent, its status as a tag, the variance on the right; the
    // card takes the accent tint while selected.
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (selected) WarehouseStatusColors.accentDim else androidx.compose.ui.graphics.Color.White,
        ),
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(line.itemNumber, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = WarehouseStatusColors.accent)
                if (line.itemName != line.itemNumber) Text(line.itemName, style = MaterialTheme.typography.bodyMedium, color = WarehouseStatusColors.text2)
                Text(
                    text = listOfNotNull(
                        line.locationId?.let { "Loc $it" },
                        line.batchId?.let { "Batch $it" },
                        line.serialId?.let { "Serial $it" },
                        line.abcClass?.let { "Class $it" },
                        line.countedBy?.let { "by $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = WarehouseStatusColors.text3,
                )
                Text(
                    text = when {
                        !line.counted -> "Not counted · Expected ${plain(line.systemQty)}"
                        line.recounted -> "Expected ${plain(line.systemQty)} · Initial ${plain(line.initialQty)} · " +
                            "Recount ${plain(line.recountQty)}"
                        else -> "Expected ${plain(line.systemQty)} · Counted ${plain(line.countedQty)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                val notes = listOfNotNull(
                    line.reasonCode?.let { "Reason $it" },
                    line.comment?.let { "\u201C$it\u201D" },
                    line.reviewedBy?.let { "by $it" },
                ).joinToString(" · ")
                if (notes.isNotEmpty()) {
                    Text(notes, style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.text3)
                }
                StatusLabel(line.status)
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
            }
        }
    }
}

@Composable
private fun StatusLabel(status: CountStatus) {
    // A Harbour status tag.
    val kind = when (status) {
        CountStatus.MATCH, CountStatus.APPROVED, CountStatus.CLOSED -> com.d365fo.mobileops.presentation.common.components.Lead.Done
        CountStatus.MINOR_VARIANCE -> com.d365fo.mobileops.presentation.common.components.Lead.Warn
        CountStatus.RECOUNT_REQUIRED, CountStatus.CONFIRMATION_REQUIRED, CountStatus.INVESTIGATE ->
            com.d365fo.mobileops.presentation.common.components.Lead.Bad
        CountStatus.NOT_COUNTED, CountStatus.UNKNOWN -> com.d365fo.mobileops.presentation.common.components.Lead.Idle
    }
    Box(Modifier.padding(top = 2.dp)) { com.d365fo.mobileops.presentation.common.components.StatusTag(status.label, kind) }
}

/**
 * One dialog for every decision: a standard D365 counting reason code (optional), a comment,
 * and for a recount who should do it. The reason code is written to the journal line in D365FO,
 * so it posts with the count.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DecisionDialog(
    action: ReviewAction,
    lines: List<VarianceLine>,
    reasonCodes: List<CountReasonCode>,
    onConfirm: (String?, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val common = lines.mapNotNull { it.reasonCode }.distinct().singleOrNull()
    var reason by remember { mutableStateOf(common) }
    var comment by remember { mutableStateOf(lines.mapNotNull { it.comment }.distinct().singleOrNull().orEmpty()) }
    var assignTo by remember { mutableStateOf("") }
    val count = lines.size
    val (title, verb) = when (action) {
        ReviewAction.APPROVE -> "Approve count" to "Approve"
        ReviewAction.RECOUNT -> "Request recount" to "Request recount"
        ReviewAction.INVESTIGATE -> "Investigate variance" to "Mark for investigation"
        ReviewAction.REJECT -> "Reject count" to "Reject"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (action) {
                        ReviewAction.APPROVE -> "$count line${if (count == 1) "" else "s"} will post with the counted quantity."
                        ReviewAction.RECOUNT -> "$count line${if (count == 1) "" else "s"} will be counted again, blind."
                        ReviewAction.INVESTIGATE -> "$count line${if (count == 1) "" else "s"} held."
                        ReviewAction.REJECT -> "$count line${if (count == 1) "" else "s"} to recount."
                    },
                )
                if (reasonCodes.isNotEmpty()) {
                    Text("Reason", style = MaterialTheme.typography.labelLarge)
                    fun label(code: CountReasonCode) =
                        if (code.description.isNotBlank() && code.description != code.code) "${code.description} (${code.code})" else code.code
                    if (reasonCodes.size > 4) {
                        Column(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            reasonCodes.forEach { code ->
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable { reason = code.code.takeIf { reason != it } }
                                        .heightIn(min = 44.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    androidx.compose.material3.RadioButton(selected = reason == code.code, onClick = null)
                                    Text(label(code), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    } else {
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            reasonCodes.forEach { code ->
                                FilterChip(
                                    selected = reason == code.code,
                                    onClick = { reason = code.code.takeIf { reason != it } },
                                    label = { Text(label(code)) },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(comment, { comment = it }, label = { Text("Comment (optional)") })
                if (action == ReviewAction.RECOUNT) {
                    OutlinedTextField(assignTo, { assignTo = it.trim() }, label = { Text("Assign to (optional user name)") }, singleLine = true)
                }
            }
        },
        confirmButton = {
            com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
                confirm = { com.d365fo.mobileops.presentation.common.components.ConfirmButton(onClick = { onConfirm(reason, comment, assignTo) }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text(verb) } },
            )
        },
    )
}

private fun plain(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
private fun signed(value: Double): String = (if (value > 0) "+" else "") + plain(value)
