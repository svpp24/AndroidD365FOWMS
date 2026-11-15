package com.d365fo.mobileops.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import timber.log.Timber
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's own key pair for the token service (docs/device-setup.md): EC P-256 in the Android
 * Keystore - in StrongBox where the device has one - so the private key never leaves the
 * hardware and a copied app data directory is no use on another device. The public key goes to
 * D365FO once, with the setup code; every token request is signed with the private key.
 *
 * One key per D365FO environment ([host]): a device set up in Dev, UAT and Prod has three, so
 * deactivating it in Dev, or setting it up again in UAT, leaves the others working (user, 15 Nov
 * 2026). The single key from before then belongs to the first environment that uses it - the one
 * the device was last set up in.
 */
@Singleton
class DeviceKey @Inject constructor(private val secureStore: SecureStore) {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    fun exists(host: String): Boolean = runCatching { keyStore.containsAlias(alias(host)) }.getOrDefault(false)

    /** The public key as base64 X.509 SubjectPublicKeyInfo - made on first use. */
    fun publicKeyBase64(host: String): String {
        val alias = ensure(host)
        return java.util.Base64.getEncoder().encodeToString(keyStore.getCertificate(alias).publicKey.encoded)
    }

    /** SHA256withECDSA over the UTF-8 [text]; base64 of the DER signature. */
    fun sign(host: String, text: String): String {
        val alias = ensure(host)
        val key = keyStore.getKey(alias, null) as PrivateKey
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(text.toByteArray(Charsets.UTF_8))
            sign()
        }
        return java.util.Base64.getEncoder().encodeToString(signature)
    }

    /** Deactivated in, or set up again in, this environment: the next [publicKeyBase64] makes a new pair. */
    fun delete(host: String) {
        val alias = alias(host)
        runCatching { keyStore.deleteEntry(alias) }
        if (alias == LEGACY_ALIAS) secureStore.remove(KEY_LEGACY_HOST)
    }

    private fun alias(host: String): String {
        val h = host.trim().lowercase()
        val legacy = runCatching { keyStore.containsAlias(LEGACY_ALIAS) }.getOrDefault(false)
        if (legacy) {
            val owner = secureStore.getString(KEY_LEGACY_HOST)
            if (owner == null && h.isNotEmpty()) {
                secureStore.putString(KEY_LEGACY_HOST, h)
                return LEGACY_ALIAS
            }
            if (owner == h) return LEGACY_ALIAS
        }
        return "$LEGACY_ALIAS@$h"
    }

    private fun ensure(host: String): String {
        val alias = alias(host)
        if (runCatching { keyStore.containsAlias(alias) }.getOrDefault(false)) return alias
        try {
            generate(alias, strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            generate(alias, strongBox = false)
        } catch (e: java.security.ProviderException) {
            // Some devices report StrongBox but fail to make a key in it.
            Timber.w(e, "device key: StrongBox failed, using the TEE")
            generate(alias, strongBox = false)
        }
        return alias
    }

    private fun generate(alias: String, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (strongBox && android.os.Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply { initialize(spec) }.generateKeyPair()
        Timber.i("device key: made %s (strongBox=%s)", alias, strongBox)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val LEGACY_ALIAS = "hsmo_device_key_v1"
        const val KEY_LEGACY_HOST = "device_key_v1_host"
    }
}
