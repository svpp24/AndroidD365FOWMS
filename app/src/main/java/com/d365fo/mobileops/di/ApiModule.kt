package com.d365fo.mobileops.di

import com.d365fo.mobileops.data.remote.api.CountSupervisorServiceApi
import com.d365fo.mobileops.data.remote.api.CountTaskServiceApi
import com.d365fo.mobileops.data.remote.api.LiveCountSupervisorServiceApi
import com.d365fo.mobileops.data.remote.api.LiveMenuServiceApi
import com.d365fo.mobileops.data.remote.api.MenuServiceApi
import com.d365fo.mobileops.data.remote.api.D365ODataApi
import com.d365fo.mobileops.data.remote.api.LiveCountTaskServiceApi
import com.d365fo.mobileops.data.remote.api.LiveD365ODataApi
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The wire: always the real D365FO endpoints, built from the tenant configured on the device.
 *
 * This used to be two files — a debug variant that could route to an in-memory demo bench, and a
 * release variant that could not. The bench is gone, so the split is gone with it: one module, one
 * behaviour, no per-variant wiring to reason about and nothing that behaves differently in the
 * build you test from the build you ship.
 *
 * The custom AOS service (`MobileOpsServiceApi`) is bound in [AppModule], alongside the
 * repositories that are its only callers.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ApiModule {

    @Binds
    @Singleton
    abstract fun bindODataApi(live: LiveD365ODataApi): D365ODataApi

    @Binds
    @Singleton
    abstract fun bindCountTaskServiceApi(live: LiveCountTaskServiceApi): CountTaskServiceApi

    @Binds
    @Singleton
    abstract fun bindCountSupervisorServiceApi(live: LiveCountSupervisorServiceApi): CountSupervisorServiceApi

    @Binds
    @Singleton
    abstract fun bindMenuServiceApi(live: LiveMenuServiceApi): MenuServiceApi

    @Binds
    @Singleton
    abstract fun bindSessionServiceApi(
        live: com.d365fo.mobileops.data.remote.api.LiveSessionServiceApi,
    ): com.d365fo.mobileops.data.remote.api.SessionServiceApi

    @Binds
    @Singleton
    abstract fun bindCountStageServiceApi(
        live: com.d365fo.mobileops.data.remote.api.LiveCountStageServiceApi,
    ): com.d365fo.mobileops.data.remote.api.CountStageServiceApi
}
