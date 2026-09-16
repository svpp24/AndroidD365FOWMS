package com.d365fo.mobileops.data.security

import com.d365fo.mobileops.BuildConfig

/**
 * Dev / UAT / Prod are switchable at runtime without a reinstall. Switching environments
 * wipes the local database — order and item data from two tenants must never mix.
 */
enum class D365Environment(val label: String, val resourceUrl: String) {
    DEV("Dev", BuildConfig.D365_DEV_RESOURCE),
    UAT("UAT", BuildConfig.D365_UAT_RESOURCE),
    PROD("Production", BuildConfig.D365_PROD_RESOURCE);

    /**
     * False when local.properties left this environment's resource URL blank. Guarded
     * because Retrofit rejects a blank base URL at graph-construction time, which would turn
     * a missing config line into an unexplained crash on first launch.
     */
    val isConfigured: Boolean get() = resourceUrl.startsWith("https://")

    val host: String get() = resourceUrl.substringAfter("://").substringBefore('/')
    val odataRoot: String get() = "$resourceUrl/data/"
    val servicesRoot: String get() = "$resourceUrl/api/services/"

    /** The AAD scope D365FO expects for a resource. */
    val scope: String get() = "$resourceUrl/.default"

    companion object {
        /**
         * Falls back to the first environment that actually has a URL, not to PROD.
         *
         * Defaulting to PROD was both wrong and unsafe: wrong because a build configured
         * only for UAT prefilled an empty URL and looked broken, unsafe because a device
         * with no stored preference should never silently point itself at production.
         */
        fun fromNameOrDefault(name: String?): D365Environment =
            entries.firstOrNull { it.name == name }
                ?: entries.firstOrNull { it.isConfigured }
                ?: UAT
    }
}
