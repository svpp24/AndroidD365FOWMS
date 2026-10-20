package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.data.repository.AssignedWarehouse
import com.d365fo.mobileops.data.repository.WorkerSession
import com.d365fo.mobileops.data.security.OfflineSignIn
import com.d365fo.mobileops.data.security.OfflineSignInResult
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class OfflineSignInTest {

    private val day = 24 * 60 * 60 * 1000L
    private var clock = 1_000 * day
    private val values = mutableMapOf<String, String>()
    private val store = OfflineSignIn(
        storage = object : OfflineSignIn.Storage {
            override fun get(key: String) = values[key]
            override fun put(key: String, value: String) { values[key] = value }
            override fun remove(key: String) { values.remove(key) }
        },
        json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true; isLenient = true; encodeDefaults = true; classDiscriminator = "type" },
        now = { clock },
    )

    private val warehouse = AssignedWarehouse("AAC", "ABB", "Abbey store", isDefault = true)
    private fun session(user: String = "Worker1", mustChange: Boolean = false) = WorkerSession(
        userName = user, displayName = "Worker One", personnelNumber = "", isSupervisor = false,
        mustChangePassword = mustChange, warehouses = listOf(warehouse), selected = warehouse,
    )

    @Test
    fun `the right password signs in offline with the cached session`() {
        store.remember("Worker1", "s3cret", session())
        clock += 3 * day

        val result = store.verify(" worker1 ", "s3cret")

        assertThat(result).isInstanceOf(OfflineSignInResult.SignedIn::class.java)
        val signedIn = (result as OfflineSignInResult.SignedIn).session
        assertThat(signedIn.signedInOffline).isTrue()
        assertThat(signedIn.selected).isEqualTo(warehouse)
    }

    @Test
    fun `the password is never stored`() {
        store.remember("Worker1", "s3cret", session())

        assertThat(values.values.none { it.contains("s3cret") }).isTrue()
    }

    @Test
    fun `nobody who never signed in online here can sign in offline`() {
        assertThat(store.verify("Worker1", "s3cret")).isEqualTo(OfflineSignInResult.NotAvailable)
    }

    @Test
    fun `an online sign-in older than the limit no longer works offline`() {
        store.remember("Worker1", "s3cret", session())
        clock += (OfflineSignIn.VALID_DAYS + 1) * day

        assertThat(store.verify("Worker1", "s3cret")).isEqualTo(OfflineSignInResult.Expired(OfflineSignIn.VALID_DAYS))
    }

    @Test
    fun `a clock set back before the last online sign-in is refused`() {
        store.remember("Worker1", "s3cret", session())
        clock -= day

        assertThat(store.verify("Worker1", "s3cret")).isEqualTo(OfflineSignInResult.ClockBehind)
    }

    @Test
    fun `too many wrong passwords clear the offline sign-in`() {
        store.remember("Worker1", "s3cret", session())

        repeat(OfflineSignIn.MAX_FAILED_ATTEMPTS - 1) { i ->
            assertThat(store.verify("Worker1", "guess$i"))
                .isEqualTo(OfflineSignInResult.WrongPassword(OfflineSignIn.MAX_FAILED_ATTEMPTS - 1 - i))
        }
        assertThat(store.verify("Worker1", "last guess")).isEqualTo(OfflineSignInResult.Cleared)
        assertThat(store.verify("Worker1", "s3cret")).isEqualTo(OfflineSignInResult.NotAvailable)
    }

    @Test
    fun `a right password resets the wrong-password count`() {
        store.remember("Worker1", "s3cret", session())
        store.verify("Worker1", "wrong")
        store.verify("Worker1", "s3cret")

        assertThat(store.verify("Worker1", "wrong"))
            .isEqualTo(OfflineSignInResult.WrongPassword(OfflineSignIn.MAX_FAILED_ATTEMPTS - 1))
    }

    @Test
    fun `forget removes it`() {
        store.remember("Worker1", "s3cret", session())
        store.forget("WORKER1")
        assertThat(store.verify("Worker1", "s3cret")).isEqualTo(OfflineSignInResult.NotAvailable)
    }

    @Test
    fun `an account flagged for a password change still signs in offline`() {
        store.remember("Worker1", "s3cret", session(mustChange = true))

        assertThat(store.verify("Worker1", "s3cret")).isInstanceOf(OfflineSignInResult.SignedIn::class.java)
    }

    @Test
    fun `a newer password replaces the old one`() {
        store.remember("Worker1", "old", session())
        store.remember("Worker1", "new", session())

        assertThat(store.verify("Worker1", "new")).isInstanceOf(OfflineSignInResult.SignedIn::class.java)
        assertThat(store.verify("Worker1", "old")).isInstanceOf(OfflineSignInResult.WrongPassword::class.java)
    }

    @Test
    fun `forgetAll clears every worker`() {
        store.remember("Worker1", "a", session("Worker1"))
        store.remember("Worker2", "b", session("Worker2"))
        store.forgetAll()

        assertThat(store.hasAny()).isFalse()
        assertThat(store.verify("Worker2", "b")).isEqualTo(OfflineSignInResult.NotAvailable)
    }
}
