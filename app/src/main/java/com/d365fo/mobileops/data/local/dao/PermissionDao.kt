package com.d365fo.mobileops.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.d365fo.mobileops.data.local.entity.WorkerDirectoryEntity
import com.d365fo.mobileops.data.local.entity.WorkerPermissionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PermissionDao {

    /** Live - the Home screen's own gating collects this directly, so a toggle (this worker's
     *  own, or one the admin makes and this device later re-syncs) updates the greyed-out card
     *  without a manual refresh. */
    @Query("SELECT * FROM worker_permission WHERE userName = :userName")
    fun observePermissions(userName: String): Flow<List<WorkerPermissionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(permissions: List<WorkerPermissionEntity>)

    @Query("DELETE FROM worker_permission WHERE userName = :userName")
    suspend fun clearFor(userName: String)

    /**
     * Replaces one worker's whole permission set atomically - see `WorkerSessionRepository.signIn()`,
     * the down-sync direction. A worker whose sign-in response now grants fewer workflows than
     * before must lose the extra rows too, not just gain new ones, so this clears first rather
     * than upserting on top of whatever was cached from an earlier session.
     */
    @Transaction
    suspend fun replaceFor(userName: String, permissions: List<WorkerPermissionEntity>) {
        clearFor(userName)
        upsert(permissions)
    }

    @Query("SELECT * FROM worker_directory ORDER BY displayName")
    fun observeDirectory(): Flow<List<WorkerDirectoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDirectory(workers: List<WorkerDirectoryEntity>)

    @Query("DELETE FROM worker_directory")
    suspend fun clearDirectory()

    /** Replaced wholesale, same reasoning as `WarehouseDao.replaceAll` - a worker removed in
     *  D365FO must disappear from the Admin Center's own list too. */
    @Transaction
    suspend fun replaceDirectory(workers: List<WorkerDirectoryEntity>) {
        clearDirectory()
        upsertDirectory(workers)
    }
}
