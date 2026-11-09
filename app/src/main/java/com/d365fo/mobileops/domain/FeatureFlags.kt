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

    /** Location-first counting and "Location is empty". */
    val countLocationFirst: Boolean

    /** "Not on this journal": capture unexpected items and send them with the counts. */
    val countExceptions: Boolean

    /** Supervisor variance review, approve / reject / recount and posting. */
    val countSupervisor: Boolean

    /** Opportunity counts offered after a pick or putaway. */
    val countOpportunity: Boolean

    /** "Report discrepancy" in picking. */
    val countDiscrepancy: Boolean

    /** Background notification of new TRIGGERED counts (stand-in for push). */
    val countTriggeredAlerts: Boolean

    /** Location-type badges / filters and verify-only lines (fetches getLineAttributes). */
    val countLocationTypes: Boolean

    /** Fetch the counter profile (external counters, teams) on entering counting. */
    val countExternalCounters: Boolean

    /** Supervisor accuracy report (getAccuracyReport). */
    val countAccuracy: Boolean
}

@Singleton
class BuildConfigFeatureFlags @Inject constructor() : FeatureFlags {
    override val countClaims: Boolean get() = BuildConfig.FEATURE_COUNT_CLAIMS
    override val countDirect: Boolean get() = BuildConfig.FEATURE_COUNT_DIRECT
    override val countLocationFirst: Boolean get() = BuildConfig.FEATURE_COUNT_LOCATION_FIRST
    override val countExceptions: Boolean get() = BuildConfig.FEATURE_COUNT_EXCEPTIONS
    override val countSupervisor: Boolean get() = BuildConfig.FEATURE_COUNT_SUPERVISOR
    override val countOpportunity: Boolean get() = BuildConfig.FEATURE_COUNT_OPPORTUNITY
    override val countDiscrepancy: Boolean get() = BuildConfig.FEATURE_COUNT_DISCREPANCY
    override val countTriggeredAlerts: Boolean get() = BuildConfig.FEATURE_COUNT_TRIGGERED_ALERTS
    override val countLocationTypes: Boolean get() = BuildConfig.FEATURE_COUNT_LOCATION_TYPES
    override val countExternalCounters: Boolean get() = BuildConfig.FEATURE_COUNT_EXTERNAL_COUNTERS
    override val countAccuracy: Boolean get() = BuildConfig.FEATURE_COUNT_ACCURACY
}
