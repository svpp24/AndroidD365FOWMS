package com.d365fo.mobileops.presentation.common

import androidx.lifecycle.ViewModel
import com.d365fo.mobileops.data.logging.LogEntry
import com.d365fo.mobileops.data.logging.LogStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Thin pass-through to [LogStore] - see its own doc for why this exists outside the sign-in gate. */
@HiltViewModel
class LogViewerViewModel @Inject constructor(
    private val logStore: LogStore,
) : ViewModel() {

    val entries: StateFlow<List<LogEntry>> = logStore.entries

    fun clear() = logStore.clear()
}
