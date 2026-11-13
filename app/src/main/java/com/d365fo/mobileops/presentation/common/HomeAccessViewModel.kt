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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    private val moduleSwitches: com.d365fo.mobileops.data.sync.ModuleSwitches,
) : ViewModel() {

    /** The worker's handheld menus (Mobile device menus), or null when none are set up. */
    val menu: StateFlow<HandheldMenu?> = menuRepository.menu

    init {
        // Sign-in's own permission list predates PO / TO creation and PO confirmation, and
        // sign-in replaces the cached rows with it - so ask the order service too, once per
        // sign-in. The pause lets sign-in's own write (WorkerSessionRepository.signIn publishes
        // the session first, then replaces the rows) land before this one, instead of wiping it.
        // Once per sign-in, not once per screen (13 Nov, performance: every screen holding this
        // model sent getMenu and getModulePermissions again).
        viewModelScope.launch {
            sessionRepository.session.collect { session ->
                val userName = session?.userName
                if (userName.isNullOrBlank()) return@collect
                menuRepository.load(userName)
                if (!claim(session, force = session !== refreshedFor)) return@collect
                launch { menuRepository.refresh(userName) }
                delay(1_500)
                orderCreationRepository.refreshPermissions(userName)
            }
        }
    }

    /**
     * Re-reads Menu access and the handheld menus from D365FO - called each time Home shows, so a
     * change made on Mobile device users reaches the worker without signing out. At most every
     * [REFRESH_MS] for the whole app, whichever screen asks.
     */
    fun refresh() {
        val session = sessionRepository.session.value ?: return
        val userName = session.userName
        if (userName.isNullOrBlank() || !claim(session, force = false)) return
        viewModelScope.launch { orderCreationRepository.refreshPermissions(userName) }
        viewModelScope.launch { menuRepository.refresh(userName) }
    }

    /** Whether this caller does the refresh: a new sign-in always, otherwise once per [REFRESH_MS]. */
    private fun claim(session: Any, force: Boolean): Boolean = synchronized(Companion) {
        val now = System.currentTimeMillis()
        if (!force && session === refreshedFor && now - refreshedAt < REFRESH_MS) return false
        refreshedFor = session
        refreshedAt = now
        true
    }

    private companion object {
        /** The sign-in (session object) last refreshed, and when - shared by every screen. */
        @Volatile var refreshedFor: Any? = null
        @Volatile var refreshedAt = 0L
        const val REFRESH_MS = 5 * 60 * 1000L
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
        // Plus whatever is switched off for everyone (Mobile module switches).
        .combine(moduleSwitches.disabled) { permissions, switchedOff ->
            com.d365fo.mobileops.domain.model.blockedWorkflows(
                allowed = permissions.filter { it.allowed }.map { it.workflow }.toSet(),
                hasRows = permissions.isNotEmpty(),
            ) + switchedOff.keys
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Modules switched off for everyone, with what the worker is told. */
    val switchedOff: StateFlow<Map<WarehouseWorkflow, String>> = moduleSwitches.disabled
}
