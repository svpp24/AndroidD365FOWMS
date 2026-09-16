package com.d365fo.mobileops.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.d365fo.mobileops.presentation.common.components.SquareTextButton as TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.R
import com.d365fo.mobileops.data.local.entity.FailedSyncEntity
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.sync.PostQueueEntry
import com.d365fo.mobileops.data.sync.PostingDispatcher
import com.d365fo.mobileops.data.sync.SyncQueueRepository
import com.d365fo.mobileops.data.sync.SyncScheduler
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.util.Formatters
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FailedSyncViewModel @Inject constructor(
    private val syncQueue: SyncQueueRepository,
    private val scheduler: SyncScheduler,
    private val postingDispatcher: PostingDispatcher,
    private val companyProvider: CompanyProvider,
) : ViewModel() {

    val failures: StateFlow<List<FailedSyncEntity>> = syncQueue.observeFailures()
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
                }
                is NetworkResult.Error -> _queueError.value = result.failure.message
                NetworkResult.Loading -> Unit
            }
            _queueLoading.value = false
        }
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
     * reopened period, an unblocked item. Nothing on the device changed, so the payload is
     * replayed as-is under a fresh idempotency key.
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
 * is D365FO's own record of what it did with what it received - Pending until someone triggers
 * [FailedSyncViewModel.processPending], then Registered or Failed. A receipt can clear the first
 * list within a second of being captured and still sit at Pending in the second for a shift.
 */
@Composable
fun FailedSyncScreen(
    onBack: () -> Unit,
    viewModel: FailedSyncViewModel = hiltViewModel(),
) {
    val failures by viewModel.failures.collectAsStateWithLifecycle()
    val queueStatus by viewModel.queueStatus.collectAsStateWithLifecycle()
    val queueLoading by viewModel.queueLoading.collectAsStateWithLifecycle()
    val processing by viewModel.processing.collectAsStateWithLifecycle()
    val queueError by viewModel.queueError.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadQueueStatus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activity") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
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
            item {
                Text(
                    text = "Registration queue",
                    style = MaterialTheme.typography.titleMedium,
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
                        enabled = !processing,
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
            queueError?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
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
            } else if (queueStatus.isEmpty()) {
                item {
                    Text(
                        text = "Nothing has been submitted for registration yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(queueStatus, key = { it.receivedUtc.orEmpty() + it.sourceId }) { entry ->
                    QueueStatusCard(entry)
                }
            }

            item {
                Text(
                    text = "Failed postings",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = WarehouseDimens.ItemSpacing),
                )
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
                items(failures, key = { it.failureId }) { failure ->
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
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = entry.status,
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor,
                )
            }
            Text(
                text = entry.documentType,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

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
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = failure.operation.name.replace('_', ' ').lowercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = failure.errorMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
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
                TextButton(
                    onClick = onAcknowledge,
                    modifier = Modifier.heightIn(min = WarehouseDimens.MinTouchTarget),
                ) {
                    Text("Acknowledge")
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
