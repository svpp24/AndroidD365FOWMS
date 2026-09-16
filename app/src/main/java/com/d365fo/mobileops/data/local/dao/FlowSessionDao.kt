package com.d365fo.mobileops.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d365fo.mobileops.data.local.entity.FlowSessionEntity
import com.d365fo.mobileops.data.local.entity.FlowType
import kotlinx.coroutines.flow.Flow

@Dao
interface FlowSessionDao {

    @Query("SELECT * FROM flow_session WHERE flowType = :flowType AND orderId = :orderId")
    suspend fun find(flowType: FlowType, orderId: String): FlowSessionEntity?

    /**
     * Everything unfinished, newest first.
     *
     * Observed rather than fetched so the home screen updates the moment a job is banked or
     * posted, without anyone having to remember to refresh it.
     */
    @Query("SELECT * FROM flow_session ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<FlowSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: FlowSessionEntity)

    @Query("DELETE FROM flow_session WHERE flowType = :flowType AND orderId = :orderId")
    suspend fun delete(flowType: FlowType, orderId: String)

    /**
     * Housekeeping for sessions nobody came back to.
     *
     * Deliberately generous — a job started before a weekend is still the same job on Monday,
     * and deleting someone's count because they went home is exactly the behaviour that makes
     * people stop trusting the app to hold their work.
     */
    @Query("DELETE FROM flow_session WHERE updatedAt < :before")
    suspend fun deleteOlderThan(before: Long)
}
