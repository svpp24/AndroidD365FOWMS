package com.d365fo.mobileops.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.d365fo.mobileops.domain.model.WarehouseWorkflow

/**
 * One worker's allow/deny for one [WarehouseWorkflow] - see `HomeScreen.kt`'s `ModuleTile.enabled`
 * for where this gates a Home screen card.
 *
 * Written two ways, on purpose: `WorkerSessionRepository.signIn()` replaces this worker's whole
 * row set on every sign-in (the down-sync direction - a worker's own permissions are always as
 * fresh as their last sign-in, no separate sync worker needed), while `AdminCenterViewModel`
 * writes a single row locally the moment an admin toggles it, before the matching up-sync queue
 * entry (`SyncOperation.SET_WORKER_PERMISSION`) has even been dispatched - see
 * `PostingDispatcher`. Both writers use the same table; there is no conflict because only one
 * device (the admin's own) ever writes ahead of the server for a given toggle, and the next
 * sign-in reconciles it.
 */
@Entity(tableName = "worker_permission", primaryKeys = ["userName", "workflow"])
data class WorkerPermissionEntity(
    val userName: String,
    val workflow: WarehouseWorkflow,
    val allowed: Boolean,
)

/**
 * A lightweight local cache of "who else is a worker," for the Admin Center's own worker list to
 * browse and toggle permissions for. Refreshed on demand (each time Admin Center opens) rather
 * than by a sync worker - see `CachedLookupRepository.listWarehouses()` for the identical
 * "network-first, serve cache when unreachable" shape this follows.
 */
@Entity(tableName = "worker_directory")
data class WorkerDirectoryEntity(
    @PrimaryKey val userName: String,
    val displayName: String,
    val isSupervisor: Boolean,
    val cachedAt: Long,
)
