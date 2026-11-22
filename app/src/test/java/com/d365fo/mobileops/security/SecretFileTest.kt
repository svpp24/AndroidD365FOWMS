package com.d365fo.mobileops.security

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.d365fo.mobileops.data.security.KeyWrapper
import com.d365fo.mobileops.data.security.SecretFile
import com.d365fo.mobileops.data.security.SecureStore
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The store that replaced EncryptedSharedPreferences (performance plan, phase 6). The Android
 * Keystore isn't there under Robolectric, so [FakeWrapper] stands in for it with a software key;
 * everything else is the real code on a real SharedPreferences file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], application = Application::class)
class SecretFileTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("secret-file-test", Context.MODE_PRIVATE)

    /** AES-GCM with a key held here; [reset] loses it, as a cleared Keystore would. */
    private class FakeWrapper : KeyWrapper {
        private var key = newKey()
        var resets = 0
        private fun newKey() = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")
        override fun wrap(plain: ByteArray, aad: ByteArray): ByteArray {
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)); updateAAD(aad) }
            return iv + c.doFinal(plain)
        }
        override fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped, 0, 12)); updateAAD(aad) }
            return c.doFinal(wrapped, 12, wrapped.size - 12)
        }
        override fun reset() { key = newKey(); resets++ }
        fun loseKey() { key = newKey() }
    }

    @After
    fun tearDown() {
        listOf("secret-file-test", "d365_secrets", "d365_prefs", "d365_secure_store").forEach(context::deleteSharedPreferences)
    }

    @Test
    fun `values come back by type and a null string removes`() {
        val file = SecretFile(prefs, FakeWrapper())
        file.putString("connection_secret", "s3cret")
        file.putLong("license_time", 42L)
        file.putBoolean("connection_flag", true)

        assertThat(file.getString("connection_secret")).isEqualTo("s3cret")
        assertThat(file.getLong("license_time")).isEqualTo(42L)
        assertThat(file.getBoolean("connection_flag")).isTrue()
        // The wrong type reads as absent, as SharedPreferences' ClassCastException did.
        assertThat(file.getLong("connection_secret")).isNull()
        assertThat(file.getString("license_time")).isNull()
        assertThat(file.getString("never_set")).isNull()

        file.putString("connection_secret", null)
        assertThat(file.getString("connection_secret")).isNull()
        assertThat(file.contains("connection_secret")).isFalse()
        file.putString("empty", "")
        assertThat(file.getString("empty")).isEmpty()
    }

    @Test
    fun `neither names nor values are on disk in the clear`() {
        val file = SecretFile(prefs, FakeWrapper())
        file.putString("offline_sign_in::alice", "verifier-for-alice")

        val raw = prefs.all
        assertThat(raw.keys.none { "alice" in it || "offline" in it }).isTrue()
        assertThat(raw.values.none { "verifier" in it.toString() }).isTrue()
    }

    @Test
    fun `the same keys open the file again in a new process`() {
        val wrapper = FakeWrapper()
        SecretFile(prefs, wrapper).putString("connection_tenant", "contoso")

        assertThat(SecretFile(prefs, wrapper).getString("connection_tenant")).isEqualTo("contoso")
        assertThat(wrapper.resets).isEqualTo(0)
    }

    @Test
    fun `a value moved under another name does not decrypt`() {
        val file = SecretFile(prefs, FakeWrapper())
        file.putString("a", "value-of-a")
        file.putString("b", "value-of-b")
        val before = prefs.all.filterKeys { it != "__keys__" }
        val (nameA, nameB) = before.keys.toList()
        prefs.edit().putString(nameA, before[nameB] as String).commit()

        // One of the two now holds the other's ciphertext: it reads as absent and is dropped.
        val read = listOf(file.getString("a"), file.getString("b"))
        assertThat(read.count { it == null }).isEqualTo(1)
    }

    @Test
    fun `a lost Keystore key starts the file again instead of failing every time`() {
        val wrapper = FakeWrapper()
        SecretFile(prefs, wrapper).putString("connection_tenant", "contoso")
        wrapper.loseKey()

        val reopened = SecretFile(prefs, wrapper)
        assertThat(reopened.getString("connection_tenant")).isNull()
        assertThat(wrapper.resets).isEqualTo(1)
        reopened.putString("connection_tenant", "fabrikam")
        assertThat(SecretFile(prefs, wrapper).getString("connection_tenant")).isEqualTo("fabrikam")
    }

    @Test
    fun `clear keeps the keys and drops the values`() {
        val wrapper = FakeWrapper()
        val file = SecretFile(prefs, wrapper)
        file.putString("x", "1")
        file.clear()
        assertThat(file.getString("x")).isNull()
        assertThat(prefs.all.keys).containsExactly("__keys__")
        file.putString("x", "2")
        assertThat(SecretFile(prefs, wrapper).getString("x")).isEqualTo("2")
    }

    @Test
    fun `SecureStore keeps secrets encrypted, the rest plain, and removes the old store`() {
        context.getSharedPreferences("d365_secure_store", Context.MODE_PRIVATE).edit().putString("old", "x").commit()
        val store = SecureStore(context, FakeWrapper())

        store.putString("connection_tenant_id", "contoso")
        store.putString("worker_id", "W1")
        store.putString(SecureStore.KEY_DEVICE_ID, "device-1")
        store.putLong(SecureStore.KEY_IDLE_LOCK_MS, 300_000L)
        store.putBoolean(SecureStore.KEY_HAS_SEEN_ONBOARDING, true)

        assertThat(store.getString("connection_tenant_id")).isEqualTo("contoso")
        assertThat(store.getString("worker_id")).isEqualTo("W1")
        assertThat(store.getString(SecureStore.KEY_DEVICE_ID)).isEqualTo("device-1")
        assertThat(store.getLong(SecureStore.KEY_IDLE_LOCK_MS)).isEqualTo(300_000L)
        assertThat(store.getBoolean(SecureStore.KEY_HAS_SEEN_ONBOARDING)).isTrue()

        val plain = context.getSharedPreferences("d365_prefs", Context.MODE_PRIVATE).all
        assertThat(plain).containsEntry(SecureStore.KEY_DEVICE_ID, "device-1")
        assertThat(plain.keys).containsNoneOf("connection_tenant_id", "worker_id")
        val secret = context.getSharedPreferences("d365_secrets", Context.MODE_PRIVATE).all
        assertThat(secret.values.none { it.toString().contains("contoso") }).isTrue()
        assertThat(context.getSharedPreferences("d365_secure_store", Context.MODE_PRIVATE).all).isEmpty()

        store.remove("connection_tenant_id")
        assertThat(store.getString("connection_tenant_id")).isNull()
        store.clear()
        assertThat(store.getString("worker_id")).isNull()
        assertThat(store.getString(SecureStore.KEY_DEVICE_ID)).isNull()
    }
}
