package com.d365fo.mobileops.data.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything needed to reach one D365FO environment. Entered on the device.
 *
 * Signs in with client credentials only - device-code sign-in was removed (Microsoft Entra
 * security defaults block it in new tenants from 1 July 2026). [clientSecret] ships onto every
 * handheld that carries it (real cost: a rooted or backed-up device yields it), but that risk is
 * now the device owner's explicit choice per profile, not this app's only option.
 */
data class TenantConfig(
    val label: String = D365Environment.UAT.name,
    val resourceUrl: String = "",
    val tenantId: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    /**
     * More app registrations for client credentials, after [clientId]/[clientSecret]: each
     * device uses one, so the fleet shares D365FO's per-registration request allowance out
     * between them - see [CredentialPool].
     */
    val extraCredentials: List<AppCredential> = emptyList(),
    /**
     * The legal entity this profile works in, from its connection QR code. Blank for a profile
     * saved before profiles carried one - the device's own company setting is used then.
     */
    val company: String = "",
    /**
     * The customer's token service, from a device setup code ([DeviceSetupQr]). Set, the device
     * gets its tokens there with its own device key and holds no tenant, client ID or secret.
     */
    val tokenServiceUrl: String = "",
) {
    val usesTokenService: Boolean get() = tokenServiceUrl.isNotBlank()

    /** Every registration a device may use: the main one first, then [extraCredentials]. */
    val credentials: List<AppCredential>
        get() = listOf(AppCredential(clientId.trim(), clientSecret.trim())) + extraCredentials

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
        usesTokenService && !tokenServiceUrl.startsWith("https://") -> "Token service address must start with https://"
        usesTokenService -> null
        tenantId.isBlank() -> "Directory (tenant) ID is required"
        clientId.isBlank() -> "Application (client) ID is required"
        clientSecret.isBlank() -> "Client secret is required"
        extraCredentials.any { !it.isComplete } -> "Each extra app registration needs a client ID and a secret"
        else -> null
    }

    /** Checked at token-request time. */
    fun credentialsError(): String? = when {
        usesTokenService -> if (resourceUrl.isBlank()) "No environment URL configured" else null
        tenantId.isBlank() -> "No directory (tenant) ID configured"
        clientId.isBlank() -> "No application (client) ID configured"
        resourceUrl.isBlank() -> "No environment URL configured"
        clientSecret.isBlank() -> "No client secret configured"
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
        // The removed device-code sign-in kept a refresh token here; a token nothing uses any
        // more is still a credential, so it does not stay on the device.
        SecureStore.LEGACY_DEVICE_CODE_KEYS.forEach(secureStore::remove)
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
    /**
     * Gives a profile saved before profiles carried a company the device's own one - a one-off
     * backfill so the profile card shows it and switching back to it restores it.
     */
    fun backfillCompany(company: String) {
        val current = _tenant.value
        if (current.company.isNotBlank() || company.isBlank() || readProfile(current.label) == null) return
        secureStore.putString(companyKey(current.label), company.trim().uppercase())
        _tenant.value = current.copy(company = company.trim().uppercase())
        _profiles.value = loadProfileLabels().mapNotNull(::readProfile)
    }

    /** Whether [saveTenant] with [config] would point the device somewhere new (and so wipe local data). */
    fun wouldChangeTarget(config: TenantConfig): Boolean {
        val previous = _tenant.value
        return previous.label != config.label ||
            previous.resourceUrl != config.resourceUrl || previous.tenantId != config.tenantId
    }

    /** Whether [activate] with [label] would point the device somewhere new. */
    fun wouldChangeTarget(label: String): Boolean = label != _tenant.value.label && readProfile(label) != null

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
     * Removes a saved profile - its URL and app registrations. The active one too: another saved
     * profile then becomes active, or none is, and the device asks for a connection QR code.
     *
     * @return true when the active target changed, meaning the caller must wipe local data.
     */
    fun deleteProfile(label: String): Boolean {
        val labels = loadProfileLabels()
        if (label !in labels) return false
        val remaining = labels - label

        // An empty list is stored as "", never removed: a missing key re-seeds the compiled-in
        // defaults on the next start (see ensureSeeded), which would undo the delete.
        secureStore.putString(KEY_PROFILE_LABELS, remaining.joinToString(","))
        clearProfileFields(label)
        secureStore.remove(postingKey(label))
        _profiles.value = remaining.mapNotNull(::readProfile)

        if (label != _tenant.value.label) return false
        val next = remaining.firstOrNull()
        if (next != null) {
            secureStore.putString(KEY_ACTIVE_LABEL, next)
            _tenant.value = readProfile(next) ?: TenantConfig(label = next)
        } else {
            secureStore.remove(KEY_ACTIVE_LABEL)
            _tenant.value = TenantConfig(label = "")
        }
        _postingEnabled.value = loadPosting(_tenant.value.label)
        return true
    }

    /**
     * Removes the tenant, client IDs and secrets of every profile. Profiles and their URLs stay,
     * and so does everything in the local database: no work is lost, it waits until a
     * connection QR code is scanned again.
     */
    fun clearAllCredentials() {
        loadProfileLabels().forEach { label ->
            secureStore.remove(tenantIdKey(label))
            secureStore.remove(clientIdKey(label))
            secureStore.remove(clientSecretKey(label))
            secureStore.remove(poolKey(label))
            secureStore.remove(authTypeKey(label))
            secureStore.remove(tokenServiceKey(label))
        }
        val label = _tenant.value.label
        _tenant.value = readProfile(label) ?: TenantConfig(label = label)
        _profiles.value = loadProfileLabels().mapNotNull(::readProfile)
    }

    fun setPostingEnabled(enabled: Boolean) {
        val label = _tenant.value.label
        secureStore.putString(postingKey(label), enabled.toString())
        _postingEnabled.value = enabled
    }

    fun canGoLive(): Boolean = _tenant.value.isComplete

    /**
     * Prefill from whatever was compiled in, so a developer build still works out of the box. A
     * fleet build normally compiles nothing in and is configured by scanning the connection QR
     * code instead (see ConnectionQr).
     */
    fun compiledDefaultFor(environment: D365Environment): TenantConfig = TenantConfig(
        label = environment.name,
        resourceUrl = environment.resourceUrl,
        tenantId = com.d365fo.mobileops.BuildConfig.AAD_TENANT_ID,
        clientId = com.d365fo.mobileops.BuildConfig.AAD_CLIENT_ID,
        clientSecret = com.d365fo.mobileops.BuildConfig.AAD_CLIENT_SECRET,
        // local.properties keeps it on one line: registrations separated by ";".
        extraCredentials = CredentialPool.parse(
            com.d365fo.mobileops.BuildConfig.AAD_CLIENT_POOL.replace(';', '\n'),
        ),
    )

    // --- persistence ---

    private fun writeProfile(config: TenantConfig) {
        val label = config.label
        secureStore.putString(resourceKey(label), config.resourceUrl.trim().trimEnd('/'))
        secureStore.putString(tenantIdKey(label), config.tenantId.trim())
        secureStore.putString(clientIdKey(label), config.clientId.trim())
        secureStore.putString(clientSecretKey(label), config.clientSecret.trim())
        secureStore.putString(poolKey(label), CredentialPool.format(config.extraCredentials))
        if (config.company.isNotBlank()) secureStore.putString(companyKey(label), config.company.trim().uppercase())
        secureStore.putString(tokenServiceKey(label), config.tokenServiceUrl.trim().trimEnd('/'))
    }

    private fun clearProfileFields(label: String) {
        secureStore.remove(resourceKey(label))
        secureStore.remove(tenantIdKey(label))
        secureStore.remove(clientIdKey(label))
        secureStore.remove(clientSecretKey(label))
        secureStore.remove(authTypeKey(label))
        secureStore.remove(poolKey(label))
        secureStore.remove(companyKey(label))
        secureStore.remove(tokenServiceKey(label))
    }

    private fun readProfile(label: String): TenantConfig? {
        val resourceUrl = secureStore.getString(resourceKey(label)) ?: return null
        return TenantConfig(
            label = label,
            resourceUrl = resourceUrl,
            tenantId = secureStore.getString(tenantIdKey(label)) ?: "",
            clientId = secureStore.getString(clientIdKey(label)) ?: "",
            clientSecret = secureStore.getString(clientSecretKey(label)) ?: "",
            extraCredentials = CredentialPool.parse(secureStore.getString(poolKey(label)).orEmpty()),
            company = secureStore.getString(companyKey(label)).orEmpty(),
            tokenServiceUrl = secureStore.getString(tokenServiceKey(label)).orEmpty(),
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
    private fun poolKey(label: String) = "connection_profile_client_pool::$label"
    private fun companyKey(label: String) = "connection_profile_company::$label"
    private fun tokenServiceKey(label: String) = "connection_profile_token_service::$label"
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
        const val KEY_LEGACY_POSTING = "connection_posting_enabled"
    }
}
