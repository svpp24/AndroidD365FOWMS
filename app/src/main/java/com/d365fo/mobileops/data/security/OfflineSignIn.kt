package com.d365fo.mobileops.data.security

import com.d365fo.mobileops.data.repository.WorkerSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

sealed interface OfflineSignInResult {
    data class SignedIn(val session: WorkerSession) : OfflineSignInResult
    /** This worker has not signed in online on this device, or their offline sign-in was cleared. */
    data object NotAvailable : OfflineSignInResult
    data class Expired(val days: Int) : OfflineSignInResult
    data class WrongPassword(val attemptsRemaining: Int) : OfflineSignInResult
    /** Too many wrong passwords: the offline sign-in is gone until the next online one. */
    data object Cleared : OfflineSignInResult
    /** The device clock is earlier than the last online sign-in, so the age can't be trusted. */
    data object ClockBehind : OfflineSignInResult
}

/**
 * Lets a worker who signed in online on this device sign in again while D365FO can't be
 * reached, for [VALID_DAYS] after that online sign-in.
 *
 * The password itself is never kept. A successful online sign-in leaves a PBKDF2-HMAC-SHA256
 * verifier (random salt per worker, [ITERATIONS] rounds) and the session it opened. Both sit in
 * [SecureStore], whose values are encrypted with a hardware-backed Android Keystore key. An
 * offline attempt is checked against the verifier in constant time.
 *
 * What D365FO decides always wins. Each online sign-in replaces the verifier. A refusal (wrong
 * password, deactivated, locked out, no warehouse, license) deletes it. [MAX_FAILED_ATTEMPTS]
 * wrong offline passwords in a row delete it too, so a lost device gives an attacker only a
 * few guesses.
 */
@Singleton
class OfflineSignIn internal constructor(
    private val storage: Storage,
    private val json: Json,
    private val now: () -> Long,
) {
    @Inject constructor(secureStore: SecureStore, json: Json) : this(
        storage = object : Storage {
            override fun get(key: String) = secureStore.getString(key)
            override fun put(key: String, value: String) = secureStore.putString(key, value)
            override fun remove(key: String) = secureStore.remove(key)
        },
        json = json,
        now = System::currentTimeMillis,
    )

    interface Storage {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
    }

    @Serializable
    private data class Ticket(
        val salt: String,
        val hash: String,
        val savedAt: Long,
        val failures: Int = 0,
        val session: WorkerSession,
    )

    /** Called after an online sign-in (or password change) that D365FO accepted. */
    @Synchronized
    fun remember(userName: String, password: String, session: WorkerSession) {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val ticket = Ticket(
            salt = salt.toHex(),
            hash = derive(password, salt).toHex(),
            savedAt = now(),
            session = session.copy(signedInOffline = false),
        )
        val user = normalise(userName)
        storage.put(KEY_PREFIX + user, json.encodeToString(Ticket.serializer(), ticket))
        // Newest first, so the oldest falls off when a shared device has seen many workers.
        val kept = (listOf(user) + users().filter { it != user }).take(MAX_WORKERS)
        users().filter { it !in kept }.forEach { storage.remove(KEY_PREFIX + it) }
        storage.put(KEY_USERS, kept.joinToString("\n"))
        Timber.i("offline sign-in kept for '%s'", user)
    }

    @Synchronized
    fun verify(userName: String, password: String): OfflineSignInResult {
        val user = normalise(userName)
        val raw = storage.get(KEY_PREFIX + user)
        if (raw == null) {
            Timber.i("no offline sign-in stored for '%s' (stored: %d)", user, users().size)
            return OfflineSignInResult.NotAvailable
        }
        val ticket = runCatching { json.decodeFromString(Ticket.serializer(), raw) }
            .onFailure { Timber.w(it, "offline sign-in for '%s' unreadable", user) }
            .getOrNull()
            ?: return OfflineSignInResult.NotAvailable

        val at = now()
        if (at < ticket.savedAt) return OfflineSignInResult.ClockBehind
        if (at - ticket.savedAt > VALID_DAYS * DAY_MS) return OfflineSignInResult.Expired(VALID_DAYS)

        if (MessageDigest.isEqual(derive(password, ticket.salt.hexToBytes()), ticket.hash.hexToBytes())) {
            if (ticket.failures > 0) storage.put(KEY_PREFIX + user, json.encodeToString(Ticket.serializer(), ticket.copy(failures = 0)))
            return OfflineSignInResult.SignedIn(ticket.session.copy(signedInOffline = true))
        }
        val failures = ticket.failures + 1
        if (failures >= MAX_FAILED_ATTEMPTS) {
            Timber.w("offline sign-in for '%s' cleared after %d wrong passwords", user, failures)
            forget(user)
            return OfflineSignInResult.Cleared
        }
        storage.put(KEY_PREFIX + user, json.encodeToString(Ticket.serializer(), ticket.copy(failures = failures)))
        return OfflineSignInResult.WrongPassword(MAX_FAILED_ATTEMPTS - failures)
    }

    @Synchronized
    fun forget(userName: String) {
        val user = normalise(userName)
        storage.remove(KEY_PREFIX + user)
        storage.put(KEY_USERS, users().filter { it != user }.joinToString("\n"))
    }

    /** Every worker's offline sign-in - for a license refusal, which isn't about one worker. */
    @Synchronized
    fun forgetAll() {
        users().forEach { storage.remove(KEY_PREFIX + it) }
        storage.remove(KEY_USERS)
    }

    fun hasAny(): Boolean = users().isNotEmpty()

    private fun users(): List<String> =
        storage.get(KEY_USERS).orEmpty().split('\n').filter { it.isNotBlank() }

    private fun normalise(userName: String) = userName.trim().lowercase(Locale.ROOT)

    private fun derive(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS))
            .encoded

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        const val VALID_DAYS = 7
        const val MAX_FAILED_ATTEMPTS = 5
        const val MAX_WORKERS = 25
        private const val ITERATIONS = 120_000
        private const val KEY_LENGTH_BITS = 256
        private const val SALT_BYTES = 16
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val KEY_PREFIX = "offline_sign_in::"
        private const val KEY_USERS = "offline_sign_in_users"
    }
}
