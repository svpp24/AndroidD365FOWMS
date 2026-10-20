package com.d365fo.mobileops.data.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** One app registration: the client ID and its secret. */
data class AppCredential(val clientId: String, val clientSecret: String) {
    val isComplete: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    /** The last few characters, for display - enough to tell registrations apart, no more. */
    val shortId: String get() = clientId.trim().takeLast(4)
}

/**
 * Several app registrations shared out across the fleet.
 *
 * D365FO's service protection limits count per user, **per application ID**, per web server:
 * 6,000 requests, 20 minutes of execution time and 52 concurrent requests in any 5 minutes.
 * With one client-credentials registration every handheld counts against the same allowance;
 * with ten, each device uses one of them and about a tenth of the fleet shares each allowance.
 * The registrations can all map to the same D365FO service user - no extra users or licences.
 */
object CredentialPool {

    /**
     * "client-id, secret" per line (a tab or spaces also separate them). Blank lines and lines
     * starting with # are ignored. A client ID is a GUID and an Entra secret has no commas or
     * whitespace, so the first separator splits them unambiguously.
     */
    fun parse(text: String): List<AppCredential> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val parts = line.split(Regex("[,\\s]+"), limit = 2)
            AppCredential(parts[0].trim(), parts.getOrElse(1) { "" }.trim())
        }

    fun format(credentials: List<AppCredential>): String =
        credentials.joinToString("\n") { "${it.clientId}, ${it.clientSecret}" }

    /**
     * The registration a device uses: a stable choice from its device ID, so the same handheld
     * always lands on the same one and the fleet spreads evenly across them.
     */
    fun assignedIndex(deviceId: String, size: Int): Int {
        if (size <= 1) return 0
        // FNV-1a: String.hashCode is too uneven over similar IDs to spread a fleet well.
        var hash = 0x811C9DC5.toInt()
        deviceId.forEach { char ->
            hash = hash xor char.code
            hash *= 0x01000193
        }
        return Math.floorMod(hash, size)
    }
}

/** What the Settings screen shows about this device's registration and throttling. */
data class CredentialStatus(
    /** 1-based, of [total]. */
    val slot: Int = 1,
    val total: Int = 1,
    val shortId: String = "",
    /** Times D365FO answered "too many requests" since the app started. */
    val throttledReplies: Int = 0,
    /** Set while this device has moved off its own registration because that one is throttled. */
    val borrowedFromSlot: Int? = null,
)

/**
 * Which registration this device uses right now: its assigned one, unless D365FO keeps
 * throttling that one - then the next one that is not, until the wait D365FO asked for is over.
 */
@Singleton
class CredentialRouter @Inject constructor() {

    private val throttledUntil = mutableMapOf<String, Long>()
    private var throttledReplies = 0
    private val _status = MutableStateFlow(CredentialStatus())
    val status: StateFlow<CredentialStatus> = _status.asStateFlow()

    @Volatile private var lastUsedClientId: String? = null

    @Synchronized
    fun current(credentials: List<AppCredential>, deviceId: String, now: Long = System.currentTimeMillis()): AppCredential {
        val usable = credentials.filter { it.isComplete }.ifEmpty { credentials }
        val assigned = CredentialPool.assignedIndex(deviceId, usable.size)
        val pick = (usable.indices)
            .map { (assigned + it) % usable.size }
            .firstOrNull { (throttledUntil[usable[it].clientId] ?: 0L) <= now }
            ?: assigned
        val chosen = usable[pick]
        lastUsedClientId = chosen.clientId
        _status.value = CredentialStatus(
            slot = pick + 1,
            total = usable.size,
            shortId = chosen.shortId,
            throttledReplies = throttledReplies,
            borrowedFromSlot = (assigned + 1).takeIf { pick != assigned },
        )
        return chosen
    }

    /** Every "too many requests" answer, retried or not - counted for the Settings screen. */
    @Synchronized
    fun onThrottledReply() {
        throttledReplies++
        _status.value = _status.value.copy(throttledReplies = throttledReplies)
    }

    /**
     * D365FO is still throttling after the retries its own Retry-After allowed: rest the
     * registration that request used for [waitMs], so the next token comes from another one.
     */
    @Synchronized
    fun onStillThrottled(waitMs: Long, now: Long = System.currentTimeMillis()) {
        val clientId = lastUsedClientId ?: return
        throttledUntil[clientId] = now + waitMs.coerceIn(MIN_REST_MS, MAX_REST_MS)
    }

    private companion object {
        const val MIN_REST_MS = 30_000L
        const val MAX_REST_MS = 5 * 60_000L
    }
}
