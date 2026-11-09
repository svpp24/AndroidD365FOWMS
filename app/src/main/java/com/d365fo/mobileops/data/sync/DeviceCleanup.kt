package com.d365fo.mobileops.data.sync

import androidx.room.Dao
import androidx.room.Query
import androidx.room.withTransaction
import com.d365fo.mobileops.data.local.D365Database
import com.d365fo.mobileops.data.remote.dto.DeviceWorkServiceResponse
import com.d365fo.mobileops.data.security.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** What [DeviceCleanup] removes, and the order it is safe in. */
@Dao
interface DeviceCleanupDao {
    /**
     * Counting journals finished before [before]: submitted and accepted (or posted), with nothing
     * counted since and no unexpected item still unsent.
     */
    @Query(
        """
        SELECT j.journalId FROM invent_journal j
        LEFT JOIN count_submission s ON s.journalId = j.journalId
        WHERE j.type = 'COUNTING'
          AND j.status IN ('SUBMITTED', 'POSTED')
          AND (s.journalId IS NULL OR s.state IN ('SUCCEEDED', 'PARTIAL'))
          AND COALESCE(s.submittedAt, j.postedAt, j.journalDate) < :before
          AND NOT EXISTS (SELECT 1 FROM invent_journal_line l WHERE l.journalId = j.journalId
                          AND (l.countedQuantity IS NOT NULL OR l.markedCounted = 1)
                          AND COALESCE(l.countedAt, 0) > COALESCE(s.submittedAt, j.postedAt, 0))
          AND NOT EXISTS (SELECT 1 FROM count_exception e WHERE e.journalId = j.journalId AND e.sentAt IS NULL)
        """,
    )
    suspend fun finishedJournals(before: Long): List<String>

    /** Lines, references, attempts, exceptions and location checks go with it (foreign keys). */
    @Query("DELETE FROM invent_journal WHERE journalId = :journalId")
    suspend fun deleteJournal(journalId: String)

    @Query("DELETE FROM count_submission WHERE journalId = :journalId")
    suspend fun deleteSubmission(journalId: String)

    @Query("DELETE FROM count_claim WHERE journalId = :journalId")
    suspend fun deleteClaims(journalId: String)

    /** Orders D365FO has answered - created or refused. One still waiting stays. */
    @Query("DELETE FROM created_order WHERE status != 'WAITING' AND updatedAt < :before")
    suspend fun deleteOrderHistory(before: Long): Int

    @Query("SELECT flowType || ':' || orderId FROM flow_session WHERE updatedAt < :before")
    suspend fun oldJobs(before: Long): List<String>

    @Query("DELETE FROM flow_session WHERE flowType || ':' || orderId = :key")
    suspend fun deleteJob(key: String)

    @Query("SELECT failureId FROM failed_sync WHERE failedAt < :before")
    suspend fun oldRefusals(before: Long): List<String>

    @Query("DELETE FROM failed_sync WHERE failureId = :failureId")
    suspend fun deleteRefusal(failureId: String)
}

/** The handheld's clean-up days, from Mobile clean-up in D365FO. 0: never. */
@Serializable
data class DeviceCleanupSettings(
    val enabled: Boolean = true,
    val finishedJournalDays: Int = 7,
    val orderHistoryDays: Int = 30,
    val oldWorkDays: Int = 30,
)

/**
 * Keeps the latest [DeviceCleanupSettings] D365FO sent - every HSMobileOpsDeviceWorkSvc answer
 * carries them. Offline first: the last ones are used until D365FO answers again; before the
 * first answer, the defaults.
 */
@Singleton
class DeviceCleanupSettingsStore @Inject constructor(
    private val secureStore: SecureStore,
    private val json: Json,
) {
    @Volatile
    var current: DeviceCleanupSettings = load()
        private set

    fun update(answer: DeviceWorkServiceResponse) {
        if (!answer.success) return
        val settings = DeviceCleanupSettings(
            enabled = answer.deviceCleanup,
            finishedJournalDays = answer.finishedJournalDays,
            orderHistoryDays = answer.orderHistoryDays,
            oldWorkDays = answer.oldWorkDays,
        )
        if (settings == current) return
        current = settings
        secureStore.putString(KEY, json.encodeToString(DeviceCleanupSettings.serializer(), settings))
    }

    private fun load(): DeviceCleanupSettings = runCatching {
        secureStore.getString(KEY)?.let { json.decodeFromString(DeviceCleanupSettings.serializer(), it) }
    }.getOrNull() ?: DeviceCleanupSettings()

    private companion object {
        const val KEY = "device_cleanup_settings"
    }
}

/**
 * Once a day, removes this device's old records by the days set on Mobile clean-up in D365FO
 * (29 Oct: "we should be able to control from F&O"): finished counting journals, the created
 * orders list, and unfinished jobs and refused postings nobody has touched.
 *
 * Nothing unsent is lost: old jobs and refusals are first saved in D365FO as Archived (Mobile
 * unsent work) and only removed here once D365FO has them - offline, they simply wait.
 */
@Singleton
class DeviceCleanup @Inject constructor(
    private val database: D365Database,
    private val settings: DeviceCleanupSettingsStore,
    private val backup: WorkBackup,
    private val secureStore: SecureStore,
    private val scope: CoroutineScope,
) {
    private val dao get() = database.deviceCleanupDao()
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            delay(FIRST_RUN_MS)
            while (isActive) {
                val last = secureStore.getString(KEY_LAST_RUN)?.toLongOrNull() ?: 0
                if (System.currentTimeMillis() - last >= DAY_MS) {
                    runCatching { runOnce() }
                        .onSuccess { secureStore.putString(KEY_LAST_RUN, System.currentTimeMillis().toString()) }
                        .onFailure { Timber.w(it, "device clean-up failed") }
                }
                delay(CHECK_MS)
            }
        }
    }

    /** One pass with the current settings. What it removed, for the log. */
    suspend fun runOnce(now: Long = System.currentTimeMillis()): String {
        val s = settings.current
        if (!s.enabled) return "off"
        var journals = 0
        var orders = 0
        var old = 0
        if (s.finishedJournalDays > 0) {
            val ids = dao.finishedJournals(now - s.finishedJournalDays * DAY_MS)
            database.withTransaction {
                ids.forEach { id ->
                    dao.deleteSubmission(id)
                    dao.deleteClaims(id)
                    dao.deleteJournal(id)
                }
            }
            journals = ids.size
        }
        if (s.orderHistoryDays > 0) {
            orders = dao.deleteOrderHistory(now - s.orderHistoryDays * DAY_MS)
        }
        if (s.oldWorkDays > 0) {
            val before = now - s.oldWorkDays * DAY_MS
            val jobs = dao.oldJobs(before)
            val refusals = dao.oldRefusals(before)
            if (jobs.isNotEmpty() || refusals.isNotEmpty()) {
                val keys = jobs.map { "job:$it" } + refusals.map { "refused:$it" }
                // Kept in D365FO first; removed here only once it has them.
                if (backup.archive(keys)) {
                    database.withTransaction {
                        jobs.forEach { dao.deleteJob(it) }
                        refusals.forEach { dao.deleteRefusal(it) }
                    }
                    old = keys.size
                }
            }
        }
        val result = "removed $journals finished journal(s), $orders order(s), $old old unfinished item(s)"
        Timber.w("device clean-up: %s", result)
        return result
    }

    private companion object {
        const val KEY_LAST_RUN = "device_cleanup_last_run"
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val FIRST_RUN_MS = 2 * 60 * 1000L
        const val CHECK_MS = 6 * 60 * 60 * 1000L
    }
}
