package com.d365fo.mobileops.domain

import com.d365fo.mobileops.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Switches for capabilities whose server side may not exist yet. Read by ViewModels and
 * navigation only - never by the data layer, which stays callable from tests with any value.
 * Values come from `local.properties` (`FEATURE_COUNT_CLAIMS=true`) at build time; everything
 * defaults to off.
 */
interface FeatureFlags {
    /** Claim / heartbeat / release and location-range claims on counting journals. */
    val countClaims: Boolean

    /** DIRECT transfer mode for small counting journals (lines and counts via HSMobileOpsCountTaskSvc). */
    val countDirect: Boolean
}

@Singleton
class BuildConfigFeatureFlags @Inject constructor() : FeatureFlags {
    override val countClaims: Boolean get() = BuildConfig.FEATURE_COUNT_CLAIMS
    override val countDirect: Boolean get() = BuildConfig.FEATURE_COUNT_DIRECT
}
