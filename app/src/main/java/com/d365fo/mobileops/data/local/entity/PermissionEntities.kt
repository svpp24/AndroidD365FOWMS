package com.d365fo.mobileops.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.d365fo.mobileops.domain.model.WarehouseWorkflow

/**
 * One worker's allow/deny for one [WarehouseWorkflow] - see `HomeScreen.kt`'s `ModuleTile.enabled`
 * for where this gates a Home screen card.
 *
 * `WorkerSessionRepository.signIn()` replaces this worker's whole row set on every sign-in, so a
 * worker's permissions are always as fresh as their last sign-in. Menu access itself is managed
 * in D365FO (Mobile device users), not on the device.
 */
@Entity(tableName = "worker_permission", primaryKeys = ["userName", "workflow"])
data class WorkerPermissionEntity(
    val userName: String,
    val workflow: WarehouseWorkflow,
    val allowed: Boolean,
)

/**
 * The worker list the removed on-device Admin Center browsed. Kept only so existing databases
 * keep their schema; nothing reads or writes it now.
 */
@Entity(tableName = "worker_directory")
data class WorkerDirectoryEntity(
    @PrimaryKey val userName: String,
    val displayName: String,
    val isSupervisor: Boolean,
    val cachedAt: Long,
)
