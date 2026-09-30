package com.d365fo.mobileops.presentation.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.local.entity.WorkerDirectoryEntity
import com.d365fo.mobileops.data.local.entity.WorkerPermissionEntity
import com.d365fo.mobileops.data.repository.AdminCenterRepository
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Admin Center screen - browsing other workers and toggling their access to the four
 * gated modules. See [AdminCenterRepository] for the offline-safe write and the
 * network-required, refresh-on-open directory read this wraps.
 */
@HiltViewModel
class AdminCenterViewModel @Inject constructor(
    private val repository: AdminCenterRepository,
    private val sessionRepository: WorkerSessionRepository,
) : ViewModel() {

    val workers: StateFlow<List<WorkerDirectoryEntity>> = repository.observeWorkerDirectory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val actingUserName = sessionRepository.session.value?.userName?.takeIf { it.isNotBlank() }
            ?: return
        viewModelScope.launch {
            _refreshing.value = true
            when (val result = repository.refreshWorkerDirectory(actingUserName)) {
                is NetworkResult.Error -> _error.value = result.failure.message
                else -> _error.value = null
            }
            _refreshing.value = false
        }
    }

    fun dismissError() {
        _error.value = null
    }

    fun permissionsFor(userName: String): Flow<List<WorkerPermissionEntity>> =
        repository.observePermissions(userName)

    /**
     * Fires and forgets, on purpose - [AdminCenterRepository.togglePermission] already commits
     * the new value locally before this suspend call returns, so the Home screen of a device
     * that already cached this worker's permissions updates the moment the transaction lands,
     * regardless of whether the AOS push that follows succeeds immediately, retries, or waits
     * for connectivity.
     */
    fun togglePermission(targetUserName: String, workflow: WarehouseWorkflow, allowed: Boolean) {
        val actingUserName = sessionRepository.session.value?.userName?.takeIf { it.isNotBlank() }
            ?: return
        viewModelScope.launch {
            repository.togglePermission(actingUserName, targetUserName, workflow, allowed)
        }
    }
}
