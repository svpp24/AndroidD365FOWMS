package com.d365fo.mobileops.di

import com.d365fo.mobileops.data.counting.ClaimStore
import com.d365fo.mobileops.data.counting.CountCaptureStore
import com.d365fo.mobileops.data.counting.RoomCountCaptureStore
import com.d365fo.mobileops.data.counting.CountTransport
import com.d365fo.mobileops.data.counting.LiveCountTaskRepository
import com.d365fo.mobileops.data.counting.LiveCountTransport
import com.d365fo.mobileops.data.counting.RoomClaimStore
import com.d365fo.mobileops.data.counting.RoomSubmissionStore
import com.d365fo.mobileops.data.counting.SubmissionStore
import com.d365fo.mobileops.data.counting.supervisor.LiveSupervisorCountRepository
import com.d365fo.mobileops.domain.counting.supervisor.SupervisorCountRepository
import com.d365fo.mobileops.domain.BuildConfigFeatureFlags
import com.d365fo.mobileops.domain.FeatureFlags
import com.d365fo.mobileops.domain.counting.CountTaskRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Inventory count extensions - every capability behind an interface, faked in tests. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CountingModule {

    @Binds @Singleton
    abstract fun bindFeatureFlags(impl: BuildConfigFeatureFlags): FeatureFlags

    @Binds @Singleton
    abstract fun bindCountTaskRepository(impl: LiveCountTaskRepository): CountTaskRepository

    @Binds @Singleton
    abstract fun bindClaimStore(impl: RoomClaimStore): ClaimStore

    @Binds @Singleton
    abstract fun bindSubmissionStore(impl: RoomSubmissionStore): SubmissionStore

    @Binds @Singleton
    abstract fun bindCountTransport(impl: LiveCountTransport): CountTransport

    @Binds @Singleton
    abstract fun bindCountCaptureStore(impl: RoomCountCaptureStore): CountCaptureStore

    @Binds @Singleton
    abstract fun bindSupervisorCountRepository(impl: LiveSupervisorCountRepository): SupervisorCountRepository
}
