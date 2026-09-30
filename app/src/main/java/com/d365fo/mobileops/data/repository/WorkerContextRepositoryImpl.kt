package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.data.security.TokenProvider
import com.d365fo.mobileops.domain.model.WorkerContext
import com.d365fo.mobileops.domain.repository.WorkerContextRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkerContextRepositoryImpl @Inject constructor(
    private val secureStore: SecureStore,
    private val tokenProvider: TokenProvider,
    private val workerSession: WorkerSessionRepository,
) : WorkerContextRepository {

    // Ticks whenever a device-level field changes (markServiceIdentity/setRegistration), so
    // observe() re-reads those too. Warehouse and site need no such tick - they come from
    // workerSession.session below, which is already a live Flow.
    private val deviceFieldsChanged = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }

    /**
     * Recomputes on every device-field change *and* every worker-session change.
     *
     * This used to be a MutableStateFlow seeded once from [load] at singleton construction -
     * before anyone had signed in - and only ever refreshed by an explicit call to [current] or
     * [setRegistration]. Nothing on the worker sign-in path calls either, so a collector of this
     * flow kept showing whatever was true (typically nothing) before sign-in, forever - the
     * "Assigned warehouse" field in Settings included, confirmed live: the Home screen's top bar
     * showed the right warehouse because it reads WorkerSessionRepository directly, while
     * Settings, reading this observe() instead, showed "No warehouse is assigned to you" for a
     * worker who plainly had one.
     */
    override fun observe(): Flow<WorkerContext> =
        combine(deviceFieldsChanged, workerSession.session) { _, _ -> load() }

    override suspend fun current(): WorkerContext = load()

    /**
     * Establishes a device-level fallback identity, ahead of and independent of any worker
     * completing sign-in against HSMobileOpsUser.
     *
     * Without this, [load] would find no worker id and hand back UNAUTHENTICATED before anyone
     * has signed in — which filters the worklist down to nothing and looks like an empty
     * warehouse. The id is prefixed `service:` and carries the device id so that the app's
     * own audit rows can still distinguish handhelds, even though D365FO will record the
     * same CreatedBy for all of them.
     */
    fun markServiceIdentity() {
        secureStore.putString(SecureStore.KEY_WORKER_ID, "service:${deviceId()}")
        deviceFieldsChanged.tryEmit(Unit)
    }

    /**
     * Site and personnel details a device-level operation still needs.
     *
     * Warehouse is deliberately not settable here any more - it comes from
     * [WorkerSessionRepository], the signed-in worker's own backoffice assignment, never from a
     * device-level override. Legal entity stays device-level: the device connects to one company,
     * chosen before anyone signs in, independent of which warehouses that company's directory
     * assigns to whichever worker signs in next.
     */
    fun setRegistration(personnelNumber: String?, dataAreaId: String?) {
        personnelNumber?.let { secureStore.putString(KEY_PERSONNEL_NUMBER, it) }
        dataAreaId?.let { secureStore.putString(KEY_DATA_AREA, it) }
        deviceFieldsChanged.tryEmit(Unit)
    }

    private fun load(): WorkerContext {
        val token = tokenProvider.cachedTokenOrNull()
        val workerId = token?.userObjectId ?: secureStore.getString(SecureStore.KEY_WORKER_ID)

        if (workerId.isNullOrBlank()) return WorkerContext.UNAUTHENTICATED

        secureStore.putString(SecureStore.KEY_WORKER_ID, workerId)

        // The signed-in worker's own backoffice-assigned warehouse - see the class doc on
        // setRegistration. Empty before anyone has signed in, or while several warehouses are
        // assigned and none is flagged default and the worker has not yet picked one.
        val assigned = workerSession.session.value?.selected

        return WorkerContext(
            workerId = workerId,
            personnelNumber = secureStore.getString(KEY_PERSONNEL_NUMBER),
            displayName = token?.userPrincipalName.orEmpty(),
            deviceId = deviceId(),
            dataAreaId = secureStore.getString(KEY_DATA_AREA).orEmpty(),
            siteId = assigned?.siteId.orEmpty(),
            warehouseId = assigned?.warehouseId.orEmpty(),
        )
    }

    /**
     * A stable per-install device identifier - was a second, independent copy of exactly the
     * logic in [SecureStore.deviceId], reading and writing the very same [SecureStore.KEY_DEVICE_ID]
     * key. Delegating instead of duplicating means there is exactly one place this can drift from
     * (see that method's own doc for the logging this now inherits) rather than two that happened
     * to agree only because both always ran against the same underlying key.
     */
    private fun deviceId(): String = secureStore.deviceId()

    private companion object {
        const val KEY_PERSONNEL_NUMBER = "worker_personnel_number"
        const val KEY_DATA_AREA = "worker_data_area"
    }
}
