package com.d365fo.mobileops.presentation.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.local.dao.PermissionDao
import com.d365fo.mobileops.data.repository.HandheldMenuRepository
import com.d365fo.mobileops.data.repository.OrderCreationRepository
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.domain.model.HandheldMenu
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The blocked half of Home screen gating - which of the four permission-bearing modules an
 * admin has switched off for the signed-in worker. See `HomeScreen`'s own `ModuleTile.workflow`
 * and `ModuleTileCard`, which reads [blockedWorkflows] to grey out a card for a *second*,
 * independent reason from "not built yet."
 *
 * An allow list - see [com.d365fo.mobileops.domain.model.blockedWorkflows]: a worker with Menu
 * access rows sees only the modules ticked Allowed; one with no rows sees every normal module.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeAccessViewModel @Inject constructor(
    private val sessionRepository: WorkerSessionRepository,
    permissionDao: PermissionDao,
    private val orderCreationRepository: OrderCreationRepository,
    private val menuRepository: HandheldMenuRepository,
) : ViewModel() {

    /** The worker's handheld menus (Mobile device menus), or null when none are set up. */
    val menu: StateFlow<HandheldMenu?> = menuRepository.menu

    init {
        // Sign-in's own permission list predates PO / TO creation and PO confirmation, and
        // sign-in replaces the cached rows with it - so ask the order service too, for every
        // worker who signs in while this screen exists. The pause lets sign-in's own write
        // (WorkerSessionRepository.signIn publishes the session first, then replaces the rows)
        // land before this one, instead of wiping it.
        viewModelScope.launch {
            sessionRepository.session.map { it?.userName }.distinctUntilChanged().collect { userName ->
                if (!userName.isNullOrBlank()) {
                    menuRepository.load(userName)
                    launch { menuRepository.refresh(userName) }
                    delay(1_500)
                    lastRefresh = System.currentTimeMillis()
                    orderCreationRepository.refreshPermissions(userName)
                }
            }
        }
    }

    private var lastRefresh = 0L

    /**
     * Re-reads Menu access and the handheld menus from D365FO - called each time Home shows, so a change made on
     * Mobile device users reaches the worker without signing out. At most every 30 seconds.
     */
    fun refresh() {
        val userName = sessionRepository.session.value?.userName
        val now = System.currentTimeMillis()
        if (userName.isNullOrBlank() || now - lastRefresh < 30_000) return
        lastRefresh = now
        viewModelScope.launch { orderCreationRepository.refreshPermissions(userName) }
        viewModelScope.launch { menuRepository.refresh(userName) }
    }

    val blockedWorkflows: StateFlow<Set<WarehouseWorkflow>> = sessionRepository.session
        .flatMapLatest { session ->
            val userName = session?.userName
            if (userName.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                permissionDao.observePermissions(userName)
            }
        }
        .map { permissions ->
            com.d365fo.mobileops.domain.model.blockedWorkflows(
                allowed = permissions.filter { it.allowed }.map { it.workflow }.toSet(),
                hasRows = permissions.isNotEmpty(),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
}
