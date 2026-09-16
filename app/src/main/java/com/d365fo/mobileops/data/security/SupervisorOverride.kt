package com.d365fo.mobileops.data.security

import com.d365fo.mobileops.util.Constants
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

sealed interface OverrideResult {
    data object Granted : OverrideResult
    data class Denied(val attemptsRemaining: Int) : OverrideResult
    data class LockedOut(val retryAfterMs: Long) : OverrideResult
    data object NotConfigured : OverrideResult
}

/**
 * Supervisor PIN gate for soft-validation overrides (tolerance breaches, count variances)
 * and for re-submitting a dead-lettered posting.
 *
 * The PIN is never stored in clear: PBKDF2-HMAC-SHA256 with a per-device random salt, and
 * verification is constant-time. The hash is provisioned during device registration, so a
 * stolen handheld does not yield a usable override credential.
 */
@Singleton
class SupervisorOverride @Inject constructor(
    private val secureStore: SecureStore,
) {
    private var failedAttempts = 0
    private var lockedUntilMs = 0L

    fun isConfigured(): Boolean =
        secureStore.getString(SecureStore.KEY_SUPERVISOR_PIN_HASH) != null

    /** Called once by device registration with the PIN issued by the D365FO supervisor. */
    fun provision(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        secureStore.putString(SecureStore.KEY_SUPERVISOR_PIN_SALT, salt.toHex())
        secureStore.putString(SecureStore.KEY_SUPERVISOR_PIN_HASH, derive(pin, salt).toHex())
    }

    fun verify(pin: String): OverrideResult {
        val now = System.currentTimeMillis()
        if (now < lockedUntilMs) return OverrideResult.LockedOut(lockedUntilMs - now)

        val storedHash = secureStore.getString(SecureStore.KEY_SUPERVISOR_PIN_HASH)
            ?: return OverrideResult.NotConfigured
        val salt = secureStore.getString(SecureStore.KEY_SUPERVISOR_PIN_SALT)?.hexToBytes()
            ?: return OverrideResult.NotConfigured

        return if (MessageDigest.isEqual(derive(pin, salt), storedHash.hexToBytes())) {
            failedAttempts = 0
            OverrideResult.Granted
        } else {
            failedAttempts++
            if (failedAttempts >= Constants.SUPERVISOR_PIN_MAX_ATTEMPTS) {
                lockedUntilMs = now + Constants.SUPERVISOR_PIN_LOCKOUT_MS
                failedAttempts = 0
                OverrideResult.LockedOut(Constants.SUPERVISOR_PIN_LOCKOUT_MS)
            } else {
                OverrideResult.Denied(Constants.SUPERVISOR_PIN_MAX_ATTEMPTS - failedAttempts)
            }
        }
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS))
            .encoded

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val ITERATIONS = 120_000
        const val KEY_LENGTH_BITS = 256
    }
}
