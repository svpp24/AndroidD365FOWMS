package com.d365fo.mobileops.data.security

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wraps the encrypted file's own keys with a key that never leaves the Android Keystore
 * ([KeystoreKeyWrapper]); tests use a software stand-in.
 */
internal interface KeyWrapper {
    fun wrap(plain: ByteArray, aad: ByteArray): ByteArray
    /** Throws when the wrapping key is gone or the data was not wrapped with it. */
    fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray
    /** Deletes the wrapping key - the next [wrap] makes a new one. */
    fun reset()
}

/**
 * A SharedPreferences file whose names and values are both encrypted - what [SecureStore] keeps
 * its secrets in. Replaces androidx.security's EncryptedSharedPreferences (deprecated; performance
 * plan, phase 6) with the same design, without the library and without Tink:
 *
 * - Two random 256-bit keys, made on the device: an AES-GCM key for values and an HMAC-SHA256 key
 *   for names. They are kept in the file wrapped by [KeyWrapper] (an Android Keystore key, which
 *   can't be exported) and unwrapped once per process, so a read or write is ordinary in-process
 *   crypto - not a Keystore call each time.
 * - A name is stored as its HMAC, so the file doesn't show what it holds (some names carry a
 *   worker's user name, e.g. offline_sign_in::<user>).
 * - A value is AES-GCM with a fresh IV, bound to its name: a value copied under another name
 *   doesn't decrypt.
 *
 * When the wrapped keys can't be unwrapped (the Keystore key is gone), the file is cleared and
 * starts again - the "not configured" state every caller already handles - instead of failing on
 * every access.
 */
internal class SecretFile(
    private val file: SharedPreferences,
    private val wrapper: KeyWrapper,
    private val random: SecureRandom = SecureRandom(),
) {
    private class Keys(val value: SecretKey, val name: SecretKey)

    private val keys: Keys by lazy { load() }

    /** Unwraps (or makes) the keys now, so a Keystore failure shows here and not on a first read. */
    fun open(): SecretFile = apply { keys }

    private fun load(): Keys {
        file.getString(KEYS_ENTRY, null)?.let { stored ->
            runCatching { split(wrapper.unwrap(decode(stored), KEYS_AAD)) }
                .onSuccess { return it }
                .onFailure { Timber.e(it, "SecretFile: keys can't be unwrapped - starting a new file") }
            wrapper.reset()
        }
        val raw = ByteArray(KEY_BYTES * 2).also(random::nextBytes)
        val wrapped = wrapper.wrap(raw, KEYS_AAD)
        // commit, not apply: nothing may be written with keys that aren't on disk yet.
        check(file.edit().clear().putString(KEYS_ENTRY, encode(wrapped)).commit()) { "writing the store keys failed" }
        return split(raw)
    }

    private fun split(raw: ByteArray): Keys {
        require(raw.size == KEY_BYTES * 2) { "store keys have the wrong length" }
        return Keys(
            value = SecretKeySpec(raw.copyOfRange(0, KEY_BYTES), "AES"),
            name = SecretKeySpec(raw.copyOfRange(KEY_BYTES, KEY_BYTES * 2), "HmacSHA256"),
        ).also { raw.fill(0) }
    }

    private fun nameOf(key: String): String =
        encode(Mac.getInstance("HmacSHA256").apply { init(keys.name) }.doFinal(key.toByteArray(Charsets.UTF_8)))

    private fun read(key: String): String? {
        val name = nameOf(key)
        val stored = file.getString(name, null) ?: return null
        return runCatching {
            val data = decode(stored)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keys.value, GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES), Charsets.UTF_8)
        }.getOrElse {
            Timber.e(it, "SecretFile: a value can't be decrypted - dropped")
            file.edit().remove(name).apply()
            null
        }
    }

    private fun write(key: String, value: String?) {
        val name = nameOf(key)
        if (value == null) {
            file.edit().remove(name).apply()
            return
        }
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.value, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        file.edit().putString(name, encode(iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))).apply()
    }

    // Each value carries its type, so getLong on a string (or the reverse) reads as absent, the
    // way SharedPreferences' own ClassCastException did through SecureStore's safeRead.
    fun getString(key: String): String? = read(key)?.takeIf { it.startsWith(STRING) }?.substring(1)
    fun putString(key: String, value: String?) = write(key, value?.let { STRING + it })
    fun getLong(key: String): Long? = read(key)?.takeIf { it.startsWith(LONG) }?.substring(1)?.toLongOrNull()
    fun putLong(key: String, value: Long) = write(key, LONG + value)
    fun getBoolean(key: String): Boolean? = read(key)?.takeIf { it.startsWith(BOOLEAN) }?.substring(1)?.toBooleanStrictOrNull()
    fun putBoolean(key: String, value: Boolean) = write(key, BOOLEAN + value)
    fun contains(key: String): Boolean = file.contains(nameOf(key))
    fun remove(key: String) = write(key, null)

    /** Everything but the keys, which stay so the file needn't be set up again. */
    fun clear() {
        open()
        val all = file.all.keys - KEYS_ENTRY
        file.edit().apply { all.forEach(::remove) }.apply()
    }

    private companion object {
        const val KEYS_ENTRY = "__keys__"
        val KEYS_AAD = "d365-secret-file-keys-v1".toByteArray(Charsets.UTF_8)
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val STRING = "s"
        const val LONG = "l"
        const val BOOLEAN = "b"

        fun encode(bytes: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        fun decode(text: String): ByteArray = java.util.Base64.getUrlDecoder().decode(text)
    }
}

/** [KeyWrapper] with an AES-256-GCM key in the Android Keystore - never exportable. */
internal class KeystoreKeyWrapper(private val alias: String) : KeyWrapper {

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existing(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun createKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()

    override fun wrap(plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // The Keystore picks the IV itself (randomized encryption is required by default).
        cipher.init(Cipher.ENCRYPT_MODE, existing() ?: createKey())
        cipher.updateAAD(aad)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray {
        val key = existing() ?: error("Keystore key $alias is missing")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped, 0, 12))
        cipher.updateAAD(aad)
        return cipher.doFinal(wrapped, 12, wrapped.size - 12)
    }

    override fun reset() {
        runCatching { keyStore().apply { if (containsAlias(alias)) deleteEntry(alias) } }
            .onFailure { Timber.e(it, "KeystoreKeyWrapper: deleting %s failed", alias) }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
