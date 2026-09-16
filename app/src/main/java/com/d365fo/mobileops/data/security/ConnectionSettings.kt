package com.d365fo.mobileops.data.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Everything needed to reach one D365FO environment. Entered on the device. */
data class TenantConfig(
    val label: String = D365Environment.UAT.name,
    val resourceUrl: String = "",
    val tenantId: String = "",
    val clientId: String = "",
    /** The device credential. Never written to a build file by the app itself. */
    val clientSecret: String = "",
    /** How this device authenticates to Azure AD. See [AuthType]. */
    val authType: AuthType = AuthType.CLIENT_CREDENTIALS,
) {
    val odataRoot: String get() = "${resourceUrl.trimEnd('/')}/data/"
    val servicesRoot: String get() = "${resourceUrl.trimEnd('/')}/api/services/"
    val host: String get() = resourceUrl.substringAfter("://").substringBefore('/')

    /**
     * Every field must be present before the app is allowed to go live. A half-filled
     * config fails at sign-in with an opaque AAD error rather than at the point the person
     * was actually typing, which is the moment they can still fix it.
     *
     * The secret is only required in [AuthType.CLIENT_CREDENTIALS] mode - device-code sign-in
     * needs no secret at all, which is the entire reason it exists (see
     * ClientCredentialsTokenProvider's own doc comment on what a shared device secret costs).
     */
    val isComplete: Boolean get() = validationError() == null

    fun validationError(): String? = when {
        resourceUrl.isBlank() -> "Environment URL is required"
        !resourceUrl.startsWith("https://") -> "Environment URL must start with https://"
        tenantId.isBlank() -> "Directory (tenant) ID is required"
        clientId.isBlank() -> "Application (client) ID is required"
        authType == AuthType.CLIENT_CREDENTIALS && clientSecret.isBlank() ->
            "Client secret is required"
        else -> null
    }

    /** Checked at token-request time, where a missing secret is the likely cause. */
    fun credentialsError(): String? = when {
        tenantId.isBlank() -> "No directory (tenant) ID configured"
        clientId.isBlank() -> "No application (client) ID configured"
        authType == AuthType.CLIENT_CREDENTIALS && clientSecret.isBlank() ->
            "No client secret configured"
        resourceUrl.isBlank() -> "No environment URL configured"
        else -> null
    }
}

/**
 * Which backend the app talks to, and the credentials for it.
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
    private val _tenant = MutableStateFlow(loadTenant())
    val tenant: StateFlow<TenantConfig> = _tenant.asStateFlow()

    /**
     * Posting is opt-in and defaults to off.
     *
     * The custom AOS services this app posts through have to be deployed to the environment
     * first. Until they are, a queued receipt would hit a 404, dead-letter, and look like an
     * app fault. Off means a freshly connected device reads real data and holds its
     * captures, which is the right state during commissioning.
     */
    private val _postingEnabled = MutableStateFlow(loadPosting())
    val postingEnabled: StateFlow<Boolean> = _postingEnabled.asStateFlow()

    fun saveTenant(config: TenantConfig) {
        secureStore.putString(KEY_LABEL, config.label)
        secureStore.putString(KEY_RESOURCE, config.resourceUrl.trim().trimEnd('/'))
        secureStore.putString(KEY_TENANT_ID, config.tenantId.trim())
        secureStore.putString(KEY_CLIENT_ID, config.clientId.trim())
        // Encrypted at rest by SecureStore. Deliberately never written to local.properties
        // or BuildConfig, so it cannot end up inside an APK or a build log.
        secureStore.putString(KEY_CLIENT_SECRET, config.clientSecret.trim())
        secureStore.putString(KEY_AUTH_TYPE, config.authType.name)
        _tenant.value = loadTenant()
    }

    fun setPostingEnabled(enabled: Boolean) {
        secureStore.putString(KEY_POSTING, enabled.toString())
        _postingEnabled.value = enabled
    }

    fun canGoLive(): Boolean = _tenant.value.isComplete

    /** Prefill from whatever was compiled in, so a configured build still works out of the box. */
    fun compiledDefaultFor(environment: D365Environment): TenantConfig = TenantConfig(
        label = environment.name,
        resourceUrl = environment.resourceUrl,
        tenantId = com.d365fo.mobileops.BuildConfig.AAD_TENANT_ID,
        clientId = com.d365fo.mobileops.BuildConfig.AAD_CLIENT_ID,
        clientSecret = com.d365fo.mobileops.BuildConfig.AAD_CLIENT_SECRET,
    )

    private fun loadTenant(): TenantConfig {
        val compiled = compiledDefaultFor(
            D365Environment.fromNameOrDefault(secureStore.getString(KEY_LABEL)),
        )
        return TenantConfig(
            label = secureStore.getString(KEY_LABEL) ?: compiled.label,
            resourceUrl = secureStore.getString(KEY_RESOURCE)?.takeIf { it.isNotBlank() }
                ?: compiled.resourceUrl,
            tenantId = secureStore.getString(KEY_TENANT_ID)?.takeIf { it.isNotBlank() }
                ?: compiled.tenantId,
            clientId = secureStore.getString(KEY_CLIENT_ID)?.takeIf { it.isNotBlank() }
                ?: compiled.clientId,
            // Stored value wins so a device can be pointed at a different registration without
            // a rebuild; the compiled secret is the fallback, not an override.
            clientSecret = secureStore.getString(KEY_CLIENT_SECRET)?.takeIf { it.isNotBlank() }
                ?: compiled.clientSecret,
            // Absent on every install before this field existed, which falls through to
            // CLIENT_CREDENTIALS - the enum's own default and the only mode that used to exist,
            // so an existing install's behaviour does not change under it.
            authType = secureStore.getString(KEY_AUTH_TYPE)
                ?.let { runCatching { AuthType.valueOf(it) }.getOrNull() }
                ?: AuthType.CLIENT_CREDENTIALS,
        )
    }

    private fun loadPosting(): Boolean =
        secureStore.getString(KEY_POSTING)?.toBooleanStrictOrNull() ?: false

    private companion object {
        const val KEY_LABEL = "connection_label"
        const val KEY_RESOURCE = "connection_resource_url"
        const val KEY_TENANT_ID = "connection_tenant_id"
        const val KEY_CLIENT_ID = "connection_client_id"
        const val KEY_CLIENT_SECRET = "connection_client_secret"
        const val KEY_AUTH_TYPE = "connection_auth_type"
        const val KEY_POSTING = "connection_posting_enabled"
    }
}
