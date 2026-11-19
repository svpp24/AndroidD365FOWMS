package com.d365fo.mobileops.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d365fo.mobileops.data.local.D365Database
import com.d365fo.mobileops.data.local.entity.WorkerPermissionEntity
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Sign-in keeps the order modules' saved rows (user, 19 Nov 2026: menus appearing late). */
class PermissionMergeTest {

    @Test
    fun signInKeepsModulesItDoesNotName() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, D365Database::class.java).build()
        val dao = db.permissionDao()
        val u = "W1"
        val workflows = WarehouseWorkflow.entries
        // The order service's full list, from the last time.
        dao.replaceFor(u, listOf(
            WorkerPermissionEntity(u, workflows[0], true),
            WorkerPermissionEntity(u, WarehouseWorkflow.PO_CREATE, true),
        ))
        // Sign-in names only the older modules - here it turns workflows[0] off.
        dao.replaceNamedFor(u, listOf(WorkerPermissionEntity(u, workflows[0], false)))
        val rows = dao.permissionsFor(u).associate { it.workflow to it.allowed }
        assertEquals(mapOf(workflows[0] to false, WarehouseWorkflow.PO_CREATE to true), rows)
        db.close()
    }
}
