package com.d365fo.mobileops.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.d365fo.mobileops.data.local.entity.FlowType
import com.d365fo.mobileops.data.local.entity.StagedLineEntity

@Dao
interface StagedLineDao {

    @Insert
    suspend fun insertAll(lines: List<StagedLineEntity>)

    /**
     * One fast local read per order lookup - see the class doc on [StagedLineEntity]. Only lines
     * whose posting is still in this device's queue: once D365FO has taken it, its own figures
     * net it (Pending rows, then InventTrans), and a hold kept past that point outlived an
     * unregister in D365FO - the order then read "already received" (30 Oct).
     */
    @Query(
        "SELECT * FROM staged_line WHERE flowType = :flowType AND orderId = :orderId " +
            "AND queueId IN (SELECT queueId FROM sync_queue)",
    )
    suspend fun forOrder(flowType: FlowType, orderId: String): List<StagedLineEntity>

    /** Holds whose posting has left this device's queue - D365FO has it, or refused it. */
    @Query("DELETE FROM staged_line WHERE queueId NOT IN (SELECT queueId FROM sync_queue)")
    suspend fun clearSent()

    /** Called once the Activity screen's queue-status poll confirms nothing for this order is still Pending. */
    @Query("DELETE FROM staged_line WHERE flowType = :flowType AND orderId = :orderId")
    suspend fun clearForOrder(flowType: FlowType, orderId: String)

    @Query("DELETE FROM staged_line WHERE queueId = :queueId")
    suspend fun clearForQueue(queueId: String)
}
