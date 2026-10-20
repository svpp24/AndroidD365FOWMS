package com.d365fo.mobileops.util

import com.d365fo.mobileops.BuildConfig

/** The installed build, as shown to people - the version comes from version.properties. */
object AppVersion {
    /** "Version 1.2.0" - short enough for the sign-in screen. */
    val short: String get() = "Version ${BuildConfig.VERSION_NAME}"

    /** "Version 1.2.0 (10200) · built 2026-10-04 · 0680962" - enough to trace a device to its exact build. */
    val full: String
        get() = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · built ${BuildConfig.BUILD_DATE} · " +
            BuildConfig.GIT_COMMIT
}
