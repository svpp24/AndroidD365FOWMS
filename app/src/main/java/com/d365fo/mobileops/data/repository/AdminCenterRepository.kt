package com.d365fo.mobileops.data.repository

import androidx.room.withTransaction
import com.d365fo.mobileops.data.local.D365Database
import com.d365fo.mobileops.data.local.dao.PermissionDao
import com.d365fo.mobileops.data.local.entity.SyncOperation
import com.d365fo.mobileops.data.local.entity.WorkerDirectoryEntity
import com.d365fo.mobileops.data.local.entity.WorkerPermissionEntity
import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.WorkerDirectoryRequestBody
import com.d365fo.mobileops.data.remote.dto.WorkerDirectoryServiceRequest
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.data.sync.SyncQueueRepository
import com.d365fo.mobileops.data.sync.payload.SetWorkerPermissionPayload
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backs the Admin Center: browsing other workers and toggling their per-module access.
 *
 * The worker directory is network-required, refreshed on demand rather than kept in sync by a
 * worker — the same "read through cache, refresh when reachable" stance
 * [CachedLookupRepository.listWarehouses] already takes, since browsing other workers is not
 * something a shift depends on being available offline. Toggling a permission for a worker
 * already seen *is* offline-safe: it writes the new value locally and queues it for AOS in one
 * transaction, same shape [SyncQueueRepository]/`PostingDispatcher` already use for postings.
 *
 * `actingUserName` on every call is re-verified as a current supervisor server-side — see
 * `HSMobileOpsUserService::setModulePermission`/`getWorkerDirectory`. The Admin Center menu
 * entry being hidden from a non-supervisor client-side is UX, not the security boundary.
 */
@Singleton
class AdminCenterRepository @Inject constructor(
    private val service: MobileOpsServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val secureStore: SecureStore,
    private val permissionDao: PermissionDao,
    private val syncQueue: SyncQueueRepository,
    private val database: D365Database,
) {

    private fun company(): String =
        companyProvider()?.takeIf { it.isNotBlank() }?.lowercase().orEmpty()

    fun observeWorkerDirectory(): Flow<List<WorkerDirectoryEntity>> = permissionDao.observeDirectory()

    fun observePermissions(userName: String): Flow<List<WorkerPermissionEntity>> =
        permissionDao.observePermissions(userName)

    suspend fun refreshWorkerDirectory(actingUserName: String): NetworkResult<Unit> {
        val request = WorkerDirectoryServiceRequest(
            WorkerDirectoryRequestBody(dataAreaId = company(), actingUserName = actingUserName),
        )
        return when (val result = errorMapper.safeCall { service.getWorkerDirectory(request) }) {
            is NetworkResult.Success -> {
                val body = result.data
                if (body.success) {
                    val now = System.currentTimeMillis()
                    permissionDao.replaceDirectory(
                        body.workers.map {
                            WorkerDirectoryEntity(
                                userName = it.userName,
                                displayName = it.displayName,
                                isSupervisor = it.isSupervisor,
                                cachedAt = now,
                            )
                        },
                    )
                    NetworkResult.Success(Unit)
                } else {
                    NetworkResult.Error(
                        AppFailure.BusinessRule(
                            body.message ?: "Could not load the worker list",
                            d365ErrorCode = body.errorCode,
                        ),
                    )
                }
            }

            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
        }
    }

    /**
     * Writes the new value locally and queues it for AOS in one transaction — offline-safe, the
     * same pattern `ReceivingFlowViewModel` uses for a posting. `TransactionPushWorker` (or the
     * reactive immediate push `SyncStatusProvider` triggers the moment the device is back
     * online) drains it from there; there is no separate live-dispatch attempt here the way a
     * posting gets, since nobody is standing at a scan waiting on a permission toggle to land.
     */
    suspend fun togglePermission(
        actingUserName: String,
        targetUserName: String,
        workflow: WarehouseWorkflow,
        allowed: Boolean,
    ) {
        val payload = SetWorkerPermissionPayload(
            idempotencyKey = UUID.randomUUID().toString(),
            dataAreaId = company(),
            actingUserName = actingUserName,
            targetUserName = targetUserName,
            workflow = workflow.name,
            allowed = allowed,
        )
        val entry = syncQueue.buildEntry(
            operation = SyncOperation.SET_WORKER_PERMISSION,
            aggregateId = "$targetUserName:${workflow.name}",
            payload = payload,
            workerId = actingUserName,
            deviceId = secureStore.deviceId(),
        )

        database.withTransaction {
            permissionDao.upsert(
                listOf(WorkerPermissionEntity(userName = targetUserName, workflow = workflow, allowed = allowed)),
            )
            syncQueue.enqueue(entry)
        }
    }
}
