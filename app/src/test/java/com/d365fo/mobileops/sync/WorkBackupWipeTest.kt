package com.d365fo.mobileops.sync

import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.data.sync.WorkBackup
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * 15 Nov 2026: a device setup wiped the local database, and the emptied device's next backup
 * deleted the D365FO copy of its unsent work. After a wipe the backup files under a new ID, so
 * the old copy stays in D365FO (offered back after sign-in) and nothing removes it.
 */
class WorkBackupWipeTest {

    private val store = mutableMapOf<String, String?>()
    private val secureStore = mockk<SecureStore>(relaxed = true).also { s ->
        every { s.deviceId() } returns "device-123"
        every { s.getString(any()) } answers { store[firstArg()] }
        every { s.putString(any(), any()) } answers { store[firstArg()] = secondArg() }
        every { s.remove(any()) } answers { store.remove(firstArg<String>()); Unit }
    }

    private fun backup() = WorkBackup(
        database = mockk(relaxed = true),
        api = mockk(relaxed = true),
        errorMapper = mockk(relaxed = true),
        json = kotlinx.serialization.json.Json,
        companyProvider = { null },
        secureStore = secureStore,
        sessions = mockk(relaxed = true),
        moduleSwitches = mockk(relaxed = true),
        cleanupSettings = mockk(relaxed = true),
        scheduler = mockk(relaxed = true),
        scope = TestScope(),
    )

    @Test
    fun `a wipe moves the backup to a new id and forgets the last set sent`() = runTest {
        val backup = backup()
        store["work_backup_hash"] = "abc"
        assertThat(backup.backupDeviceId()).isEqualTo("device-123")

        backup.keepBeforeWipe(saveFirst = false)
        assertThat(backup.backupDeviceId()).isEqualTo("device-123~1")
        assertThat(store["work_backup_hash"]).isNull()

        backup.keepBeforeWipe()
        assertThat(backup.backupDeviceId()).isEqualTo("device-123~2")
    }

    @Test
    fun `the backup id fits D365FO's 64 characters`() = runTest {
        every { secureStore.deviceId() } returns "x".repeat(64)
        val backup = backup()
        repeat(12) { backup.keepBeforeWipe(saveFirst = false) }
        assertThat(backup.backupDeviceId().length).isAtMost(64)
        assertThat(backup.backupDeviceId()).endsWith("~12")
    }
}
