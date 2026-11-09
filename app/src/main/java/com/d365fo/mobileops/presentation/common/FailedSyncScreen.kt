package com.d365fo.mobileops.presentation.common

import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.d365fo.mobileops.presentation.common.components.SquareTextButton as TextButton
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.R
import com.d365fo.mobileops.data.local.entity.FailedSyncEntity
import com.d365fo.mobileops.data.remote.dto.PostLineDto
import com.d365fo.mobileops.data.repository.CountingActivity
import com.d365fo.mobileops.data.repository.CountingDownloadTracker
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.sync.PostQueueEntry
import com.d365fo.mobileops.data.sync.PostingDispatcher
import com.d365fo.mobileops.data.sync.SyncQueueRepository
import com.d365fo.mobileops.data.sync.SyncScheduler
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.util.Formatters
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class FailedSyncViewModel @Inject constructor(
    private val syncQueue: SyncQueueRepository,
    private val scheduler: SyncScheduler,
    private val postingDispatcher: PostingDispatcher,
    private val companyProvider: CompanyProvider,
    private val stagedLines: com.d365fo.mobileops.data.repository.StagedLineRepository,
    private val countingDownloads: CountingDownloadTracker,
    private val followUp: com.d365fo.mobileops.data.sync.PostingFollowUp,
) : ViewModel() {

    /** Counting-journal downloads and submissions, newest first - see [CountingDownloadTracker]. */
    val countingActivities: StateFlow<List<CountingActivity>> = countingDownloads.activities

    fun dismissActivity(key: String) = countingDownloads.dismiss(key)

    val failures: StateFlow<List<FailedSyncEntity>> = syncQueue.observeFailures()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Captured work still on this device, not yet handed to D365FO - offline, or between tries. */
    val waiting: StateFlow<List<com.d365fo.mobileops.data.local.entity.SyncQueueEntity>> = syncQueue.observeWaiting()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * D365FO's own record of what it has received - see [PostQueueEntry]. Distinct from
     * [failures], which is this device's own local queue: a row here can sit at "Pending" long
     * after the local queue entry that created it is gone, waiting for [processPending].
     */
    private val _queueStatus = MutableStateFlow<List<PostQueueEntry>>(emptyList())
    val queueStatus: StateFlow<List<PostQueueEntry>> = _queueStatus.asStateFlow()

    private val _processing = MutableStateFlow(false)
    val processing: StateFlow<Boolean> = _processing.asStateFlow()

    private val _queueError = MutableStateFlow<String?>(null)
    val queueError: StateFlow<String?> = _queueError.asStateFlow()

    /**
     * True from the moment a fetch starts until it resolves - including the very first one.
     *
     * [queueStatus] starts as an empty list, same shape as "checked, and there genuinely is
     * nothing pending" - without this, the screen shows that confident empty state for however
     * long the network round trip takes, which reads as "nothing was submitted" for a receipt
     * that was in fact just captured. Confirmed live: a receipt posted seconds earlier read as
     * not-yet-submitted on this screen because the fetch just hadn't come back yet.
     */
    private val _queueLoading = MutableStateFlow(true)
    val queueLoading: StateFlow<Boolean> = _queueLoading.asStateFlow()

    /** Called once when the screen opens, and again after [processPending]. */
    fun loadQueueStatus() {
        val dataAreaId = companyProvider() ?: return
        viewModelScope.launch {
            _queueLoading.value = true
            when (val result = postingDispatcher.getQueueStatus(dataAreaId)) {
                is NetworkResult.Success -> {
                    _queueStatus.value = result.data
                    _queueError.value = null
                    clearSettledStagedLines(result.data)
                    // A posting refused since it was sent belongs in Failed postings right now.
                    followUp.check()
                }
                is NetworkResult.Error -> _queueError.value = result.failure.message
                NetworkResult.Loading -> Unit
            }
            _queueLoading.value = false
        }
    }

    /**
     * Frees an order's [com.d365fo.mobileops.data.repository.StagedLineRepository] hold as soon
     * as D365FO's own queue confirms a row for it exists at all - Pending, Registered or Failed.
     *
     * Not "no longer Pending": AOS nets Pending *and* Registered rows itself now
     * (HSMobileOpsReceivingService::stagedQtyByLine - registration never posts a packing slip, so
     * RemainPurchPhysical never reflects it any other way). The moment a row is visible here it is
     * already visible to that query, so holding the local copy past that point only double-counts
     * it on top of the server's own netting. See
     * [ReceivingFlowViewModel.sessionRemaining][com.d365fo.mobileops.presentation.modules.stepflow.ReceivingFlowViewModel]
     * for why the hold exists in the first place - it only needs to cover the gap before a row has
     * synced at all. Grouped by sourceId (the order number - the one stable id every row for an
     * order shares) rather than tracking individual queue rows, since that is all the local ledger
     * is keyed on too.
     */
    private fun clearSettledStagedLines(rows: List<PostQueueEntry>) {
        val settledOrderIds = rows.map { it.sourceId }.toSet()
        if (settledOrderIds.isEmpty()) return

        viewModelScope.launch {
            val byOrder = rows.groupBy { it.sourceId }
            settledOrderIds.forEach { orderId ->
                val flowType = flowTypeFor(byOrder[orderId]?.firstOrNull()?.documentType)
                if (flowType != null) stagedLines.clearForOrder(flowType, orderId)
            }
        }
    }

    private fun flowTypeFor(documentType: String?): com.d365fo.mobileops.data.local.entity.FlowType? =
        when (documentType) {
            "Registration" -> com.d365fo.mobileops.data.local.entity.FlowType.RECEIVING
            "TransferShipment" -> com.d365fo.mobileops.data.local.entity.FlowType.TRANSFER_SHIP
            "TransferReceipt" -> com.d365fo.mobileops.data.local.entity.FlowType.TRANSFER_RECEIVE
            else -> null
        }

    /**
     * Works every Pending row on D365FO's side - see the class doc on
     * `aos/HSMobileOps/AxTable/HSMobileOpsPostQueue.xml` for why this is a deliberate tap rather
     * than something a timer does on its own.
     */
    fun processPending() {
        val dataAreaId = companyProvider() ?: return
        if (_processing.value) return

        viewModelScope.launch {
            _processing.value = true
            when (val result = postingDispatcher.processPending(dataAreaId)) {
                is NetworkResult.Success -> _queueError.value = null
                is NetworkResult.Error -> _queueError.value = result.failure.message
                NetworkResult.Loading -> Unit
            }
            _processing.value = false
            // Refreshes the whole list, not just the rows this run touched - a second device or
            // a second worker may have queued more since the screen last loaded.
            loadQueueStatus()
        }
    }

    /**
     * Puts a rejected posting back on the queue.
     *
     * Whatever caused the rejection is expected to have been fixed on the D365FO side — a
     * reopened period, an unblocked item. Nothing on the device changed, so the payload goes
     * again as it is, and D365FO reworks its own staged row - see SyncQueueRepository.isResubmit.
     */
    fun resubmit(failureId: String) {
        viewModelScope.launch {
            if (syncQueue.resubmit(failureId)) {
                scheduler.requestImmediatePush()
            }
        }
    }

    fun acknowledge(failureId: String) {
        viewModelScope.launch { syncQueue.acknowledgeFailure(failureId) }
    }
}

/**
 * Supervisor review queue.
 *
 * Deliberately reachable in one tap from the status bar on every screen: a posting that
 * D365FO refused is invisible work, and the longer it sits the harder it is to reconcile
 * against what is physically on the racking.
 *
 * Two lists, not one. [FailedSyncViewModel.failures] is this *device's* own outbound queue -
 * whether it managed to hand a captured receipt to D365FO at all. [FailedSyncViewModel.queueStatus]
 * is D365FO's own record of what it did with what it received - Pending until D365FO's batch
 * job works it (every few minutes) or someone triggers [FailedSyncViewModel.processPending],
 * then Registered or Failed. A receipt can clear the first list within a second of being
 * captured and still sit at Pending in the second for a few minutes.
 */
@Composable
fun FailedSyncScreen(
    onBack: () -> Unit,
    viewModel: FailedSyncViewModel = hiltViewModel(),
) {
    val failures by viewModel.failures.collectAsStateWithLifecycle()
    val waiting by viewModel.waiting.collectAsStateWithLifecycle()
    val activities by viewModel.countingActivities.collectAsStateWithLifecycle()
    val queueStatus by viewModel.queueStatus.collectAsStateWithLifecycle()
    val queueLoading by viewModel.queueLoading.collectAsStateWithLifecycle()
    val processing by viewModel.processing.collectAsStateWithLifecycle()
    val queueError by viewModel.queueError.collectAsStateWithLifecycle()
    val online = com.d365fo.mobileops.presentation.common.components.LocalSyncStatus.current?.fullyOnline != false
    val postingEnabled = com.d365fo.mobileops.presentation.common.components.LocalSyncStatus.current?.postingEnabled != false
    val (acknowledged, needsAction) = failures.partition { it.acknowledged }

    // On opening, and again when the connection comes back - the queue lives in D365FO.
    LaunchedEffect(online) { if (online) viewModel.loadQueueStatus() }
    // Something just sent: what D365FO received has changed.
    LaunchedEffect(waiting.size) { if (online) viewModel.loadQueueStatus() }

    Scaffold(
        topBar = {
            // A bottom tab: the greeting bar like Home, Services and More, no back arrow (20 Oct restyle).
            TopAppBar(
                title = { HomeGreeting() },
                expandedHeight = WarehouseDimens.TopBarHeight,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = WarehouseDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(WarehouseDimens.ItemSpacing),
        ) {
            // First: work captured on this device that D365FO hasn't received yet. Offline this
            // is the answer to "is my receipt safe?" - it is, and here it is.
            if (waiting.isNotEmpty()) {
                item {
                    Text(
                        text = "Waiting to send · ${waiting.size}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                        modifier = Modifier.padding(top = WarehouseDimens.ItemSpacing),
                    )
                }
                items(waiting, key = { it.queueId }) { entry -> WaitingCard(entry, online, postingEnabled) }
            }

            // Needs someone now, so above the history: under the queue it took 15 swipes to reach
            // Resubmit. Acknowledged ones have been seen and stay at the bottom.
            if (needsAction.isNotEmpty()) {
                item {
                    Text(
                        text = "Failed postings · ${needsAction.size}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                        modifier = Modifier.padding(top = WarehouseDimens.ItemSpacing),
                    )
                }
                items(needsAction, key = { it.failureId }) { failure ->
                    FailureCard(
                        failure = failure,
                        onResubmit = { viewModel.resubmit(failure.failureId) },
                        onAcknowledge = { viewModel.acknowledge(failure.failureId) },
                    )
                }
            }

            if (activities.isNotEmpty()) {
                item {
                    Text(
                        text = "Counting",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                        modifier = Modifier.padding(top = WarehouseDimens.ItemSpacing),
                    )
                }
                items(activities, key = { it.key }) { activity ->
                    ActivityCard(activity, onDismiss = { viewModel.dismissActivity(activity.key) })
                }
            }

            item {
                Text(
                    text = "Registration queue",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                    modifier = Modifier.padding(top = WarehouseDimens.ItemSpacing),
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(WarehouseDimens.ItemSpacing),
                ) {
                    Button(
                        onClick = viewModel::processPending,
                        enabled = !processing && online,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) {
                        if (processing) {
                            CircularProgressIndicator(
                                modifier = Modifier.heightIn(max = 20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Process pending")
                        }
                    }
                }
            }
            if (!online) {
                // Not an error: the queue lives in D365FO, so there is nothing to show until it
                // answers. What this device still has to send is listed above and below.
                item {
                    Text(
                        text = "Check again when online.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else queueError?.let { message ->
                item {
                    ErrorBanner(message = message)
                }
            }
            if (queueLoading) {
                // Distinct from the empty state below - see the doc on queueLoading. A receipt
                // captured a moment ago must never read as "not submitted" just because this
                // fetch hasn't come back yet.
                item {
                    Text(
                        text = "Checking registration status…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (online && queueStatus.isEmpty()) {
                item {
                    Text(
                        text = "Nothing has been submitted for registration yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // D365FO's rows have no id of their own, and one document can have two in the same
                // second (a PO created and then confirmed) - the position keeps every key unique.
                itemsIndexed(queueStatus, key = { i, it -> "$i|${it.documentType}|${it.receivedUtc.orEmpty()}|${it.sourceId}" }) { _, entry ->
                    QueueStatusCard(entry)
                }
            }

            if (failures.isEmpty() || acknowledged.isNotEmpty()) {
                item {
                    Text(
                        text = if (failures.isEmpty()) "Failed postings" else "Failed postings · acknowledged",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                        modifier = Modifier.padding(top = WarehouseDimens.ItemSpacing),
                    )
                }
            }
            if (failures.isEmpty()) {
                item {
                    Text(
                        text = "Nothing has failed to post.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(acknowledged, key = { it.failureId }) { failure ->
                    FailureCard(
                        failure = failure,
                        onResubmit = { viewModel.resubmit(failure.failureId) },
                        onAcknowledge = { viewModel.acknowledge(failure.failureId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WaitingCard(entry: com.d365fo.mobileops.data.local.entity.SyncQueueEntity, online: Boolean, postingEnabled: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(WarehouseDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = entry.aggregateId, style = MaterialTheme.typography.titleMedium)
            Text(
                text = operationLabel(entry.operation) + " · saved " +
                    com.d365fo.mobileops.util.Formatters.clockTime(entry.createdAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = when {
                    !postingEnabled -> "Held - posting is off"
                    online -> "Sending to D365FO…"
                    else -> "Saved - sent when online"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (online && postingEnabled) MaterialTheme.colorScheme.onSurfaceVariant else WarehouseStatusColors.amber,
            )
        }
    }
}

private fun operationLabel(operation: com.d365fo.mobileops.data.local.entity.SyncOperation): String = when (operation) {
    com.d365fo.mobileops.data.local.entity.SyncOperation.POST_PRODUCT_RECEIPT -> "PO receipt"
    com.d365fo.mobileops.data.local.entity.SyncOperation.POST_ARRIVAL_JOURNAL -> "Arrival"
    com.d365fo.mobileops.data.local.entity.SyncOperation.TRANSFER_ORDER_SHIP -> "Transfer shipment"
    com.d365fo.mobileops.data.local.entity.SyncOperation.TRANSFER_ORDER_RECEIVE -> "Transfer receipt"
    com.d365fo.mobileops.data.local.entity.SyncOperation.POST_PACKING_SLIP -> "Packing slip"
    com.d365fo.mobileops.data.local.entity.SyncOperation.POST_INVENTORY_JOURNAL -> "Inventory journal"
    com.d365fo.mobileops.data.local.entity.SyncOperation.SET_WORKER_PERMISSION -> "Permission change"
    com.d365fo.mobileops.data.local.entity.SyncOperation.CREATE_PURCHASE_ORDER -> "New purchase order"
    com.d365fo.mobileops.data.local.entity.SyncOperation.CREATE_TRANSFER_ORDER -> "New transfer order"
    com.d365fo.mobileops.data.local.entity.SyncOperation.CONFIRM_PURCHASE_ORDER -> "PO confirmation"
    com.d365fo.mobileops.data.local.entity.SyncOperation.SEND_FEEDBACK -> "Feedback"
    com.d365fo.mobileops.data.local.entity.SyncOperation.CREATE_PURCHASE_RETURN -> "Return to vendor"
}

@Composable
private fun ActivityCard(activity: CountingActivity, onDismiss: () -> Unit) {
    // A run in progress can't move while D365FO can't be reached: say so, rather than show a
    // progress bar that isn't progressing.
    val status = com.d365fo.mobileops.presentation.common.components.LocalSyncStatus.current
    val postingOff = activity.inProgress && status?.postingEnabled == false
    val waiting = activity.inProgress && (status?.fullyOnline == false || postingOff)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(WarehouseDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = activity.journalId, style = MaterialTheme.typography.titleMedium)
            Text(
                text = listOfNotNull(activity.title, activity.description?.takeIf { it.isNotBlank() })
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (activity.failed) {
                ErrorBanner(message = activity.label)
            } else {
                Text(
                    text = when {
                        postingOff -> "Held - posting is off"
                        waiting -> "Waiting for connection"
                        else -> activity.label
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (activity.warning) WarehouseStatusColors.amber else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (waiting) {
                Unit
            } else if (activity.inProgress) {
                val percent = activity.percent
                if (percent != null) {
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun QueueStatusCard(entry: PostQueueEntry) {
    val statusColor = when (entry.status) {
        "Registered" -> MaterialTheme.colorScheme.secondary
        "Failed" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(WarehouseDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = entry.sourceId,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = entry.status,
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor,
                )
            }
            Text(
                text = readableDocumentType(entry.documentType),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Prefers processedUtc (when D365FO actually actioned it) over receivedUtc (when
            // this device's request landed) - the more useful of the two once both exist, since
            // "when did this finish" is what a supervisor scanning this list is really asking.
            // D365FO sends its empty date (1900-01-01) rather than null for a row not processed
            // yet - shown as-is that read "Processed 04:28" on a pending row.
            val processedAt = entry.processedUtc?.let(Formatters::odataToEpochMillis)?.takeIf { it > EMPTY_DATE_CUTOFF }
            val receivedAt = entry.receivedUtc?.let(Formatters::odataToEpochMillis)?.takeIf { it > EMPTY_DATE_CUTOFF }
            (processedAt ?: receivedAt)
                ?.let { epochMs ->
                    Text(
                        text = (if (processedAt != null) "Processed " else "Received ") +
                            Formatters.clockTime(epochMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            parseQueueLines(entry.linesJson).takeIf { it.isNotEmpty() }?.let { queueLines ->
                Text(
                    text = queueLines.joinToString(", ") { "${it.itemId} ×${formatQty(it.quantity)}" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // A big posting's lines are not sent to this list, only how many there were.
            if (parseQueueLines(entry.linesJson).isEmpty() && entry.lineCount > 0) {
                Text(
                    text = "${entry.lineCount} " + (if (entry.lineCount == 1) "line" else "lines"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            entry.grnId?.let { grn ->
                Text(
                    text = "GRN $grn",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
            }
            entry.userName?.takeIf { it.isNotBlank() }?.let { userName ->
                Text(
                    text = "by $userName",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            entry.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                ErrorBanner(message = message)
            }
        }
    }
}

private val queueLinesJson = Json { ignoreUnknownKeys = true }

/**
 * Best-effort only - this is a status list, not the posting path, so a shape it does not
 * recognise just shows nothing extra rather than breaking the whole screen.
 */
private fun parseQueueLines(linesJson: String?): List<PostLineDto> = linesJson
    ?.let { runCatching { queueLinesJson.decodeFromString(ListSerializer(PostLineDto.serializer()), it) }.getOrNull() }
    ?: emptyList()

private fun formatQty(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

@Composable
private fun FailureCard(
    failure: FailedSyncEntity,
    onResubmit: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(WarehouseDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = failure.aggregateId,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = failure.operation.name.replace('_', ' ').lowercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ErrorBanner(message = failure.errorMessage)
            Text(
                text = "${failure.attempts} attempts · failed " +
                    Formatters.clockTime(failure.failedAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(WarehouseDimens.ItemSpacing),
            ) {
                // Acknowledged cards stay listed (Resubmit still works) but must look seen: the
                // button stayed exactly as it was, so a tap changed nothing a worker could see
                // except the tab badge.
                if (failure.acknowledged) {
                    Text(
                        text = "✓ Acknowledged",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .heightIn(min = WarehouseDimens.MinTouchTarget)
                            .wrapContentHeight(Alignment.CenterVertically)
                            .padding(horizontal = 12.dp),
                    )
                } else {
                    TextButton(
                        onClick = onAcknowledge,
                        modifier = Modifier.heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) {
                        Text("Acknowledge")
                    }
                }
                Button(
                    onClick = onResubmit,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = WarehouseDimens.MinTouchTarget),
                ) {
                    Text("Resubmit")
                }
            }
        }
    }
}

/** Anything before 2000 is D365FO's "no date" value, not a real time. */
private const val EMPTY_DATE_CUTOFF = 946_684_800_000L

/** D365FO's codes ("TransferReceipt") as words - shown on every queue card. */
private fun readableDocumentType(code: String): String = when (code) {
    "Registration" -> "PO receipt"
    "TransferReceipt" -> "Transfer receipt"
    "TransferShipment" -> "Transfer shipment"
    else -> code.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase().replaceFirstChar { it.uppercase() }
}
