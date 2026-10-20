package com.d365fo.mobileops.data.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's key-value store. Only secrets are encrypted (decision, 11 Oct: "only connection and
 * login accounts need encryption") - the connection settings (tenant, client id and secret,
 * company, pins), the offline sign-in records and last user, the worker's identity, the
 * licence records; see [isSecret]. Every other key - device
 * id, preferences, tour and counting state, sync stats - goes to a plain SharedPreferences
 * file. A non-secret value still in the encrypted file from an earlier version is moved across
 * the first time it is read, so callers never notice.
 *
 * The master key lives in the Android Keystore (hardware-backed where available) and is
 * never exportable; EncryptedSharedPreferences holds the ciphertext.
 *
 * **Every read/write is wrapped and logged (via Timber - see LogStore), not left to throw or
 * fail silently.** Confirmed live on a real device: values written earlier the same day (a
 * cached DeviceId, an AAD refresh token) were later gone with no uninstall in between - and
 * every access point up to this one (`init { runCatching { ensureKeystoreKey() } }`,
 * unguarded `prefs.getString`/`putString` calls) swallowed or never checked for a failure, so
 * there was no way to tell "genuinely never set" apart from "read/write threw and got lost."
 * This doesn't fix whatever is causing the underlying loss - a suspected Keystore/storage quirk
 * on that specific hardware, not reproducible on demand - but the next occurrence will show up
 * as a logged exception in the in-app log viewer instead of silent, confusing data loss.
 */
@Singleton
class SecureStore @Inject constructor(private val context: Context) {

    /**
     * Built on first use, not in the constructor.
     *
     * EncryptedSharedPreferences.create() generates or unwraps a Keystore master key and opens
     * an encrypted file. Held as a constructor property it ran during Hilt graph construction -
     * inside Application.onCreate, on the main thread, ahead of the first frame. Nothing needs
     * it before the first screen asks a question of it, and by then it can be reached off the
     * main thread.
     */
    private val prefs: SharedPreferences by lazy {
        try {
            createPrefs()
        } catch (e: Exception) {
            // A corrupt file or a Keystore key EncryptedSharedPreferences can no longer unwrap
            // (see the class doc) would otherwise throw on every single access forever - this
            // device would never recover without an uninstall. Wiping the file and the Keystore
            // alias and rebuilding fresh loses whatever was in it, exactly like the "genuinely
            // never configured" state every caller already handles gracefully (NotConfiguredScreen,
            // NO_LICENSE, "Device sign-in required", ...) - a clean, understood failure mode
            // instead of a wedged one.
            Timber.e(e, "SecureStore: opening encrypted prefs failed, resetting store")
            resetCorruptedStore()
            createPrefs()
        }
    }

    private fun createPrefs(): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            MasterKey.Builder(context, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setUserAuthenticationRequired(false)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private fun resetCorruptedStore() {
        context.deleteSharedPreferences(FILE_NAME)
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(MASTER_KEY_ALIAS)) ks.deleteEntry(MASTER_KEY_ALIAS)
        }.onFailure { Timber.e(it, "SecureStore: failed to clear the Keystore alias during reset") }
    }

    /**
     * Everything that is not a secret - plain, no Keystore involved. On first use, the non-secret
     * values an earlier version kept in the encrypted file are moved across in one go.
     */
    private val plain: SharedPreferences by lazy {
        context.getSharedPreferences(PLAIN_FILE_NAME, Context.MODE_PRIVATE).also { moveOutOfEncrypted(it) }
    }

    private fun moveOutOfEncrypted(target: SharedPreferences) {
        if (target.getBoolean(KEY_MOVED, false)) return
        runCatching {
            val moved = prefs.all.filterKeys { !isSecret(it) }
            val edit = target.edit()
            moved.forEach { (key, value) ->
                when (value) {
                    is String -> edit.putString(key, value)
                    is Long -> edit.putLong(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Float -> edit.putFloat(key, value)
                }
            }
            edit.putBoolean(KEY_MOVED, true)
            check(edit.commit()) { "writing the plain preferences failed" }
            prefs.edit().apply { moved.keys.forEach { remove(it) } }.apply()
            Timber.i("SecureStore: %d non-secret values moved out of the encrypted file", moved.size)
        }.onFailure { Timber.e(it, "SecureStore: moving non-secret values failed - retried next start") }
    }

    private fun store(key: String): SharedPreferences = if (isSecret(key)) prefs else plain

    fun getString(key: String): String? = safeRead(key) { store(key).getString(key, null) }
    fun putString(key: String, value: String?) = safeWrite(key) { store(key).edit().putString(key, value).apply() }
    fun getLong(key: String, default: Long = 0L): Long = safeRead(key) { store(key).getLong(key, default) } ?: default
    fun putLong(key: String, value: Long) = safeWrite(key) { store(key).edit().putLong(key, value).apply() }
    fun getBoolean(key: String, default: Boolean = false): Boolean =
        safeRead(key) { store(key).getBoolean(key, default) } ?: default
    fun putBoolean(key: String, value: Boolean) = safeWrite(key) { store(key).edit().putBoolean(key, value).apply() }
    fun remove(key: String) = safeWrite(key) {
        if (!isSecret(key)) plain.edit().remove(key).apply()
        prefs.edit().remove(key).apply()
    }
    fun clear() = safeWrite("<all>") {
        plain.edit().clear().apply()
        prefs.edit().clear().apply()
    }

    /**
     * A failed read is reported as "not present" - the same outcome every caller already
     * treats a genuinely-blank value as - rather than crashing whatever screen asked. The
     * Timber.e call is what actually changes: it turns an otherwise-indistinguishable silent
     * loss into a logged, diagnosable event (see the class doc).
     */
    private inline fun <T> safeRead(key: String, block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            Timber.e(e, "SecureStore.get failed for key=$key")
            null
        }

    private inline fun safeWrite(key: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Timber.e(e, "SecureStore.put failed for key=$key")
        }
    }

    /**
     * A stable per-install device identifier, used to attribute a posting to the handheld
     * that made it independently of who was signed in.
     *
     * Generated once and stored rather than read from hardware: ANDROID_ID is scoped per app
     * signing key and resets on factory reset, which is exactly the behaviour wanted here, but
     * reading a hardware serial would require privileged permissions a warehouse fleet MDM
     * should not have to grant.
     */
    fun deviceId(): String = getString(KEY_DEVICE_ID) ?: run {
        @Suppress("HardwareIds")
        val generated = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        ) ?: java.util.UUID.randomUUID().toString()
        // Logged deliberately - a device that already has one of these getting here again
        // means KEY_DEVICE_ID was lost, not that this is genuinely a first launch. See the
        // class doc: this is the concrete symptom that motivated wrapping every read/write.
        Timber.w("SecureStore.deviceId: no cached id found, generating fresh one: $generated")
        putString(KEY_DEVICE_ID, generated)
        generated
    }

    /**
     * The old SQLCipher database key, its backup file and the backup's Keystore key - unused
     * since the database became plain SQLite (1.0.89 converted every device). Removed once.
     */
    fun forgetDatabaseKey() = safeWrite(KEY_DB_PASSPHRASE) {
        if (prefs.contains(KEY_DB_PASSPHRASE)) prefs.edit().remove(KEY_DB_PASSPHRASE).apply()
        java.io.File(context.noBackupFilesDir, "db_key.bak").delete()
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias("d365_mobileops_db_key_backup")) ks.deleteEntry("d365_mobileops_db_key_backup")
    }

    /** The raw `WMSCERT1...` string from device activation - see LicenseActivationScreen. Null
     *  means never activated. */
    fun licenseCertificate(): String? = getString(KEY_LICENSE_CERTIFICATE)
    fun setLicenseCertificate(value: String) = putString(KEY_LICENSE_CERTIFICATE, value)

    /**
     * Millis since epoch of the most recent trusted UTC time this device has seen - always from
     * a server response (`HSMobileOpsGetLicenseResponse.serverUtcNow`), never the device's own
     * clock. See LicenseRepository's clock-rollback check: a device clock (or, in principle, a
     * replayed server response) reporting a time earlier than this is treated as suspicious.
     */
    fun lastTrustedTimeUtcMillis(): Long? = getString(KEY_LICENSE_LAST_TRUSTED_TIME)?.toLongOrNull()
    fun setLastTrustedTimeUtcMillis(value: Long) = putString(KEY_LICENSE_LAST_TRUSTED_TIME, value.toString())

    /** When the license last fully validated (cert + license + registerDevice all succeeded) -
     *  the anchor for LicenseRepository's offline grace period. Null means never validated. */
    fun lastLicenseValidationUtcMillis(): Long? = getString(KEY_LICENSE_LAST_VALIDATION)?.toLongOrNull()
    fun setLastLicenseValidationUtcMillis(value: Long) = putString(KEY_LICENSE_LAST_VALIDATION, value.toString())

    private fun ensureKeystoreKey() {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(MASTER_KEY_ALIAS)) return
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    MASTER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    init {
        runCatching { ensureKeystoreKey() }
            .onFailure { Timber.e(it, "SecureStore: ensureKeystoreKey failed") }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        private const val FILE_NAME = "d365_secure_store"
        private const val MASTER_KEY_ALIAS = "d365_mobileops_master"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        private const val KEY_DB_PASSPHRASE = "db_passphrase"
        private const val PLAIN_FILE_NAME = "d365_prefs"
        private const val KEY_MOVED = "moved_from_encrypted_store"

        /** Keys whose name starts with one of these are kept encrypted. */
        private val SECRET_PREFIXES = listOf(
            "connection_",          // connection profiles: tenant, client id / secret, company, pool
            "offline_sign_in::",    // login accounts for offline sign-in (PIN verifiers)
            "license_",             // licence certificate and trusted-time records
            "debug_worker_session", // debug builds: the restored worker session
        )

        /** Single keys kept encrypted. */
        private val SECRET_KEYS = setOf(
            KEY_DB_PASSPHRASE,
            "active_environment",
            "last_worker_user_name",
            "worker_id",
            "worker_data_area",
            "worker_personnel_number",
        )

        /** Whether [key] holds a connection or login-account secret, and so stays encrypted. */
        fun isSecret(key: String): Boolean = key in SECRET_KEYS || SECRET_PREFIXES.any { key.startsWith(it) }
        const val KEY_ACTIVE_ENVIRONMENT = "active_environment"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_WORKER_ID = "worker_id"
        const val KEY_LAST_FOREGROUND_AT = "last_foreground_at"
        const val KEY_IDLE_LOCK_MS = "idle_lock_ms"

        /** Not per-worker - one flag per install, so a device shared across a shift only ever
         *  shows the first-launch walkthrough once, regardless of who signs into it next. */
        const val KEY_HAS_SEEN_ONBOARDING = "has_seen_onboarding"

        /** Left by the removed device-code sign-in; deleted on startup (ConnectionSettings). */
        val LEGACY_DEVICE_CODE_KEYS = listOf("device_code_refresh_token", "device_code_upn", "device_code_object_id")

        /**
         * Debug-build-only cache of the last real worker sign-in, so a debug reinstall during
         * active testing doesn't require re-typing credentials every time. See the guard on
         * this key's only two call sites, in WorkerSessionRepository - never read or written in
         * a release build. No password is ever in it, same as the in-memory session it mirrors.
         */
        const val KEY_DEBUG_WORKER_SESSION = "debug_worker_session"
        /**
         * The APK's `lastUpdateTime` at the moment [KEY_DEBUG_WORKER_SESSION] was last written.
         * See WorkerSessionRepository.restoreDebugSession for why this exists: a bare
         * close-and-reopen of the same install must require login like production does, while a
         * rebuild-and-reinstall during active testing should not force retyping credentials.
         * Those two are otherwise indistinguishable from a cold start alone.
         */
        const val KEY_DEBUG_SESSION_INSTALL_TIME = "debug_worker_session_install_time"

        /** Offline licensing (certificate-chain design) - see LicenseRepository. */
        const val KEY_LICENSE_CERTIFICATE = "license_certificate"
        const val KEY_LICENSE_LAST_TRUSTED_TIME = "license_last_trusted_time_utc_millis"
        const val KEY_LICENSE_LAST_VALIDATION = "license_last_validation_utc_millis"
    }
}
