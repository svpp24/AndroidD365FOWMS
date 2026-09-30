package com.d365fo.mobileops.domain.repository

import com.d365fo.mobileops.domain.model.WorkerContext
import kotlinx.coroutines.flow.Flow

interface WorkerContextRepository {
    fun observe(): Flow<WorkerContext>
    suspend fun current(): WorkerContext
}
