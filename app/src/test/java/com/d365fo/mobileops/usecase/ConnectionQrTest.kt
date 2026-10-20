package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.data.security.ConnectionQr
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The same encoding tools/make_connection_qr.py produces. */
class ConnectionQrTest {

    private val json = """
        {"v":1,"label":"PROD","url":"https://contoso.operations.dynamics.com/","tenant":"tenant-guid",
         "company":"bsp","apps":[{"id":"client-1","secret":"secret-1"},{"id":"client-2","secret":"secret-2"}]}
    """.trimIndent()

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun sealWithPin(text: String, pin: String): String {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ConnectionQr.PIN_ITERATIONS, 256)).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return ConnectionQr.PREFIX_PIN + b64(salt + iv + cipher.doFinal(text.toByteArray()))
    }

    @Test
    fun `a plain code sets the connection, every registration and the company`() {
        val result = ConnectionQr.read(ConnectionQr.PREFIX_PLAIN + b64(json.toByteArray()))
        assertThat(result).isInstanceOf(ConnectionQr.Result.Ready::class.java)
        val ready = result as ConnectionQr.Result.Ready
        assertThat(ready.config.resourceUrl).isEqualTo("https://contoso.operations.dynamics.com")
        assertThat(ready.config.clientId).isEqualTo("client-1")
        assertThat(ready.config.credentials.map { it.clientId }).containsExactly("client-1", "client-2").inOrder()
        assertThat(ready.company).isEqualTo("BSP")
    }

    @Test
    fun `a PIN code asks for the PIN, refuses a wrong one and opens with the right one`() {
        val code = sealWithPin(json, "482915")
        assertThat(ConnectionQr.read(code)).isEqualTo(ConnectionQr.Result.NeedsPin)
        assertThat(ConnectionQr.read(code, "000000")).isInstanceOf(ConnectionQr.Result.Invalid::class.java)
        assertThat(ConnectionQr.read(code, "482915")).isInstanceOf(ConnectionQr.Result.Ready::class.java)
    }

    @Test
    fun `the JSON itself, from any QR generator, is read the same way`() {
        assertThat(ConnectionQr.isConnectionQr(json)).isTrue()
        val ready = ConnectionQr.read(json) as ConnectionQr.Result.Ready
        assertThat(ready.config.credentials).hasSize(2)
        assertThat(ready.company).isEqualTo("BSP")
        // The company belongs to the profile, so switching back to it restores it.
        assertThat(ready.config.company).isEqualTo("BSP")
        // Some other JSON QR is not mistaken for a connection.
        assertThat(ConnectionQr.isConnectionQr("""{"order":"PO-1"}""")).isFalse()
    }

    @Test
    fun `reads a code made by tools make_connection_qr py`() {
        // Fake values throughout: ten made-up registrations, PIN 482915, read back off the PNG.
        val code = javaClass.classLoader!!.getResource("connection_qr_fake_pin482915.txt")!!.readText().trim()
        val ready = ConnectionQr.read(code, "482915") as ConnectionQr.Result.Ready
        assertThat(ready.config.label).isEqualTo("PROD")
        assertThat(ready.config.credentials).hasSize(10)
        assertThat(ready.company).isEqualTo("BSP")
    }

    @Test
    fun `other codes and damaged codes are refused, not half-applied`() {
        assertThat(ConnectionQr.isConnectionQr("WMSCERT1.abc.def")).isFalse()
        assertThat(ConnectionQr.read("WMSCERT1.abc.def")).isInstanceOf(ConnectionQr.Result.Invalid::class.java)
        assertThat(ConnectionQr.read(ConnectionQr.PREFIX_PLAIN + "!!not-base64!!"))
            .isInstanceOf(ConnectionQr.Result.Invalid::class.java)
        val noSecret = """{"url":"https://x.dynamics.com","tenant":"t","apps":[{"id":"c","secret":""}]}"""
        assertThat(ConnectionQr.read(ConnectionQr.PREFIX_PLAIN + b64(noSecret.toByteArray())))
            .isInstanceOf(ConnectionQr.Result.Invalid::class.java)
    }
}
