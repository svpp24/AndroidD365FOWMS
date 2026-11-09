package com.d365fo.mobileops.presentation.modules.cyclecounting.supervisor

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.counting.supervisor.LiveSupervisorCountRepository
import com.d365fo.mobileops.domain.counting.supervisor.DualCountLine
import com.d365fo.mobileops.domain.counting.supervisor.DualCountReview
import com.d365fo.mobileops.domain.counting.supervisor.SupervisorCountRepository
import com.d365fo.mobileops.domain.counting.supervisor.SupervisorResult
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar
import com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton
import com.d365fo.mobileops.presentation.common.components.ConfirmButton
import com.d365fo.mobileops.presentation.common.components.DialogButtonRow
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton as OutlinedButton
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

data class DualCountReviewState(
    val journalId: String = "",
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val review: DualCountReview? = null,
    val message: String? = null,
    val approving: DualCountLine? = null,
    val confirmPost: Boolean = false,
)

/** The disagreements on a dual count journal. D365FO decides everything; this only shows and asks. */
@HiltViewModel
class DualCountReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SupervisorCountRepository,
) : ViewModel() {
    private val journalId: String = checkNotNull(savedStateHandle["journalId"])
    private val _state = MutableStateFlow(DualCountReviewState(journalId = journalId))
    val state: StateFlow<DualCountReviewState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val result = repository.dualCountReview(journalId)) {
                is NetworkResult.Success -> _state.update { it.copy(loading = false, review = result.data) }
                is NetworkResult.Error -> _state.update { it.copy(loading = false, error = result.failure.message) }
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun askApprove(line: DualCountLine) = _state.update { it.copy(approving = line) }
    fun cancelApprove() = _state.update { it.copy(approving = null) }
    fun dismissMessage() = _state.update { it.copy(message = null) }
    fun askPost() = _state.update { it.copy(confirmPost = true) }
    fun cancelPost() = _state.update { it.copy(confirmPost = false) }

    fun approve(line: DualCountLine, finalQty: Double) = settle(line, finalQty, "Line ${line.lineNumber.toInt()} approved.")
    fun recount(line: DualCountLine) = settle(line, null, "Line ${line.lineNumber.toInt()} sent for a recount.")

    private fun settle(line: DualCountLine, finalQty: Double?, done: String) {
        _state.update { it.copy(approving = null, busy = true) }
        viewModelScope.launch {
            val result = repository.settleDualLine(journalId, line.lineNumber, finalQty)
            _state.update { it.copy(busy = false, message = describe(result, done)) }
            if (result is SupervisorResult.Done) load()
        }
    }

    fun post() {
        _state.update { it.copy(confirmPost = false, busy = true) }
        viewModelScope.launch {
            val result = repository.post(journalId)
            _state.update { it.copy(busy = false, message = describe(result, "$journalId has been posted.")) }
        }
    }

    private fun describe(result: SupervisorResult, done: String): String = when (result) {
        is SupervisorResult.Done -> result.message ?: done
        is SupervisorResult.Unreachable -> "Couldn't reach D365FO: ${result.message}"
        is SupervisorResult.Refused -> LiveSupervisorCountRepository.messageFor(result.reason, result.message)
    }
}

private fun Double.qty(): String = BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()

/** Supervisor only: how a dual count journal stands, each disagreement with every count side by side. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DualCountReviewScreen(
    onBack: () -> Unit,
    viewModel: DualCountReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            text = { Text(message) },
            confirmButton = {
                DialogButtonRow(
                    dismiss = null,
                    confirm = {
                        ConfirmButton(
                            onClick = viewModel::dismissMessage,
                            modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                        ) { Text("OK") }
                    },
                )
            },
        )
    }
    state.approving?.let { line ->
        ApproveDialog(line, onConfirm = { viewModel.approve(line, it) }, onDismiss = viewModel::cancelApprove)
    }
    if (state.confirmPost) {
        AlertDialog(
            onDismissRequest = viewModel::cancelPost,
            title = { Text("Post ${state.journalId}?") },
            text = { Text("On-hand is adjusted. This can't be undone.") },
            confirmButton = {
                DialogButtonRow(
                    dismiss = {
                        CancelOutlinedButton(
                            onClick = viewModel::cancelPost,
                            modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                        ) { Text("Cancel") }
                    },
                    confirm = {
                        ConfirmButton(
                            onClick = viewModel::post,
                            modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                        ) { Text("Post journal") }
                    },
                )
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dual count · ${state.journalId}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val review = state.review
            when {
                state.loading && review == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.error != null && review == null -> ErrorBanner(
                    message = state.error.orEmpty(),
                    modifier = Modifier.align(Alignment.Center).padding(WarehouseDimens.ScreenPadding),
                )
                review != null -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = WarehouseDimens.ScreenPadding),
                    contentPadding = PaddingValues(vertical = WarehouseDimens.ItemSpacing),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "summary") { Summary(review, state.busy, viewModel::askPost) }
                    items(review.lines, key = { it.lineNumber }) { line ->
                        LineCard(line, state.busy, onApprove = { viewModel.askApprove(line) }, onRecount = { viewModel.recount(line) })
                    }
                    if (review.truncated) {
                        item(key = "more") {
                            Text(
                                "Showing the first 500. Settle these to see the rest.",
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

@Composable
private fun Summary(review: DualCountReview, busy: Boolean, onPost: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${review.settled} of ${review.total} settled", fontWeight = FontWeight.SemiBold)
            Text("Not counted: ${review.notCounted}")
            Text("Counted once: ${review.firstCounted}")
            Text("Matched: ${review.matched}")
            Text("Disagree: ${review.mismatch}")
            Text("Sent for recount: ${review.recount}")
            Text("Approved: ${review.approved}")
            if (review.emptyConfirmed > 0) Text("Location empty: ${review.emptyConfirmed}")
            Button(
                onClick = onPost,
                enabled = review.canPost && !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = WarehouseDimens.MinTouchTarget),
            ) { Text("Post journal") }
            if (!review.canPost) {
                Text(
                    "Every line must be settled before this can post.",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarehouseStatusColors.text3,
                )
            }
        }
    }
}

@Composable
private fun LineCard(line: DualCountLine, busy: Boolean, onApprove: () -> Unit, onRecount: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${line.itemNumber} · line ${line.lineNumber.toInt()}", fontWeight = FontWeight.SemiBold)
            Text(line.itemName, style = MaterialTheme.typography.bodySmall)
            if (line.where.isNotBlank()) Text(line.where, style = MaterialTheme.typography.bodySmall)
            Text("D365FO holds ${line.systemQty.qty()}")
            line.attempts.forEach { attempt ->
                Text("Count ${attempt.attemptNo}: ${attempt.countedQty.qty()} by ${attempt.userId}")
            }
            Text(
                when {
                    line.needsApproval -> "Three counts disagree: needs your decision."
                    line.status == "Recount" -> "Sent for a recount."
                    else -> "Waiting for a tiebreak count. You can settle it now."
                },
                style = MaterialTheme.typography.bodySmall,
                color = WarehouseStatusColors.amber,
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRecount,
                    enabled = !busy && line.status != "Recount",
                    modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                ) { Text("Recount") }
                Button(
                    onClick = onApprove,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                ) { Text("Approve") }
            }
        }
    }
}

@Composable
private fun ApproveDialog(line: DualCountLine, onConfirm: (Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(line.attempts.lastOrNull()?.countedQty?.qty().orEmpty()) }
    val qty = text.toDoubleOrNull()?.takeIf { it >= 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Approve ${line.itemNumber}") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Final quantity") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            DialogButtonRow(
                dismiss = {
                    CancelOutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) { Text("Cancel") }
                },
                confirm = {
                    ConfirmButton(
                        onClick = { qty?.let(onConfirm) },
                        enabled = qty != null,
                        modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) { Text("Approve") }
                },
            )
        },
    )
}
