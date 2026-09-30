package com.d365fo.mobileops.data.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything needed to reach one D365FO environment. Entered on the device.
 *
 * [clientSecret] only matters when [authType] is [AuthType.CLIENT_CREDENTIALS] - restored after
 * a brief removal in favour of device-code alone. It ships a fleet-wide secret onto every
 * handheld that carries it (real cost: a rooted or backed-up device yields it), but that risk is
 * now the device owner's explicit choice per profile, not this app's only option.
 */
data class TenantConfig(
    val label: String = D365Environment.UAT.name,
    val resourceUrl: String = "",
    val tenantId: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val authType: AuthType = AuthType.CLIENT_CREDENTIALS,
) {
    val odataRoot: String get() = "${resourceUrl.trimEnd('/')}/data/"
    val servicesRoot: String get() = "${resourceUrl.trimEnd('/')}/api/services/"
    val host: String get() = resourceUrl.substringAfter("://").substringBefore('/')

    /**
     * Every field must be present before the app is allowed to go live. A half-filled
     * config fails at sign-in with an opaque AAD error rather than at the point the person
     * was actually typing, which is the moment they can still fix it.
     */
    val isComplete: Boolean get() = validationError() == null

    fun validationError(): String? = when {
        resourceUrl.isBlank() -> "Environment URL is required"
        !resourceUrl.startsWith("https://") -> "Environment URL must start with https://"
        tenantId.isBlank() -> "Directory (tenant) ID is required"
        clientId.isBlank() -> "Application (client) ID is required"
        authType == AuthType.CLIENT_CREDENTIALS && clientSecret.isBlank() -> "Client secret is required"
        else -> null
    }

    /** Checked at token-request time. */
    fun credentialsError(): String? = when {
        tenantId.isBlank() -> "No directory (tenant) ID configured"
        clientId.isBlank() -> "No application (client) ID configured"
        resourceUrl.isBlank() -> "No environment URL configured"
        authType == AuthType.CLIENT_CREDENTIALS && clientSecret.isBlank() -> "No client secret configured"
        else -> null
    }
}

/**
 * Which backend the app talks to, and the credentials for it - one of possibly several saved
 * *profiles* (Dev / UAT / Prod, or any custom name a fleet wants), exactly one of which is
 * active at a time.
 *
 * Each profile is stored under its own key, keyed by [TenantConfig.label], rather than the
 * single shared slot this class used to have - switching between Dev and UAT no longer loses
 * whichever one you are switching away from. [activate] and [saveTenant] both report whether
 * the active target actually changed; SettingsViewModel wipes the local database when it does,
 * because order and item data from two tenants must never mix (the reason this app clears its
 * database on an environment switch at all). [postingEnabled] is scoped the same way, per
 * profile: a device proven safe to post on Dev must not inherit that the moment it is pointed
 * at a freshly connected Prod profile where the custom AOS services this app posts through may
 * not be deployed yet.
 *
 * Stored through [SecureStore], so the tenant and client ids sit in EncryptedSharedPreferences
 * rather than plain prefs. Neither is secret in the cryptographic sense — a client id is
 * public by design — but together they identify the customer's tenant, and a handheld left
 * in a yard should not hand that over to anyone who pulls the app's data directory.
 *
 * Entered on the device rather than compiled in: commissioning a fleet means pointing
 * handhelds at UAT, then at Production, then occasionally back again. Baking that into the
 * APK turns every change into a rebuild, a re-sign and a redeploy through the MDM.
 */
@Singleton
class ConnectionSettings @Inject constructor(
    private val secureStore: SecureStore,
) {
    init {
        // Must run before the property initializers below read profile state - the same
        // init-order lesson learned the hard way elsewhere in this app (see
        // ReceivingFlowViewModel's own two-init-block comment).
        ensureSeeded()
    }

    private val _profiles = MutableStateFlow(loadProfileLabels().mapNotNull(::readProfile))
    val profiles: StateFlow<List<TenantConfig>> = _profiles.asStateFlow()

    // Reuses _profiles rather than re-reading the active profile's five fields a second time -
    // this whole chain runs synchronously on the main thread at first injection (SessionViewModel
    // needs canGoLive() before it can render anything), so every avoidable EncryptedSharedPreferences
    // read here is a launch-latency cost, not a free correctness improvement.
    private val _tenant = MutableStateFlow(
        secureStore.getString(KEY_ACTIVE_LABEL).let { activeLabel ->
            _profiles.value.firstOrNull { it.label == activeLabel }
                ?: TenantConfig(label = activeLabel ?: D365Environment.UAT.name)
        },
    )
    val tenant: StateFlow<TenantConfig> = _tenant.asStateFlow()

    /**
     * Posting is opt-in and defaults to off, per profile.
     *
     * The custom AOS services this app posts through have to be deployed to each environment
     * first. Until they are, a queued receipt would hit a 404, dead-letter, and look like an
     * app fault. Off means a freshly connected profile reads real data and holds its
     * captures, which is the right state during commissioning - and switching to a *different*
     * profile must not carry an earlier one's "yes, this is safe to post to" along with it.
     */
    private val _postingEnabled = MutableStateFlow(loadPosting(_tenant.value.label))
    val postingEnabled: StateFlow<Boolean> = _postingEnabled.asStateFlow()

    /**
     * Saves [config] under its own label and makes it the active connection - editing the
     * fields on screen and saving has always meant "this is what the device talks to now."
     *
     * @return true if this actually changed the active resource URL or tenant ID, meaning the
     * caller must wipe local data before the next read happens against the new target.
     */
    fun saveTenant(config: TenantConfig): Boolean {
        val previous = _tenant.value
        val changed = previous.label == config.label &&
            (previous.resourceUrl != config.resourceUrl || previous.tenantId != config.tenantId)

        writeProfile(config)
        val labels = (loadProfileLabels() + config.label).distinct()
        secureStore.putString(KEY_PROFILE_LABELS, labels.joinToString(","))
        secureStore.putString(KEY_ACTIVE_LABEL, config.label)

        _profiles.value = labels.mapNotNull(::readProfile)
        _tenant.value = readProfile(config.label) ?: config
        _postingEnabled.value = loadPosting(config.label)

        // A brand new label (not previously the active one) is always a target change too -
        // there is no "previous" state for it to compare against.
        return changed || previous.label != config.label
    }

    /**
     * Switches to an already-saved profile without editing it - a supervisor moving a device
     * from Dev to UAT, not correcting a typo. No-ops (returns false) if [label] is not a saved
     * profile or is already active.
     *
     * @return true if this changed the active target, meaning the caller must wipe local data.
     */
    fun activate(label: String): Boolean {
        if (label == _tenant.value.label) return false
        val target = readProfile(label) ?: return false

        secureStore.putString(KEY_ACTIVE_LABEL, label)
        _tenant.value = target
        _postingEnabled.value = loadPosting(label)
        return true
    }

    /**
     * Removes a saved profile. Refuses to delete the active one - switch to a different profile
     * first - so there is never a moment with no active connection to fall back on.
     */
    fun deleteProfile(label: String): Boolean {
        if (label == _tenant.value.label) return false
        val labels = loadProfileLabels() - label
        if (labels.isEmpty()) return false

        secureStore.putString(KEY_PROFILE_LABELS, labels.joinToString(","))
        clearProfileFields(label)
        secureStore.remove(postingKey(label))
        _profiles.value = labels.mapNotNull(::readProfile)
        return true
    }

    fun setPostingEnabled(enabled: Boolean) {
        val label = _tenant.value.label
        secureStore.putString(postingKey(label), enabled.toString())
        _postingEnabled.value = enabled
    }

    fun canGoLive(): Boolean = _tenant.value.isComplete

    /**
     * Prefill from whatever was compiled in, so a configured build still works out of the box -
     * device-code, not client-credentials, as of 2026-09-29 (see local.properties' own comment
     * on AAD_CLIENT_ID). A fresh install still needs the one-time interactive browser step this
     * implies; there is no way to compile in a device-code sign-in that skips it, unlike a
     * client secret.
     */
    fun compiledDefaultFor(environment: D365Environment): TenantConfig = TenantConfig(
        label = environment.name,
        resourceUrl = environment.resourceUrl,
        tenantId = com.d365fo.mobileops.BuildConfig.AAD_TENANT_ID,
        clientId = com.d365fo.mobileops.BuildConfig.AAD_CLIENT_ID,
        clientSecret = com.d365fo.mobileops.BuildConfig.AAD_CLIENT_SECRET,
        authType = AuthType.DEVICE_CODE,
    )

    // --- persistence ---

    private fun writeProfile(config: TenantConfig) {
        val label = config.label
        secureStore.putString(resourceKey(label), config.resourceUrl.trim().trimEnd('/'))
        secureStore.putString(tenantIdKey(label), config.tenantId.trim())
        secureStore.putString(clientIdKey(label), config.clientId.trim())
        secureStore.putString(clientSecretKey(label), config.clientSecret.trim())
        secureStore.putString(authTypeKey(label), config.authType.name)
    }

    private fun clearProfileFields(label: String) {
        secureStore.remove(resourceKey(label))
        secureStore.remove(tenantIdKey(label))
        secureStore.remove(clientIdKey(label))
        secureStore.remove(clientSecretKey(label))
        secureStore.remove(authTypeKey(label))
    }

    private fun readProfile(label: String): TenantConfig? {
        val resourceUrl = secureStore.getString(resourceKey(label)) ?: return null
        return TenantConfig(
            label = label,
            resourceUrl = resourceUrl,
            tenantId = secureStore.getString(tenantIdKey(label)) ?: "",
            clientId = secureStore.getString(clientIdKey(label)) ?: "",
            clientSecret = secureStore.getString(clientSecretKey(label)) ?: "",
            authType = secureStore.getString(authTypeKey(label))
                ?.let { runCatching { AuthType.valueOf(it) }.getOrNull() }
                ?: AuthType.CLIENT_CREDENTIALS,
        )
    }

    private fun loadProfileLabels(): List<String> =
        secureStore.getString(KEY_PROFILE_LABELS)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    private fun loadPosting(label: String): Boolean =
        secureStore.getString(postingKey(label))?.toBooleanStrictOrNull() ?: false

    /**
     * Runs once, ever, the first time this class loads on a given install.
     *
     * Two cases: an install from before named profiles existed has its single saved connection
     * (the old KEY_LEGACY_* slot) become the first, active profile under whatever label it
     * already had - nothing about an existing device's behaviour changes. A genuinely fresh
     * install instead gets one profile per environment local.properties actually compiled a URL
     * for, active = the same fallback D365Environment.fromNameOrDefault already used to pick
     * before profiles existed - so "supports dev/uat/prod" is true out of the box, not just
     * once someone adds them by hand.
     */
    private fun ensureSeeded() {
        if (secureStore.getString(KEY_PROFILE_LABELS) != null) return

        val legacyResource = secureStore.getString(KEY_LEGACY_RESOURCE)
        if (legacyResource != null) {
            val label = secureStore.getString(KEY_LEGACY_LABEL)?.takeIf { it.isNotBlank() }
                ?: D365Environment.UAT.name
            writeProfile(
                TenantConfig(
                    label = label,
                    resourceUrl = legacyResource,
                    tenantId = secureStore.getString(KEY_LEGACY_TENANT_ID) ?: "",
                    clientId = secureStore.getString(KEY_LEGACY_CLIENT_ID) ?: "",
                    clientSecret = secureStore.getString(KEY_LEGACY_CLIENT_SECRET) ?: "",
                    authType = secureStore.getString(KEY_LEGACY_AUTH_TYPE)
                        ?.let { runCatching { AuthType.valueOf(it) }.getOrNull() }
                        ?: AuthType.CLIENT_CREDENTIALS,
                ),
            )
            secureStore.getString(KEY_LEGACY_POSTING)?.let { secureStore.putString(postingKey(label), it) }
            secureStore.putString(KEY_PROFILE_LABELS, label)
            secureStore.putString(KEY_ACTIVE_LABEL, label)
            return
        }

        val configured = D365Environment.entries.filter { it.isConfigured }
        if (configured.isEmpty()) return

        configured.forEach { writeProfile(compiledDefaultFor(it)) }
        secureStore.putString(KEY_PROFILE_LABELS, configured.joinToString(",") { it.name })
        secureStore.putString(KEY_ACTIVE_LABEL, D365Environment.fromNameOrDefault(null).name)
    }

    private fun resourceKey(label: String) = "connection_profile_resource_url::$label"
    private fun tenantIdKey(label: String) = "connection_profile_tenant_id::$label"
    private fun clientIdKey(label: String) = "connection_profile_client_id::$label"
    private fun clientSecretKey(label: String) = "connection_profile_client_secret::$label"
    private fun authTypeKey(label: String) = "connection_profile_auth_type::$label"
    private fun postingKey(label: String) = "connection_profile_posting_enabled::$label"

    private companion object {
        const val KEY_PROFILE_LABELS = "connection_profile_labels"
        /** Reuses a key SecureStore already reserved for exactly this, unused until now. */
        val KEY_ACTIVE_LABEL = SecureStore.KEY_ACTIVE_ENVIRONMENT

        // Pre-profiles single-slot keys. Read once, by ensureSeeded(), on an install that had
        // already saved a connection before profiles existed - never written again after that.
        const val KEY_LEGACY_LABEL = "connection_label"
        const val KEY_LEGACY_RESOURCE = "connection_resource_url"
        const val KEY_LEGACY_TENANT_ID = "connection_tenant_id"
        const val KEY_LEGACY_CLIENT_ID = "connection_client_id"
        const val KEY_LEGACY_CLIENT_SECRET = "connection_client_secret"
        const val KEY_LEGACY_AUTH_TYPE = "connection_auth_type"
        const val KEY_LEGACY_POSTING = "connection_posting_enabled"
    }
}
