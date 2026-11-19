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

    @Query("SELECT * FROM worker_permission WHERE userName = :userName")
    suspend fun permissionsFor(userName: String): List<WorkerPermissionEntity>

    /**
     * Sign-in's list: it replaces the modules it names, and keeps the saved rows of the ones it
     * does not (the order modules - Create PO, Create transfer, Confirm PO, Purchase return - come
     * from the order service a moment later). Wiping them hid those menus on every sign-in until
     * that answer came (user, 19 Nov 2026: "few menus, after a few seconds a few more").
     */
    @Transaction
    suspend fun replaceNamedFor(userName: String, permissions: List<WorkerPermissionEntity>) {
        val named = permissions.map { it.workflow }.toSet()
        val kept = permissionsFor(userName).filter { it.workflow !in named }
        clearFor(userName)
        upsert(kept + permissions)
    }

}
