package com.d365fo.mobileops.di

import com.d365fo.mobileops.presentation.common.scan.RoomScanLookups
import com.d365fo.mobileops.presentation.common.scan.ScanLookups
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The scan router's on-device lookups - see [com.d365fo.mobileops.presentation.common.scan.ScanRouter]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ScanModule {

    @Binds
    abstract fun bindScanLookups(impl: RoomScanLookups): ScanLookups
}
