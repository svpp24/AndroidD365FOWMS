package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.data.security.AppCredential
import com.d365fo.mobileops.data.security.CredentialPool
import com.d365fo.mobileops.data.security.CredentialRouter
import com.d365fo.mobileops.data.sync.PollBackoff
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CredentialPoolTest {

    private val apps = (1..10).map { AppCredential("client-$it", "secret-$it") }

    @Test
    fun `parses one registration per line, ignoring blanks and comments`() {
        val parsed = CredentialPool.parse(
            """
            # fleet registrations
            11111111-aaaa, s3cr~et.one

            22222222-bbbb	s3cr_et-two
            """.trimIndent(),
        )
        assertThat(parsed).containsExactly(
            AppCredential("11111111-aaaa", "s3cr~et.one"),
            AppCredential("22222222-bbbb", "s3cr_et-two"),
        ).inOrder()
        assertThat(CredentialPool.parse(CredentialPool.format(parsed))).isEqualTo(parsed)
    }

    @Test
    fun `a device always gets the same registration, and a fleet spreads across all of them`() {
        assertThat(CredentialPool.assignedIndex("device-42", 10)).isEqualTo(CredentialPool.assignedIndex("device-42", 10))
        val counts = IntArray(10)
        repeat(400) { counts[CredentialPool.assignedIndex("dev-${it}-${it * 7919}", 10)]++ }
        // 400 devices over 10 registrations: every one used, none carrying more than double its share.
        assertThat(counts.all { it in 1..80 }).isTrue()
        assertThat(CredentialPool.assignedIndex("anything", 1)).isEqualTo(0)
    }

    @Test
    fun `a throttled registration is rested and the next one used until the wait is over`() {
        val router = CredentialRouter()
        val own = router.current(apps, "device-1", now = 0)
        router.onStillThrottled(waitMs = 60_000, now = 0)

        val borrowed = router.current(apps, "device-1", now = 1_000)
        assertThat(borrowed).isNotEqualTo(own)
        assertThat(router.status.value.borrowedFromSlot).isNotNull()

        assertThat(router.current(apps, "device-1", now = 61_000)).isEqualTo(own)
        assertThat(router.status.value.borrowedFromSlot).isNull()
    }

    @Test
    fun `status shows this device's slot and only the end of its client ID`() {
        val router = CredentialRouter()
        router.current(apps, "device-7", now = 0)
        val status = router.status.value
        assertThat(status.total).isEqualTo(10)
        assertThat(status.slot).isIn(1..10)
        assertThat(status.shortId.length).isAtMost(4)
    }

    @Test
    fun `poll waits start quick, slow down, and stay inside the budget`() {
        val waits = PollBackoff.delays(budgetMs = 300_000, firstMs = 3_000)
        assertThat(waits.take(5)).containsExactly(3_000L, 3_000L, 3_000L, 3_000L, 3_000L)
        assertThat(waits[5]).isEqualTo(5_000L)
        assertThat(waits.last()).isEqualTo(10_000L)
        assertThat(waits.sum()).isAtMost(300_000L)
        assertThat(waits.size).isLessThan(100) // was 100 checks, every 3 seconds
    }
}
