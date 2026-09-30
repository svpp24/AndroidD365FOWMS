package com.d365fo.mobileops.counting

import com.d365fo.mobileops.data.counting.CountClaimManager
import com.d365fo.mobileops.domain.counting.ClaimOutcome
import com.d365fo.mobileops.domain.counting.ClaimRefusal
import com.d365fo.mobileops.domain.model.CountRange
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CountClaimManagerTest {

    private val remote = FakeCountTaskRepository()
    private val store = FakeClaimStore()
    private var now = 1_000_000L
    private val manager = CountClaimManager(remote, store).also { it.clock = { now } }

    private val aisleB = CountRange("B", "Aisle B", "B-01", "B-99", lineCount = 40)

    @Test
    fun `claim conflict is reported with the holder and nothing is stored`() = runTest {
        remote.claimAnswer = { _, _ -> ClaimOutcome.Refused(ClaimRefusal.CLAIMED_BY_OTHER, "counter2", null) }

        val outcome = manager.claim("CNT-1", range = null)

        assertThat(outcome).isInstanceOf(ClaimOutcome.Refused::class.java)
        assertThat((outcome as ClaimOutcome.Refused).claimedBy).isEqualTo("counter2")
        assertThat(store.get("CNT-1")).isNull()
    }

    @Test
    fun `location range claim stores the range the export will use`() = runTest {
        val outcome = manager.claim("CNT-1", aisleB)

        assertThat(outcome).isInstanceOf(ClaimOutcome.Granted::class.java)
        assertThat(remote.claimCalls.single().second).isEqualTo(aisleB)
        val stored = store.get("CNT-1")!!
        assertThat(stored.rangeId).isEqualTo("B")
        assertThat(stored.locationFrom).isEqualTo("B-01")
        assertThat(stored.locationTo).isEqualTo("B-99")
        assertThat(stored.lost).isFalse()
    }

    @Test
    fun `range contains only locations inside it`() {
        assertThat(aisleB.contains("B-15")).isTrue()
        assertThat(aisleB.contains("A-15")).isFalse()
        assertThat(aisleB.contains("C-01")).isFalse()
    }

    @Test
    fun `heartbeat refused as expired marks the claim lost`() = runTest {
        manager.claim("CNT-1", null)
        remote.heartbeatAnswer = { ClaimOutcome.Refused(ClaimRefusal.EXPIRED, null, null) }

        now += 61_000
        manager.heartbeatDue()

        val claim = store.get("CNT-1")!!
        assertThat(claim.lost).isTrue()
        assertThat(claim.lostReason).contains("expired")
    }

    @Test
    fun `heartbeat is not sent before its interval`() = runTest {
        manager.claim("CNT-1", null)

        now += 30_000
        manager.heartbeatDue()

        assertThat(remote.heartbeatCalls).isEmpty()
    }

    @Test
    fun `offline before expiry keeps the claim, offline past expiry loses it`() = runTest {
        remote.claimAnswer = { _, range -> FakeCountTaskRepository.granted(range, expiresAt = now + 120_000) }
        manager.claim("CNT-1", null)
        remote.heartbeatAnswer = { ClaimOutcome.Unreachable("offline") }

        now += 61_000
        manager.heartbeatDue()
        assertThat(store.get("CNT-1")!!.lost).isFalse()

        now += 120_000
        manager.heartbeatDue()
        assertThat(store.get("CNT-1")!!.lost).isTrue()
    }

    @Test
    fun `reclaim after expiry claims the same range again`() = runTest {
        manager.claim("CNT-1", aisleB)
        store.markLost("CNT-1", "expired")

        val outcome = manager.reclaim("CNT-1")

        assertThat(outcome).isInstanceOf(ClaimOutcome.Granted::class.java)
        assertThat(remote.claimCalls.last().second?.rangeId).isEqualTo("B")
        assertThat(store.get("CNT-1")!!.lost).isFalse()
    }

    @Test
    fun `release gives the claim back and forgets it`() = runTest {
        manager.claim("CNT-1", null)

        manager.release("CNT-1")

        assertThat(remote.released).containsExactly("CNT-1")
        assertThat(store.get("CNT-1")).isNull()
    }
}
