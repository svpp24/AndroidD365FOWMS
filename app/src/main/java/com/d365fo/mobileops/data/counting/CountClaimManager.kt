package com.d365fo.mobileops.data.counting

import com.d365fo.mobileops.data.local.dao.CountingDao
import com.d365fo.mobileops.data.local.entity.CountClaimEntity
import com.d365fo.mobileops.domain.counting.ClaimOutcome
import com.d365fo.mobileops.domain.counting.ClaimRefusal
import com.d365fo.mobileops.domain.counting.CountTaskRepository
import com.d365fo.mobileops.domain.model.CountRange
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** The local side of claims - Room in the app, an in-memory map in tests. */
interface ClaimStore {
    fun observe(): Flow<List<CountClaimEntity>>
    suspend fun get(journalId: String): CountClaimEntity?
    suspend fun active(): List<CountClaimEntity>
    suspend fun save(claim: CountClaimEntity)
    suspend fun recordHeartbeat(journalId: String, expiresAt: Long?, at: Long)
    suspend fun markLost(journalId: String, reason: String)
    suspend fun delete(journalId: String)
}

@Singleton
class RoomClaimStore @Inject constructor(private val dao: CountingDao) : ClaimStore {
    override fun observe() = dao.observeClaims()
    override suspend fun get(journalId: String) = dao.getClaim(journalId)
    override suspend fun active() = dao.activeClaims()
    override suspend fun save(claim: CountClaimEntity) = dao.upsertClaim(claim)
    override suspend fun recordHeartbeat(journalId: String, expiresAt: Long?, at: Long) =
        dao.recordHeartbeat(journalId, expiresAt, at)
    override suspend fun markLost(journalId: String, reason: String) = dao.markClaimLost(journalId, reason)
    override suspend fun delete(journalId: String) = dao.deleteClaim(journalId)
}

/**
 * Takes, keeps alive and gives back this device's claims. The server decides everything - the
 * heartbeat interval it asks for, whether a claim is still valid; the only local judgement is
 * that a claim whose own expiry has passed without a successful heartbeat is treated as lost
 * (submit blocked until claimed again). Counts are never touched by anything here.
 */
@Singleton
class CountClaimManager @Inject constructor(
    private val remote: CountTaskRepository,
    private val store: ClaimStore,
) {
    internal var clock: () -> Long = System::currentTimeMillis

    fun observe(): Flow<List<CountClaimEntity>> = store.observe()

    suspend fun localClaim(journalId: String): CountClaimEntity? = store.get(journalId)

    /** Claims [journalId] (or [range] of it). Only a granted claim is stored. */
    suspend fun claim(journalId: String, range: CountRange?): ClaimOutcome {
        val outcome = remote.claim(journalId, range)
        if (outcome is ClaimOutcome.Granted) {
            val now = clock()
            store.save(
                CountClaimEntity(
                    journalId = journalId,
                    claimToken = outcome.claimToken,
                    rangeId = outcome.rangeId,
                    locationFrom = outcome.locationFrom,
                    locationTo = outcome.locationTo,
                    claimedBy = outcome.claimedBy,
                    claimedAt = now,
                    expiresAt = outcome.expiresAt,
                    heartbeatSeconds = outcome.heartbeatSeconds.takeIf { it > 0 } ?: DEFAULT_HEARTBEAT_SECONDS,
                    lastHeartbeatAt = now,
                ),
            )
        }
        return outcome
    }

    /** Claims a lost claim's journal again, for the same range. Counts stay as they are. */
    suspend fun reclaim(journalId: String): ClaimOutcome {
        val old = store.get(journalId)
        val range = old?.rangeId?.let {
            CountRange(it, it, old.locationFrom, old.locationTo, lineCount = 0)
        }
        return claim(journalId, range)
    }

    /** One pass over every live claim whose heartbeat is due. Safe to call as often as wanted. */
    suspend fun heartbeatDue() {
        val now = clock()
        store.active().forEach { claim ->
            val interval = claim.heartbeatSeconds.coerceAtLeast(MIN_HEARTBEAT_SECONDS) * 1_000L
            val last = claim.lastHeartbeatAt ?: claim.claimedAt
            if (now - last < interval) return@forEach

            when (val outcome = remote.heartbeat(claim.journalId, claim.claimToken)) {
                is ClaimOutcome.Granted -> store.recordHeartbeat(claim.journalId, outcome.expiresAt ?: claim.expiresAt, now)
                is ClaimOutcome.Refused -> if (outcome.reason != ClaimRefusal.NOT_AVAILABLE) {
                    store.markLost(claim.journalId, lostMessage(outcome))
                }
                is ClaimOutcome.Unreachable -> {
                    val expiresAt = claim.expiresAt
                    if (expiresAt != null && now > expiresAt) {
                        store.markLost(claim.journalId, "Your claim expired while this device was offline.")
                    }
                }
            }
        }
    }

    /** Gives the claim back and forgets it locally, whatever the server answers. */
    suspend fun release(journalId: String) {
        val claim = store.get(journalId) ?: return
        remote.release(journalId, claim.claimToken)
        store.delete(journalId)
    }

    private fun lostMessage(outcome: ClaimOutcome.Refused): String = when (outcome.reason) {
        ClaimRefusal.EXPIRED -> "Your claim on this journal expired."
        ClaimRefusal.CLAIMED_BY_OTHER, ClaimRefusal.LOST ->
            outcome.claimedBy?.let { "This journal is now claimed by $it." } ?: "Your claim on this journal was taken over."
        ClaimRefusal.JOURNAL_POSTED -> "This journal has been posted in D365FO."
        ClaimRefusal.JOURNAL_NOT_FOUND -> "This journal no longer exists in D365FO."
        ClaimRefusal.NOT_AVAILABLE, ClaimRefusal.OTHER -> outcome.message ?: "Your claim on this journal is no longer valid."
    }

    companion object {
        /** Used only when the server does not say. TODO: confirm against the X++ claim TTL. */
        const val DEFAULT_HEARTBEAT_SECONDS = 60
        const val MIN_HEARTBEAT_SECONDS = 15
    }
}
