package com.d365fo.mobileops.presentation.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.repository.FeedbackEntry
import com.d365fo.mobileops.data.repository.FeedbackOrigin
import com.d365fo.mobileops.data.repository.FeedbackRepository
import com.d365fo.mobileops.data.repository.FeedbackType
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class FeedbackState(
    val screen: String = "",
    val screenshot: File? = null,
    val includeScreenshot: Boolean = true,
    val type: FeedbackType = FeedbackType.Problem,
    val message: String = "",
    val sending: Boolean = false,
    /** The feedback just sent - a thank-you replaces the form until "Send more". */
    val sentId: String? = null,
    val mine: List<FeedbackEntry> = emptyList(),
    val loadingMine: Boolean = false,
    val mineError: String? = null,
    /** Feedback being changed - see [FeedbackViewModel.startEdit]. */
    val editing: com.d365fo.mobileops.data.repository.FeedbackEntry? = null,
    val editType: FeedbackType = FeedbackType.Problem,
    val editMessage: String = "",
    val saving: Boolean = false,
    val editError: String? = null,
)

/**
 * Feedback from the app: what kind, what happened, and - when opened from a screen's feedback
 * button - that screen's name and screenshot. Below it, the worker's earlier feedback with the
 * team's status and reply.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FeedbackViewModel @Inject constructor(
    private val repository: FeedbackRepository,
    private val sessions: WorkerSessionRepository,
    origin: FeedbackOrigin,
) : ViewModel() {

    private val _state = MutableStateFlow(FeedbackState())
    val state: StateFlow<FeedbackState> = _state.asStateFlow()

    private val userName: String get() = sessions.session.value?.userName.orEmpty()

    /** Feedback still on this device. */
    val unsent: StateFlow<List<FeedbackEntry>> = repository.observeUnsent(userName)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val context: String
        get() = listOf(
            _state.value.screen.ifBlank { null },
            "v${repository.appVersion}",
            sessions.session.value?.selected?.warehouseId,
            repository.deviceModel,
        ).filterNotNull().joinToString(" · ")

    init {
        val (screen, shot) = origin.consume()
        _state.update { it.copy(screen = screen ?: "General", screenshot = shot, includeScreenshot = shot != null) }
        refreshMine()
        // Feedback is sent in the background: once one leaves the device, D365FO's list has it.
        viewModelScope.launch {
            var before = 0
            unsent.collect { rows -> if (rows.size < before) refreshMine(); before = rows.size }
        }
    }

    fun setType(type: FeedbackType) = _state.update { it.copy(type = type) }

    fun setMessage(value: String) = _state.update { it.copy(message = value.take(4000)) }

    fun setIncludeScreenshot(value: Boolean) = _state.update { it.copy(includeScreenshot = value) }

    fun send() {
        val s = _state.value
        if (s.message.isBlank() || s.sending) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true) }
            val id = repository.submit(
                userName = userName,
                warehouseId = sessions.session.value?.selected?.warehouseId.orEmpty(),
                screen = s.screen,
                type = s.type,
                message = s.message,
                screenshot = s.screenshot.takeIf { s.includeScreenshot },
            )
            _state.update { it.copy(sending = false, sentId = id, message = "") }
            refreshMine()
        }
    }

    /** Back to an empty form, for more feedback about the same screen. */
    fun sendMore() = _state.update { it.copy(sentId = null) }

    /** Opens feedback for changing - only feedback the team has not picked up yet (New). */
    fun startEdit(entry: com.d365fo.mobileops.data.repository.FeedbackEntry) {
        if (entry.waiting || entry.status != "New") return
        _state.update {
            it.copy(
                editing = entry,
                editType = FeedbackType.entries.firstOrNull { t -> t.name == entry.type } ?: FeedbackType.Problem,
                editMessage = entry.message,
                editError = null,
            )
        }
    }

    fun setEditType(type: FeedbackType) = _state.update { it.copy(editType = type) }

    fun setEditMessage(value: String) = _state.update { it.copy(editMessage = value.take(4000)) }

    fun cancelEdit() = _state.update { it.copy(editing = null, editError = null) }

    fun saveEdit() {
        val s = _state.value
        val entry = s.editing ?: return
        if (s.editMessage.isBlank() || s.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, editError = null) }
            when (val result = repository.update(userName, entry.id, s.editType, s.editMessage)) {
                is NetworkResult.Success -> {
                    _state.update { it.copy(saving = false, editing = null) }
                    refreshMine()
                }
                is NetworkResult.Error -> _state.update {
                    it.copy(
                        saving = false,
                        editError = if (result.failure.retryable) "Can't reach D365FO - connect to change it." else result.failure.message,
                    )
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun refreshMine() {
        if (userName.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMine = true) }
            when (val result = repository.mine(userName)) {
                is NetworkResult.Success -> _state.update { it.copy(loadingMine = false, mine = result.data, mineError = null) }
                is NetworkResult.Error -> _state.update {
                    it.copy(
                        loadingMine = false,
                        mineError = if (result.failure.retryable) "Can't reach D365FO - your earlier feedback shows once it can be reached." else result.failure.message,
                    )
                }
                NetworkResult.Loading -> Unit
            }
        }
    }
}

/** Gives AppNavGraph the [FeedbackOrigin] the feedback button hands its screenshot to. */
@HiltViewModel
class FeedbackOriginViewModel @Inject constructor(val origin: FeedbackOrigin) : ViewModel()
