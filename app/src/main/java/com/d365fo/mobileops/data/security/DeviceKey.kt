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
 */
@Singleton
class DeviceKey @Inject constructor() {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    fun exists(): Boolean = runCatching { keyStore.containsAlias(ALIAS) }.getOrDefault(false)

    /** The public key as base64 X.509 SubjectPublicKeyInfo - made on first use. */
    fun publicKeyBase64(): String {
        ensure()
        return java.util.Base64.getEncoder().encodeToString(keyStore.getCertificate(ALIAS).publicKey.encoded)
    }

    /** SHA256withECDSA over the UTF-8 [text]; base64 of the DER signature. */
    fun sign(text: String): String {
        ensure()
        val key = keyStore.getKey(ALIAS, null) as PrivateKey
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(text.toByteArray(Charsets.UTF_8))
            sign()
        }
        return java.util.Base64.getEncoder().encodeToString(signature)
    }

    /** A deactivated device, or a new setup: the next [publicKeyBase64] makes a new pair. */
    fun delete() {
        runCatching { keyStore.deleteEntry(ALIAS) }
    }

    private fun ensure() {
        if (exists()) return
        try {
            generate(strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            generate(strongBox = false)
        } catch (e: java.security.ProviderException) {
            // Some devices report StrongBox but fail to make a key in it.
            Timber.w(e, "device key: StrongBox failed, using the TEE")
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (strongBox && android.os.Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply { initialize(spec) }.generateKeyPair()
        Timber.i("device key: made (strongBox=%s)", strongBox)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "hsmo_device_key_v1"
    }
}
