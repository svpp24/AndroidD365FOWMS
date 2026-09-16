package com.d365fo.mobileops.data.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything sensitive on disk goes through here: the SQLCipher passphrase, cached tokens,
 * the device registration record and the supervisor PIN hash.
 *
 * The master key lives in the Android Keystore (hardware-backed where available) and is
 * never exportable; EncryptedSharedPreferences holds the ciphertext.
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
    }

    fun getString(key: String): String? = prefs.getString(key, null)
    fun putString(key: String, value: String?) = prefs.edit().putString(key, value).apply()
    fun getLong(key: String, default: Long = 0L): Long = prefs.getLong(key, default)
    fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    fun remove(key: String) = prefs.edit().remove(key).apply()
    fun clear() = prefs.edit().clear().apply()

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
        putString(KEY_DEVICE_ID, generated)
        generated
    }

    /**
     * SQLCipher passphrase. Generated once from [SecureRandom], stored encrypted, and never
     * derived from anything guessable such as the device ID.
     */
    fun databasePassphrase(): ByteArray {
        prefs.getString(KEY_DB_PASSPHRASE, null)?.let { return it.hexToBytes() }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_DB_PASSPHRASE, fresh.toHex()).apply()
        return fresh
    }

    /** Called on environment switch and on "reset local data" recovery. */
    fun rotateDatabasePassphrase() = remove(KEY_DB_PASSPHRASE)

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

    init { runCatching { ensureKeystoreKey() } }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        private const val FILE_NAME = "d365_secure_store"
        private const val MASTER_KEY_ALIAS = "d365_mobileops_master"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        private const val KEY_DB_PASSPHRASE = "db_passphrase"
        const val KEY_ACTIVE_ENVIRONMENT = "active_environment"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_REGISTRATION_STATE = "device_registration_state"
        const val KEY_WORKER_ID = "worker_id"
        const val KEY_SUPERVISOR_PIN_HASH = "supervisor_pin_hash"
        const val KEY_SUPERVISOR_PIN_SALT = "supervisor_pin_salt"
        const val KEY_LAST_FOREGROUND_AT = "last_foreground_at"
        const val KEY_IDLE_LOCK_MS = "idle_lock_ms"

        /** See DeviceCodeTokenProvider - the whole point of device-code auth is no stored secret. */
        const val KEY_DEVICE_CODE_REFRESH_TOKEN = "device_code_refresh_token"
        const val KEY_DEVICE_CODE_UPN = "device_code_upn"
        const val KEY_DEVICE_CODE_OBJECT_ID = "device_code_object_id"

        /**
         * Debug-build-only cache of the last real worker sign-in, so a debug reinstall during
         * active testing doesn't require re-typing credentials every time. See the guard on
         * this key's only two call sites, in WorkerSessionRepository - never read or written in
         * a release build. No password is ever in it, same as the in-memory session it mirrors.
         */
        const val KEY_DEBUG_WORKER_SESSION = "debug_worker_session"
    }
}
