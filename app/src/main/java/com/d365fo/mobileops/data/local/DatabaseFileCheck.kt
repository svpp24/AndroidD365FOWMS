package com.d365fo.mobileops.data.local

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * The app database is plain SQLite (decision, 11 Oct: only the connection settings and the login
 * accounts are encrypted, in SecureStore). 1.0.89 and 1.0.90 converted the earlier SQLCipher
 * file on every device; SQLCipher itself left the app in 1.0.91.
 *
 * Run before Room opens the database. A file that is still encrypted cannot be read any more:
 * it is set aside as `<name>.encrypted` - kept, never deleted, so an administrator can still
 * recover it with an older build - and Room starts a fresh database. This should never happen.
 */
object DatabaseFileCheck {

    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** What [ensureReadable] found, logged by D365MobileApp once the in-app log is up. */
    @Volatile var lastOutcome: String = "not checked"
        private set

    fun ensureReadable(context: Context, name: String) {
        val db = context.getDatabasePath(name)
        // Left over from the 1.0.89 conversion if it was interrupted after the swap.
        File(db.path + ".plain").let { if (it.exists()) sidecars(it).forEach(File::delete) }
        lastOutcome = when {
            !db.exists() -> "new (created plain)"
            isPlain(db) -> "plain SQLite"
            else -> {
                val aside = File(db.path + ".encrypted")
                sidecars(db).zip(sidecars(aside)).forEach { (from, to) -> if (from.exists()) from.renameTo(to) }
                "STILL ENCRYPTED - set aside as ${aside.name}, starting a new database"
            }
        }
        Timber.w("database: %s", lastOutcome)
    }

    fun isPlain(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(SQLITE_HEADER.size)
            input.read(head) == head.size && head.contentEquals(SQLITE_HEADER)
        }
    }.getOrDefault(false)

    private fun sidecars(file: File) = listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal"))
}
