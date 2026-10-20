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

    /** One fast local read per order lookup - see the class doc on [StagedLineEntity]. */
    @Query("SELECT * FROM staged_line WHERE flowType = :flowType AND orderId = :orderId")
    suspend fun forOrder(flowType: FlowType, orderId: String): List<StagedLineEntity>

    /** Called once the Activity screen's queue-status poll confirms nothing for this order is still Pending. */
    @Query("DELETE FROM staged_line WHERE flowType = :flowType AND orderId = :orderId")
    suspend fun clearForOrder(flowType: FlowType, orderId: String)

    @Query("DELETE FROM staged_line WHERE queueId = :queueId")
    suspend fun clearForQueue(queueId: String)
}
