package com.d365fo.mobileops.presentation.modules.cyclecounting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton
import com.d365fo.mobileops.presentation.common.components.ConfirmButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar
import com.d365fo.mobileops.presentation.common.components.CountProgressBar
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
import com.d365fo.mobileops.data.local.entity.CountClaimEntity
import com.d365fo.mobileops.domain.model.CountRange
import com.d365fo.mobileops.domain.model.CountingJournalSummary
import com.d365fo.mobileops.domain.model.freezeBanner
import com.d365fo.mobileops.domain.model.originLabel
import com.d365fo.mobileops.domain.model.ExportJobStatus
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton as OutlinedButton
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.util.Formatters

/**
 * Open counting journals for this worker's assigned warehouse - headers only, loaded on entry.
 *
 * A card with no data yet has one button, "Get data", which runs in the background (see
 * CountingJournalListViewModel and the Activity page). Once the lines are on the device the card
 * becomes a counting control panel: View lines, Start counting (Continue counting once started)
 * and End count, with a live "counted X of Y" tally. End count asks for confirmation first,
 * spelling out how many lines are still uncounted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountingJournalListScreen(
    onBack: () -> Unit,
    onViewLines: (String) -> Unit,
    onStartCounting: (String) -> Unit,
    onReviewVariance: (String) -> Unit = {},
    onNewSpotCount: () -> Unit = {},
    onAccuracy: () -> Unit = {},
    viewModel: CountingJournalListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmEndFor by remember { mutableStateOf<String?>(null) }
    var confirmReopenFor by remember { mutableStateOf<String?>(null) }

    confirmReopenFor?.let { journalId ->
        AlertDialog(
            onDismissRequest = { confirmReopenFor = null },
            title = { Text("Reopen counting?") },
            text = {
                Text(
                    "The counts stay as they are. You can correct them or count more lines, then " +
                        "press End count to send them again.",
                )
            },
            confirmButton = {
                ConfirmButton(
                    onClick = {
                        confirmReopenFor = null
                        viewModel.reopenCounting(journalId)
                    },
                ) { Text("Reopen") }
            },
            dismissButton = {
                CancelOutlinedButton(onClick = { confirmReopenFor = null }) { Text("Cancel") }
            },
        )
    }

    state.rangePickerFor?.let { journalId ->
        state.journals.firstOrNull { it.journalId == journalId }?.let { journal ->
            RangePickerDialog(
                journal = journal,
                onPick = { viewModel.pickRange(journalId, it) },
                onDismiss = viewModel::cancelRangePicker,
            )
        }
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            text = { Text(message) },
            confirmButton = { ConfirmButton(onClick = viewModel::dismissMessage) { Text("OK") } },
        )
    }

    confirmEndFor?.let { journalId ->
        state.countStates[journalId]?.let { countState ->
            EndCountDialog(
                countState = countState,
                onConfirm = {
                    confirmEndFor = null
                    viewModel.endCount(journalId)
                },
                onDismiss = { confirmEndFor = null },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Inventory count") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.canViewAccuracy) {
                        IconButton(onClick = onAccuracy) {
                            Icon(Icons.Default.Insights, contentDescription = "Count accuracy")
                        }
                    }
                    if (state.canCreateSpot) {
                        IconButton(onClick = onNewSpotCount) {
                            Icon(Icons.Default.Add, contentDescription = "New spot count")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.busy && state.journals.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                state.error != null && state.journals.isEmpty() -> {
                    ErrorBanner(
                        message = state.error.orEmpty(),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(WarehouseDimens.ScreenPadding),
                    )
                }

                state.journals.isEmpty() -> {
                    Text(
                        text = state.warehouseId
                            ?.let { "No open counting journals in $it." }
                            ?: "No open counting journals.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(WarehouseDimens.ScreenPadding),
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = WarehouseDimens.ScreenPadding),
                        contentPadding = PaddingValues(vertical = WarehouseDimens.ItemSpacing),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.externalCounter) {
                            item(key = "external") {
                                Text(
                                    text = "External counter" + (state.counterTeam?.let { " · team $it" } ?: "") +
                                        " - only journals assigned to your team are listed.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = WarehouseStatusColors.text3,
                                )
                            }
                        }
                        state.error?.let { error ->
                            item(key = "error") {
                                ErrorBanner(
                                    message = "Couldn't refresh the journal list ($error). " +
                                        "Journals already on this device are shown.",
                                )
                            }
                        }
                        items(state.journals, key = { it.journalId }) { journal ->
                            CountingJournalRow(
                                journal = journal,
                                exportStatus = state.exportStatuses[journal.journalId],
                                countState = state.countStates[journal.journalId],
                                refreshNote = state.refreshNotes[journal.journalId],
                                claimsEnabled = state.claimsEnabled,
                                myClaim = state.myClaims[journal.journalId],
                                busy = state.claiming == journal.journalId || state.checking == journal.journalId,
                                onReclaim = { viewModel.reclaim(journal.journalId) },
                                onGetData = { viewModel.startExport(journal.journalId) },
                                onViewLines = { onViewLines(journal.journalId) },
                                onStartCounting = {
                                    viewModel.startCounting(journal.journalId)
                                    onStartCounting(journal.journalId)
                                },
                                onEndCount = { confirmEndFor = journal.journalId },
                                onResendExceptions = { viewModel.resendExceptions(journal.journalId) },
                                onReviewVariance = { onReviewVariance(journal.journalId) }.takeIf { state.canReviewVariance },
                                onReopen = { confirmReopenFor = journal.journalId },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountingJournalRow(
    journal: CountingJournalSummary,
    exportStatus: ExportJobStatus?,
    countState: JournalCountState?,
    refreshNote: String?,
    claimsEnabled: Boolean,
    myClaim: CountClaimEntity?,
    busy: Boolean,
    onReclaim: () -> Unit,
    onGetData: () -> Unit,
    onViewLines: () -> Unit,
    onStartCounting: () -> Unit,
    onEndCount: () -> Unit,
    onResendExceptions: () -> Unit,
    onReviewVariance: (() -> Unit)?,
    onReopen: () -> Unit,
) {
    val refreshing = exportStatus != null && exportStatus !is ExportJobStatus.Failed
    var menuOpen by remember { mutableStateOf(false) }

    // The rarely-used actions live in a menu instead of each taking a full-width button.
    val canRefresh = countState != null && !refreshing &&
        countState.phase != CountPhase.SUBMITTING && countState.phase != CountPhase.SUBMITTED &&
        countState.phase != CountPhase.POSTED
    val canReopen = countState?.phase == CountPhase.SUBMITTED
    // Tapping the card itself continues counting - the most common action, one tap from the list.
    val canContinue = countState != null && !refreshing && !busy &&
        countState.phase != CountPhase.SUBMITTING && countState.phase != CountPhase.SUBMITTED &&
        countState.phase != CountPhase.POSTED

    Card(
        modifier = Modifier.fillMaxWidth()
            .then(if (canContinue) Modifier.clickable(onClick = onStartCounting) else Modifier),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = journal.journalId,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // Description, size and date on one quiet line rather than three.
                    Text(
                        text = listOfNotNull(
                            journal.description?.takeIf { it.isNotBlank() },
                            "${journal.lineCount} lines",
                            journal.journalDate?.let { Formatters.shortDate(it) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = WarehouseStatusColors.text3,
                        maxLines = 2,
                    )
                }
                if (canRefresh || canReopen || onReviewVariance != null) {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (canRefresh) {
                                DropdownMenuItem(
                                    text = { Text("Get data again") },
                                    onClick = { menuOpen = false; onGetData() },
                                )
                            }
                            if (canReopen) {
                                DropdownMenuItem(
                                    text = { Text("Reopen counting") },
                                    onClick = { menuOpen = false; onReopen() },
                                )
                            }
                            if (onReviewVariance != null) {
                                DropdownMenuItem(
                                    text = { Text("Review variance") },
                                    onClick = { menuOpen = false; onReviewVariance() },
                                )
                            }
                        }
                    }
                }
            }

            PassBadges(journal)
            freezeBanner(journal.freezeState, journal.snapshotAt) { Formatters.clockTime(it) }?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.amber)
            }
            if (claimsEnabled) {
                ClaimLine(journal = journal, myClaim = myClaim)
            }
            if (myClaim?.lost == true) {
                ErrorBanner(
                    message = (myClaim.lostReason ?: "Your claim on this journal was lost.") +
                        " Your counts are kept on this device. Claim the journal again to submit them.",
                )
                Button(
                    onClick = onReclaim,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
                ) { Text(if (busy) "Claiming…" else "Claim again") }
            }
            if (busy && myClaim?.lost != true) {
                ProgressRow(label = "Checking with D365FO…", percent = null)
            }

            if (countState != null) {
                CountControls(
                    countState = countState,
                    refreshStatus = exportStatus,
                    refreshNote = refreshNote,
                    onRefresh = onGetData,
                    onViewLines = onViewLines,
                    onStartCounting = onStartCounting,
                    onEndCount = onEndCount,
                    onResendExceptions = onResendExceptions,
                    onReopen = onReopen,
                    submitBlocked = myClaim?.lost == true,
                )
            } else {
                DownloadSection(status = exportStatus, onGetData = onGetData)
            }
        }
    }
}

/**
 * The card once its lines are on the device: one tally line, a thin bar, any status that needs
 * saying, and at most two rows of buttons - View lines beside the main action, and End count
 * only while a count is actually under way.
 */
@Composable
private fun CountControls(
    countState: JournalCountState,
    refreshStatus: ExportJobStatus?,
    refreshNote: String?,
    onRefresh: () -> Unit,
    onViewLines: () -> Unit,
    onStartCounting: () -> Unit,
    onEndCount: () -> Unit,
    onResendExceptions: () -> Unit,
    onReopen: () -> Unit,
    submitBlocked: Boolean,
) {
    val phase = countState.phase
    val finished = phase == CountPhase.SUBMITTED
    val submitting = phase == CountPhase.SUBMITTING

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${countState.counted} of ${countState.total} counted",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${countState.percent}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        CountProgressBar(progress = countState.percent / 100f)

        // Status - only what needs saying.
        when {
            submitting -> ProgressRow(countState.error ?: "Submitting counts… (see Activity)", null)
            phase == CountPhase.POSTED -> Text(
                text = "Posted in D365FO. This journal is closed and can't be reopened.",
                style = MaterialTheme.typography.bodyMedium,
                color = WarehouseStatusColors.text3,
            )
            finished -> {
                Text(
                    text = "Counts submitted to D365FO.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WarehouseStatusColors.green,
                )
                countState.error?.let {
                    Text(
                        text = if (countState.failedExceptions > 0) it else "Some lines were not accepted: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarehouseStatusColors.amber,
                    )
                }
                countState.passNote?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (countState.thirdCountNeeded) WarehouseStatusColors.amber else WarehouseStatusColors.text3,
                    )
                }
                if (countState.failedExceptions > 0) {
                    OutlinedButton(
                        onClick = onResendExceptions,
                        modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) { Text("Send unexpected items again") }
                }
            }
            phase == CountPhase.FAILED -> countState.error?.let { ErrorBanner(message = it) }
            else -> Unit
        }

        // "Get data again" in flight or failed (the button itself is in the card's menu).
        val refreshing = refreshStatus != null && refreshStatus !is ExportJobStatus.Failed
        when {
            refreshing -> DownloadSection(status = refreshStatus, onGetData = onRefresh)
            refreshStatus is ExportJobStatus.Failed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ErrorBanner(message = refreshStatus.reason)
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
                ) { Text("Try again") }
            }
            refreshNote != null && !submitting && !finished -> Text(
                text = refreshNote,
                style = MaterialTheme.typography.bodySmall,
                color = WarehouseStatusColors.green,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onViewLines,
                modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
            ) { Text("View lines") }

            when {
                submitting || phase == CountPhase.POSTED -> Unit
                finished -> Button(
                    onClick = onReopen,
                    modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                ) { Text("Reopen counting") }
                else -> Button(
                    onClick = onStartCounting,
                    modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                ) { Text(if (phase == CountPhase.READY) "Start counting" else "Continue counting") }
            }
        }

        // End count only exists while a count is under way (or a submission needs another go).
        if (phase == CountPhase.COUNTING || phase == CountPhase.FAILED) {
            OutlinedButton(
                onClick = onEndCount,
                enabled = !submitBlocked,
                modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
            ) { Text("End count") }
        }
    }
}

@Composable
private fun EndCountDialog(
    countState: JournalCountState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("End count?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${countState.percent}% counted. ${countState.withQuantity} of ${countState.total} lines have a counted quantity.")
                if (countState.markedOnly > 0) {
                    Text(
                        "${countState.markedOnly} are marked counted without a quantity - their " +
                            "on-hand quantity is sent as the count, so there is no adjustment.",
                    )
                }
                if (countState.notCounted > 0) {
                    Text(
                        "${countState.notCounted} lines are not counted at all and will be left as " +
                            "they are.",
                        color = WarehouseStatusColors.amber,
                    )
                }
                if (countState.emptyLocations > 0) {
                    Text(
                        "${countState.emptyLocations} location${if (countState.emptyLocations == 1) "" else "s"} " +
                            "confirmed empty.",
                    )
                }
                if (countState.exceptions > 0) {
                    Text(
                        "${countState.exceptions} item${if (countState.exceptions == 1) "" else "s"} recorded as " +
                            "not on this journal - sent with the counts for a planner to resolve.",
                    )
                }
                Text(
                    if (countState.counted > 0) "Only the counted lines are sent. Submit now?"
                    else "Nothing has been counted yet - there is nothing to submit.",
                )
            }
        },
        confirmButton = {
            ConfirmButton(onClick = onConfirm, enabled = countState.counted > 0) { Text("Submit counts") }
        },
        dismissButton = {
            CancelOutlinedButton(onClick = onDismiss) { Text("Keep counting") }
        },
    )
}

/**
 * A card with no data yet: a "Get data" button when nothing's in flight, live progress while it
 * is, an error with Try again on failure. [ExportJobStatus.Running.percent] is only sometimes
 * known - shown as a determinate bar when it is, an indeterminate one when it isn't, never a
 * fabricated percentage either way. The run itself continues in the background and is also
 * listed on the Activity page.
 */
@Composable
private fun DownloadSection(
    status: ExportJobStatus?,
    onGetData: () -> Unit,
) {
    when (status) {
        null -> Button(
            onClick = onGetData,
            modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
        ) { Text("Get data") }

        is ExportJobStatus.Enqueued -> ProgressRow(label = "Requesting export…", percent = null)

        is ExportJobStatus.Running -> ProgressRow(
            label = status.percent?.let { "Fetching… $it%" } ?: "Fetching…",
            percent = status.percent,
        )

        // Ready without lines in Room yet: the download/import is in progress. Fleeting - the
        // card switches to its counting controls the moment the import lands.
        is ExportJobStatus.Ready -> ProgressRow(label = "Importing lines…", percent = null)

        is ExportJobStatus.Failed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ErrorBanner(message = status.reason)
            Button(
                onClick = onGetData,
                modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
            ) { Text("Try again") }
        }
    }
}

@Composable
private fun ProgressRow(label: String, percent: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (percent != null) {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Who holds this journal (or which of its ranges) - this device, someone else, or nobody. */
@Composable
private fun ClaimLine(journal: CountingJournalSummary, myClaim: CountClaimEntity?) {
    val text = when {
        myClaim != null && !myClaim.lost -> buildString {
            append("Claimed by you")
            myClaim.rangeId?.let { append(" · range $it") }
            myClaim.expiresAt?.let { append(" · until ${Formatters.clockTime(it)}") }
        }
        journal.claims.isNotEmpty() -> journal.claims.joinToString("\n") { claim ->
            val range = listOfNotNull(claim.locationFrom, claim.locationTo).joinToString(" - ")
            "Claimed by ${claim.claimedBy}" + if (range.isNotBlank()) " ($range)" else ""
        }
        journal.ranges.isNotEmpty() -> "${journal.ranges.size} ranges to claim"
        else -> return
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.text3)
}

/** A wall-to-wall journal split by the planner: pick one range to claim and count. */
@Composable
private fun RangePickerDialog(
    journal: CountingJournalSummary,
    onPick: (CountRange) -> Unit,
    onDismiss: () -> Unit,
) {
    val taken = journal.claims.mapNotNull { claim -> claim.rangeId?.let { it to claim.claimedBy } }.toMap()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a range") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(journal.ranges, key = { it.rangeId }) { range ->
                    val holder = taken[range.rangeId]
                    TextButton(
                        onClick = { onPick(range) },
                        enabled = holder == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(range.label, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = holder?.let { "Claimed by $it" } ?: "${range.lineCount} lines",
                                style = MaterialTheme.typography.bodySmall,
                                color = WarehouseStatusColors.text3,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { CancelOutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Recount / second pass / double-blind - what kind of count this is, from the server. */
@Composable
private fun PassBadges(journal: CountingJournalSummary) {
    val badges = listOfNotNull(
        originLabel(journal.originType)?.takeIf { journal.recountOf == null || journal.originType != "RECOUNT" },
        journal.recountOf?.let { "Recount of $it" },
        "Pass ${journal.passNumber}".takeIf { journal.passNumber > 1 && journal.recountOf == null },
        "Double-blind".takeIf { journal.doubleBlind },
        // A control group repeats the same sample on a schedule; the analysis is server-side.
        (journal.repeatInfo ?: "Repeats").takeIf { journal.originType == "CONTROL_GROUP" },
    )
    val blind = journal.passNumber > 1 || journal.doubleBlind || journal.recountOf != null
    if (badges.isNotEmpty()) {
        Text(
            text = badges.joinToString(" · ") + if (blind) " - counted blind" else "",
            style = MaterialTheme.typography.labelMedium,
            color = if (journal.originType == "TRIGGERED") MaterialTheme.colorScheme.error else WarehouseStatusColors.amber,
        )
    }
    listOfNotNull(journal.abcClass?.let { "Class $it" }, journal.toleranceInfo).takeIf { it.isNotEmpty() }?.let {
        Text(text = it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.text3)
    }
}
