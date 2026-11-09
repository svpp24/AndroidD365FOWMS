package com.d365fo.mobileops.data.sync

import android.content.Context
import android.net.Uri
import com.d365fo.mobileops.data.remote.dto.DeviceWorkItemDto
import com.d365fo.mobileops.data.security.SecureStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class WorkFileContent(
    val version: Int = 1,
    val deviceId: String,
    val createdAt: Long,
    val items: List<DeviceWorkItemDto>,
)

/**
 * Unsent work saved to a file the worker picks (Downloads, a memory card) - for when D365FO can't
 * be reached and the app has to be reinstalled. Offline first means some work only ever exists on
 * the device; this is the one copy of it that survives an uninstall.
 *
 * Only on request, never automatically, and always PIN-protected: the app keeps no backup of its
 * own on purpose (allowBackup=false), and a file of warehouse transactions must not be readable by
 * whoever finds it. Same sealing as a PIN-protected connection code: PBKDF2-HMAC-SHA256 from the
 * PIN, AES-GCM - salt (16) | iv (12) | ciphertext + tag, base64url after [PREFIX].
 */
@Singleton
class WorkFile @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backup: WorkBackup,
    private val json: Json,
    private val secureStore: SecureStore,
) {
    /** Writes this device's unsent work to [uri]. The number of pieces saved. */
    suspend fun export(uri: Uri, pin: String): Int = withContext(Dispatchers.IO) {
        val items = backup.snapshot()
        val content = WorkFileContent(deviceId = secureStore.deviceId(), createdAt = System.currentTimeMillis(), items = items)
        val sealed = seal(json.encodeToString(WorkFileContent.serializer(), content), pin)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(sealed.toByteArray(Charsets.US_ASCII)) }
            ?: error("The file could not be written")
        items.size
    }

    /** The work in the file at [uri], or null for a wrong PIN or a file that isn't one of these. */
    suspend fun read(uri: Uri, pin: String): List<DeviceWorkItemDto>? = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.US_ASCII) }
            ?: return@withContext null
        val plain = open(text, pin) ?: return@withContext null
        runCatching { json.decodeFromString(WorkFileContent.serializer(), plain).items }.getOrNull()
    }

    companion object {
        const val PREFIX = "HSWORK1:"
        const val MIME = "application/octet-stream"
        private const val ITERATIONS = 120_000

        fun suggestedName(now: Long = System.currentTimeMillis()): String =
            "unsent-work-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date(now)) + ".hswork"

        internal fun seal(plain: String, pin: String): String {
            val random = SecureRandom()
            val salt = ByteArray(16).also(random::nextBytes)
            val iv = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(pin, salt), GCMParameterSpec(128, iv))
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            return PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(salt + iv + body)
        }

        internal fun open(text: String, pin: String): String? = runCatching {
            val trimmed = text.trim()
            require(trimmed.startsWith(PREFIX))
            val sealed = java.util.Base64.getUrlDecoder().decode(trimmed.removePrefix(PREFIX))
            require(sealed.size > 16 + 12 + 16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(pin, sealed.copyOfRange(0, 16)), GCMParameterSpec(128, sealed.copyOfRange(16, 28)))
            cipher.doFinal(sealed.copyOfRange(28, sealed.size)).toString(Charsets.UTF_8)
        }.getOrNull()

        private fun key(pin: String, salt: ByteArray) = SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)).encoded,
            "AES",
        )
    }
}
