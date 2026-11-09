package com.d365fo.mobileops.presentation.modules.cyclecounting

import kotlinx.coroutines.launch
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
import androidx.compose.foundation.lazy.rememberLazyListState
import com.d365fo.mobileops.presentation.common.components.SquareTextButton as TextButton
import androidx.compose.material.icons.Icons
import com.d365fo.mobileops.domain.model.kind
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Inventory
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CountingJournalListScreen(
    onBack: () -> Unit,
    onViewLines: (String) -> Unit,
    onStartCounting: (String) -> Unit,
    onReviewVariance: (String) -> Unit = {},
    onReviewDualCount: (String) -> Unit = {},
    /** A free count of the whole store, reviewed by the supervisor (Stock count control center). */
    onStoreCount: () -> Unit = {},
    onAccuracy: () -> Unit = {},
    /** The Inventory count sub-menu this list is for; null lists every kind (search). */
    kind: com.d365fo.mobileops.domain.model.CountKind? = null,
    viewModel: CountingJournalListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmEndFor by remember { mutableStateOf<String?>(null) }
    var confirmReopenFor by remember { mutableStateOf<String?>(null) }

    // Export as CSV (feedback, 12 Oct): the worker picks where - Downloads, a shared drive - in
    // Android's own save dialog, and Excel opens the file.
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var exportFor by remember { mutableStateOf<String?>(null) }
    val saveCsv = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val journalId = exportFor ?: return@rememberLauncherForActivityResult
        exportFor = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val csv = viewModel.csvFor(journalId)
            val written = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        // A byte-order mark, so Excel reads item names in UTF-8.
                        out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                        out.write(csv.toByteArray(Charsets.UTF_8))
                    } != null
                }.getOrDefault(false)
            }
            viewModel.exported(if (written) "$journalId saved as CSV." else "$journalId could not be saved.")
        }
    }

    confirmReopenFor?.let { journalId ->
        AlertDialog(
            onDismissRequest = { confirmReopenFor = null },
            title = { Text("Reopen counting?") },
            text = {
                Text(
                    "Counts stay. Fix or add, then End count again.",
                )
            },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = { confirmReopenFor = null }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
                    confirm = { com.d365fo.mobileops.presentation.common.components.ConfirmButton(onClick = {
                        confirmReopenFor = null
                        viewModel.reopenCounting(journalId)
                    }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Reopen") } },
                )
            },
        )
    }

    state.rangePickerFor?.let { journalId ->
        state.journals.firstOrNull { it.journalId == journalId }?.let { journal ->
            RangePickerDialog(
                journal = journal,
                currentUser = viewModel.currentUserName,
                onPick = { viewModel.pickRange(journalId, it) },
                onDismiss = viewModel::cancelRangePicker,
            )
        }
    }

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
                title = {
                    Text(
                        when (kind) {
                            com.d365fo.mobileops.domain.model.CountKind.CYCLE -> "Cycle count"
                            com.d365fo.mobileops.domain.model.CountKind.WALL_TO_WALL -> "Wall-to-wall count"
                            null -> "Inventory count"
                        },
                    )
                },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                // Store count and Count accuracy in the ⋮ menu: four icons beside the title broke
                // "Inventory count" mid-word (20 Oct restyle).
                menuItems = { close ->
                    // Every counter may count the store; the supervisor reviews it in D365FO.
                    // A sub-menu leaves both to the Inventory count page.
                    if (kind == null) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Store count") },
                        leadingIcon = { Icon(Icons.Default.Inventory, contentDescription = null) },
                        onClick = { close(); onStoreCount() },
                    )
                    if (state.canViewAccuracy) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Count accuracy") },
                            leadingIcon = { Icon(Icons.Default.Insights, contentDescription = null) },
                            onClick = { close(); onAccuracy() },
                        )
                    }
                    }
                },
            )
        },
    ) { padding ->
        // This device's journals draw first and D365FO's open ones arrive above them. A list keeps
        // its place when rows land above it, which would leave the worker looking at closed
        // journals with the ones to count out of sight.
        val listState = rememberLazyListState()
        val firstJournal = state.journals.firstOrNull()?.journalId
        var shownFirst by rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(firstJournal) {
            if (firstJournal != null && firstJournal != shownFirst) {
                listState.scrollToItem(0)
                shownFirst = firstJournal
            }
        }
        val blindCountNotice by viewModel.blindCountNotice.collectAsStateWithLifecycle()
        // 0 Cycle (or "To count" in a sub-menu), 1 Recount, 2 Wall-to-wall, 3 Done. A sub-menu
        // lists its own kind only: cycle has To count / Recount / Done, wall-to-wall To count / Done.
        val tabs = when (kind) {
            null -> listOf(0, 1, 2, 3)
            com.d365fo.mobileops.domain.model.CountKind.CYCLE -> listOf(0, 1, 3)
            else -> listOf(0, 3)
        }
        var tab by rememberSaveable { mutableStateOf(0) }
        val ofKind = if (kind == null) state.journals else state.journals.filter { it.kind == kind }
        val (doneAny, openJournals) = ofKind.partition { it.journalId in state.doneIds }
        // Open work is oldest first; finished work reads newest first.
        val doneJournals = doneAny.sortedByDescending { it.journalDate ?: Long.MIN_VALUE }
        val tabJournals = if (tab == 3) doneJournals else openJournals.filter {
            when (tab) {
                1 -> it.onRecountTab
                2 -> it.onWallToWallTab
                else -> kind != null && kind != com.d365fo.mobileops.domain.model.CountKind.CYCLE || it.onCycleTab
            }
        }
        val recountLineTotal = openJournals.filter { !it.isWallToWall }.sumOf { it.recountLines }
        val wallToWallCount = openJournals.count { it.isWallToWall }
        blindCountNotice?.let { notice ->
            AlertDialog(
                onDismissRequest = viewModel::dismissBlindCountNotice,
                text = { Text(notice) },
                confirmButton = {
                    com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                        dismiss = null,
                        confirm = {
                            com.d365fo.mobileops.presentation.common.components.ConfirmButton(
                                onClick = viewModel::dismissBlindCountNotice,
                                modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                            ) { Text("OK") }
                        },
                    )
                },
            )
        }
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Tabs under the bar, the same as Activity's (Harbour).
            val shown = listOf(0) + listOfNotNull(1.takeIf { 1 in tabs }, 2.takeIf { 2 in tabs }) + 3
            com.d365fo.mobileops.presentation.common.components.SegmentTabs(
                labels = shown.map {
                    when (it) {
                        0 -> if (kind == null) "Cycle" else "To count"
                        1 -> "Recount"
                        2 -> "Wall-to-wall"
                        else -> "Done"
                    }
                },
                selected = shown.indexOf(tab).coerceAtLeast(0),
                onSelect = { tab = shown[it] },
                badges = buildMap {
                    shown.forEachIndexed { i, t ->
                        val n = when (t) {
                            1 -> recountLineTotal
                            2 -> wallToWallCount
                            3 -> doneJournals.size
                            else -> 0
                        }
                        if (n > 0 && i > 0) put(i, n)
                    }
                },
            )
        Box(modifier = Modifier.fillMaxSize()) {
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

                tabJournals.isEmpty() -> {
                    Text(
                        text = when {
                            tab == 3 -> "No finished counts yet."
                            tab == 1 -> "Nothing to recount."
                            tab == 2 || kind == com.d365fo.mobileops.domain.model.CountKind.WALL_TO_WALL -> "No wall-to-wall count in progress."
                            else -> state.warehouseId?.let { "No open counting journals in $it." } ?: "No open counting journals."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(WarehouseDimens.ScreenPadding),
                    )
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = WarehouseDimens.ScreenPadding),
                        contentPadding = PaddingValues(vertical = WarehouseDimens.ItemSpacing),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.externalCounter) {
                            item(key = "external") {
                                Text(
                                    text = "External counter" + (state.counterTeam?.let { " · team $it" } ?: ""),
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
                        items(tabJournals, key = { it.journalId }) { journal ->
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
                                onReviewVariance = {
                                    if (journal.doubleBlind) onReviewDualCount(journal.journalId) else onReviewVariance(journal.journalId)
                                }.takeIf { state.canReviewVariance && journal.doneNote == null },
                                onReopen = { confirmReopenFor = journal.journalId },
                                onExportCsv = {
                                    exportFor = journal.journalId
                                    saveCsv.launch("${journal.journalId}.csv")
                                }.takeIf { state.countStates[journal.journalId] != null },
                            )
                        }
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
    onExportCsv: (() -> Unit)? = null,
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
        // Compact (feedback, 21 Oct): the ring sits left of the journal number and Get data at
        // its right, instead of each taking a full-width row.
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (countState != null) {
                    com.d365fo.mobileops.presentation.common.components.CountProgressRing(
                        progress = countState.percent / 100f,
                        percent = countState.percent,
                        size = 44.dp,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        // Breaks after a hyphen, never inside the number.
                        text = with(com.d365fo.mobileops.presentation.common.components.Breakable) { journal.journalId.code() },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        // The document in the accent, as every Harbour row.
                        color = WarehouseStatusColors.accent,
                    )
                    // Description, size and date on one quiet line rather than three.
                    Text(
                        // Minimal (feedback, 21 Oct: "too much info on each card"): what it is
                        // and how far along - no date, no claim timer, no class or tolerance.
                        text = listOfNotNull(
                            journal.description?.takeIf { it.isNotBlank() },
                            countState?.let { "${it.counted} of ${it.total} counted" }
                                ?: if (journal.doneNote != null) null else when {
                                    journal.lineCount == 0 && onReviewVariance != null -> "Nothing left to count"
                                    journal.lineCount == 1 -> "1 line"
                                    else -> "${journal.lineCount} lines"
                                },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = WarehouseStatusColors.text3,
                    )
                }
                // A supervisor sees a dual journal whose lines are all settled, to review it: there
                // is nothing to fetch, and Review variance is in the menu.
                if (countState == null && exportStatus == null && journal.doneNote == null &&
                    !(journal.lineCount == 0 && onReviewVariance != null)
                ) {
                    androidx.compose.material3.FilledTonalButton(
                        onClick = onGetData,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                            containerColor = WarehouseStatusColors.accentDim,
                            contentColor = WarehouseStatusColors.accent,
                        ),
                        modifier = Modifier.heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) { Text("Get data", fontWeight = FontWeight.Bold) }
                }
                if (canRefresh || canReopen || onReviewVariance != null || onExportCsv != null) {
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
                            if (onExportCsv != null) {
                                DropdownMenuItem(
                                    text = { Text("Export as CSV") },
                                    onClick = { menuOpen = false; onExportCsv() },
                                )
                            }
                        }
                    }
                }
            }

            journal.doneNote?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium, color = WarehouseStatusColors.green)
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
                    // Nothing downloaded here yet means no counts to keep (30 Oct).
                    message = (myClaim.lostReason ?: "Claim lost.") +
                        if (countState != null) " Counts are kept - claim again to send." else " Claim again to count.",
                )
                com.d365fo.mobileops.presentation.common.components.GradientButton(
                    onClick = onReclaim,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
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
            } else if (exportStatus != null) {
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
        // Status - only what needs saying.
        when {
            submitting && com.d365fo.mobileops.presentation.common.components.LocalSyncStatus.current?.postingEnabled == false ->
                Text(
                    text = "Held here - posting is off",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarehouseStatusColors.text3,
                )
            submitting && com.d365fo.mobileops.presentation.common.components.LocalSyncStatus.current?.fullyOnline == false ->
                Text(
                    text = "Waiting for connection",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarehouseStatusColors.text3,
                )
            submitting -> ProgressRow(countState.error ?: "Sending…", null)
            phase == CountPhase.POSTED -> Text(
                text = "Posted - closed",
                style = MaterialTheme.typography.bodySmall,
                color = WarehouseStatusColors.text3,
            )
            finished -> {
                // At a store that reviews journal counts, End count sends them to the supervisor,
                // not into the journal - saying "submitted" there was wrong (13 Oct).
                val sentForReview = countState.passNote?.let { it.startsWith(SENT_FOR_REVIEW_PREFIX) || it.startsWith(OLD_SENT_FOR_REVIEW_PREFIX) } == true
                Text(
                    text = if (sentForReview) "Sent for review" else "Submitted",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarehouseStatusColors.green,
                )
                countState.error?.let {
                    val n = countState.failedExceptions
                    Text(
                        text = if (n > 0) "$n unexpected item${if (n == 1) "" else "s"} not sent" else "Some lines not accepted",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarehouseStatusColors.amber,
                    )
                }
                // Only a third count needs saying here; the rest of the pass note is supervisor detail.
                if (countState.thirdCountNeeded) {
                    Text(
                        text = "Third count needed",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarehouseStatusColors.amber,
                    )
                }
                if (countState.failedExceptions > 0) {
                    OutlinedButton(
                        onClick = onResendExceptions,
                        modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) { Text("Send again") }
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

        // The Harbour pair: outlined View lines beside the gradient main action.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            com.d365fo.mobileops.presentation.common.components.DockOutlinedButton(
                onClick = onViewLines,
                text = "View lines",
                modifier = Modifier.weight(1f),
            )
            when {
                submitting || phase == CountPhase.POSTED -> Unit
                finished -> com.d365fo.mobileops.presentation.common.components.GradientButton(
                    onClick = onReopen,
                    modifier = Modifier.weight(1f),
                ) { Text("Reopen") }
                else -> com.d365fo.mobileops.presentation.common.components.GradientButton(
                    onClick = onStartCounting,
                    modifier = Modifier.weight(1f),
                ) { Text(if (phase == CountPhase.READY) "Start counting" else "Continue counting") }
            }
        }

        // End count only exists while a count is under way (or a submission needs another go).
        if (phase == CountPhase.COUNTING || phase == CountPhase.FAILED) {
            com.d365fo.mobileops.presentation.common.components.DockOutlinedButton(
                onClick = onEndCount,
                enabled = !submitBlocked,
                text = "End count",
                modifier = Modifier.fillMaxWidth(),
            )
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
                Text("${countState.withQuantity} of ${countState.total} lines counted.")
                if (countState.markedOnly > 0) {
                    Text(
                        "${countState.markedOnly} marked OK (on-hand kept).",
                    )
                }
                if (countState.notCounted > 0) {
                    Text(
                        "${countState.notCounted} not counted - left as they are.",
                        color = WarehouseStatusColors.amber,
                    )
                }
                if (countState.emptyLocations > 0) {
                    Text(
                        "${countState.emptyLocations} empty location${if (countState.emptyLocations == 1) "" else "s"}.",
                    )
                }
                if (countState.exceptions > 0) {
                    Text(
                        "${countState.exceptions} not on the journal - added as new lines.",
                    )
                }
                Text(
                    when {
                        countState.counted > 0 -> "Send the counts?"
                        countState.exceptions > 0 -> "Send the extra items?"
                        else -> "Nothing counted yet."
                    },
                )
            }
        },
        confirmButton = {
            com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Keep counting") } },
                confirm = { com.d365fo.mobileops.presentation.common.components.ConfirmButton(onClick = onConfirm, enabled = countState.counted > 0 || countState.exceptions > 0, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Submit counts") } },
            )
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
        // A tonal button like the dashboard's actions - a solid one on every card was heavy.
        null -> androidx.compose.material3.FilledTonalButton(
            onClick = onGetData,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                containerColor = WarehouseStatusColors.accentDim,
                contentColor = WarehouseStatusColors.accent,
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
        ) { Text("Get data", fontWeight = FontWeight.Bold) }

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
        // This device's own claim needs no line - it's the normal case.
        myClaim != null && !myClaim.lost -> return
        journal.claims.isNotEmpty() -> journal.claims.joinToString("\n") { claim ->
            // One location (a wall-to-wall task) once, not "A - A".
            val range = listOfNotNull(claim.locationFrom, claim.locationTo).filter { it.isNotBlank() }.distinct().joinToString(" - ")
            "Claimed by ${claim.claimedBy}" + if (range.isNotBlank()) " ($range)" else ""
        } + if (journal.isWallToWall && journal.ranges.isNotEmpty()) "\n${locationsToCount(journal.ranges.size)}" else ""
        journal.ranges.isNotEmpty() -> if (journal.isWallToWall) locationsToCount(journal.ranges.size) else "${journal.ranges.size} ranges to claim"
        else -> return
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.text3)
}

private fun locationsToCount(n: Int) = if (n == 1) "1 location to count" else "$n locations to count"

/** A wall-to-wall journal split by the planner: pick one range to claim and count. */
@Composable
private fun RangePickerDialog(
    journal: CountingJournalSummary,
    currentUser: String?,
    onPick: (CountRange) -> Unit,
    onDismiss: () -> Unit,
) {
    val taken = journal.claims.mapNotNull { claim -> claim.rangeId?.let { it to claim.claimedBy } }.toMap()
    // The server names a holder "Name (user id)": a range of the counter's own is theirs to go
    // back to (seen 8 Nov: after a refused End count, their own location could not be picked).
    fun isMine(holder: String) = currentUser != null &&
        holder.trim().endsWith("($currentUser)", ignoreCase = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (journal.isWallToWall) "Choose a location" else "Choose a range") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(journal.ranges, key = { it.rangeId }) { range ->
                    val holder = taken[range.rangeId]
                    TextButton(
                        onClick = { onPick(range) },
                        enabled = holder == null || isMine(holder),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(range.label, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = when {
                                    holder == null -> "${range.lineCount} lines"
                                    isMine(holder) -> "Yours · ${range.lineCount} lines"
                                    else -> "Claimed by $holder"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = WarehouseStatusColors.text3,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                dismiss = null,
                confirm = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
            )
        },
    )
}

/** Recount / second pass / double-blind - what kind of count this is, from the server. */
@Composable
private fun PassBadges(journal: CountingJournalSummary) {
    val badges = listOfNotNull(
        originLabel(journal.originType)
            ?.takeIf { journal.recountOf == null || journal.originType != "RECOUNT" }
            // Not twice: a spot count's description already starts "Spot count · ...".
            ?.takeIf { label -> journal.description?.startsWith(label, ignoreCase = true) != true },
        journal.recountOf?.let { "Recount of $it" },
        "Pass ${journal.passNumber}".takeIf { journal.passNumber > 1 && journal.recountOf == null },
        // A wall-to-wall line needs a second count only by its counting group: not "double-blind" as such.
        "Double-blind".takeIf { journal.doubleBlind && !journal.isWallToWall },
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
    // Class and tolerance are supervisor detail - left off the counter's card (21 Oct).
}

/** How [com.d365fo.mobileops.data.counting.CountSubmitRunner] starts the note for counts sent for review. */
internal const val SENT_FOR_REVIEW_PREFIX = "Sent for review"

/** The wording before 21 Oct, still on journals submitted then. */
internal const val OLD_SENT_FOR_REVIEW_PREFIX = "Sent to the supervisor for review"
