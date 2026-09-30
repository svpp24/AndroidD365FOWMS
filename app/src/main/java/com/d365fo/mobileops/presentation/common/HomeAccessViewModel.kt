package com.d365fo.mobileops.presentation.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.local.dao.PermissionDao
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The blocked half of Home screen gating - which of the four permission-bearing modules an
 * admin has switched off for the signed-in worker. See `HomeScreen`'s own `ModuleTile.workflow`
 * and `ModuleTileCard`, which reads [blockedWorkflows] to grey out a card for a *second*,
 * independent reason from "not built yet."
 *
 * Absence-means-allowed, the same rule the whole permission cache follows end to end (see
 * `WorkerPermissionEntity`, `PermissionDao.replaceFor`): a workflow only appears here once it
 * is explicitly blocked, never because nothing has been said about it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeAccessViewModel @Inject constructor(
    sessionRepository: WorkerSessionRepository,
    permissionDao: PermissionDao,
) : ViewModel() {

    val blockedWorkflows: StateFlow<Set<WarehouseWorkflow>> = sessionRepository.session
        .flatMapLatest { session ->
            val userName = session?.userName
            if (userName.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                permissionDao.observePermissions(userName)
            }
        }
        .map { permissions -> permissions.filter { !it.allowed }.map { it.workflow }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
}
