package com.d365fo.mobileops.data.sync

import androidx.room.Dao
import androidx.room.Query
import androidx.room.withTransaction
import com.d365fo.mobileops.BuildConfig
import com.d365fo.mobileops.data.local.D365Database
import com.d365fo.mobileops.data.local.entity.CountExceptionEntity
import com.d365fo.mobileops.data.local.entity.FailedSyncEntity
import com.d365fo.mobileops.data.local.entity.FlowSessionEntity
import com.d365fo.mobileops.data.local.entity.FlowType
import com.d365fo.mobileops.data.local.entity.JournalLineEntity
import com.d365fo.mobileops.data.local.entity.OrderDraftEntity
import com.d365fo.mobileops.data.local.entity.OrderKind
import com.d365fo.mobileops.data.local.entity.SyncOperation
import com.d365fo.mobileops.data.local.entity.SyncQueueEntity
import com.d365fo.mobileops.data.remote.api.DeviceWorkServiceApi
import com.d365fo.mobileops.data.remote.dto.DeviceWorkItemDto
import com.d365fo.mobileops.data.remote.dto.DeviceWorkRequestBody
import com.d365fo.mobileops.data.remote.dto.DeviceWorkServiceRequest
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import timber.log.Timber
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What [WorkBackup] copies: everything [UnsentWork] counts, row by row. */
@Dao
interface WorkBackupDao {
    @Query("SELECT * FROM sync_queue ORDER BY createdAt")
    suspend fun queued(): List<SyncQueueEntity>

    /** Seen or not - a refused posting is unresolved until it is resubmitted or removed. */
    @Query("SELECT * FROM failed_sync ORDER BY failedAt")
    suspend fun refused(): List<FailedSyncEntity>

    @Query("SELECT * FROM flow_session")
    suspend fun jobs(): List<FlowSessionEntity>

    @Query("SELECT * FROM order_draft")
    suspend fun orderDrafts(): List<OrderDraftEntity>

    /** Counted lines D365FO hasn't accepted - the same rule as [UnsentWorkDao.counts]. */
    @Query(
        """
        SELECT l.* FROM invent_journal_line l
        LEFT JOIN count_submission s ON s.journalId = l.journalId
        WHERE (l.countedQuantity IS NOT NULL OR l.markedCounted = 1)
          AND (s.journalId IS NULL
               OR s.state NOT IN ('SUCCEEDED', 'PARTIAL')
               OR COALESCE(l.countedAt, 0) > s.submittedAt)
        """,
    )
    suspend fun unacceptedCounts(): List<JournalLineEntity>

    @Query("SELECT * FROM count_exception WHERE sentAt IS NULL")
    suspend fun unsentExceptions(): List<CountExceptionEntity>

    @Query("SELECT * FROM invent_journal_line WHERE journalLineId = :journalLineId")
    suspend fun journalLine(journalLineId: String): JournalLineEntity?

    @Query("SELECT COUNT(*) FROM invent_journal WHERE journalId = :journalId")
    suspend fun journalOnDevice(journalId: String): Int

    /** Re-emits whenever any of the tables above changes; the value itself means nothing. */
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM sync_queue) + (SELECT COUNT(*) FROM failed_sync)
             + (SELECT COUNT(*) FROM flow_session) + (SELECT COUNT(*) FROM order_draft)
             + (SELECT COUNT(*) FROM count_submission) + (SELECT COUNT(*) FROM count_exception)
             + (SELECT COUNT(*) FROM invent_journal_line WHERE countedQuantity IS NOT NULL OR markedCounted = 1)
        """,
    )
    fun observeChanges(): Flow<Long>
}

/** The kinds of work, as stored in D365FO (HSMobileOpsDeviceWork.Kind). */
object WorkKind {
    const val POSTING = "POSTING"
    const val REFUSED = "REFUSED"
    const val JOB = "JOB"
    const val ORDER_DRAFT = "ORDER_DRAFT"
    const val COUNT = "COUNT"
}

@Serializable
internal data class SavedPosting(
    val operation: String,
    val aggregateId: String,
    val payloadJson: String,
    val workerId: String,
    val createdAt: Long,
    val dedupeKey: String,
)

@Serializable
internal data class SavedRefusal(
    val failureId: String,
    val operation: String,
    val aggregateId: String,
    val payloadJson: String,
    val workerId: String,
    val originallyQueuedAt: Long,
    val failedAt: Long,
    val attempts: Int,
    val errorCode: String? = null,
    val errorMessage: String,
)

@Serializable
internal data class SavedJob(
    val flowType: String,
    val orderId: String,
    val warehouseId: String? = null,
    val userName: String? = null,
    val capturedJson: String,
    val lineCount: Int,
    val updatedAt: Long,
)

@Serializable
internal data class SavedOrderDraft(val kind: String, val userName: String, val json: String, val updatedAt: Long)

@Serializable
internal data class SavedCountLine(
    val journalLineId: String,
    val countedQuantity: String? = null,
    val markedCounted: Boolean = false,
    val countedBy: String? = null,
    val countedAt: Long? = null,
)

@Serializable
internal data class SavedException(
    val clientId: String,
    val itemId: String,
    val locationId: String? = null,
    val quantity: String,
    val batchId: String? = null,
    val serialId: String? = null,
    val note: String? = null,
    val capturedBy: String? = null,
    val capturedAt: Long,
    val configId: String? = null,
    val sizeId: String? = null,
    val colorId: String? = null,
    val styleId: String? = null,
)

@Serializable
internal data class SavedCount(
    val journalId: String,
    val lines: List<SavedCountLine> = emptyList(),
    val exceptions: List<SavedException> = emptyList(),
)

/** What a restore did: [restored] pieces, and why the rest didn't come over. */
data class RestoreResult(val restored: Int, val notes: List<String>) {
    /** Everything came over (or was already here) - the other device's copy can go. */
    val complete: Boolean get() = notes.isEmpty()
}

/**
 * A copy of this device's unsent work in D365FO (HSMobileOpsDeviceWorkSvc), so a broken, lost or
 * reinstalled handheld loses nothing (29 Oct: "how can we recover the stored data").
 *
 * Offline first: work is always captured here, and this device's copy stays the one that counts.
 * Whenever it changes, the whole set is sent once there is a connection - the same set twice is
 * not sent again, and a set D365FO couldn't take is retried every [RETRY_MS]. What D365FO holds
 * for this device is exactly what was last sent, so posted or discarded work drops off it.
 *
 * After sign-in, [savedElsewhere] finds the worker's work saved from another device (or an
 * earlier install of this one) and [restore] brings it here: postings keep their idempotency
 * keys, so one D365FO already has is not posted twice.
 */
@Singleton
class WorkBackup @Inject constructor(
    private val database: D365Database,
    private val api: DeviceWorkServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val json: Json,
    private val companyProvider: CompanyProvider,
    private val secureStore: SecureStore,
    private val sessions: WorkerSessionRepository,
    private val moduleSwitches: ModuleSwitches,
    private val cleanupSettings: DeviceCleanupSettingsStore,
    private val scheduler: SyncScheduler,
    private val scope: CoroutineScope,
) {
    private val dao get() = database.workBackupDao()
    private val lock = Mutex()
    private var started = false

    private val _lastBackupAt = MutableStateFlow(secureStore.getString(KEY_LAST_AT)?.toLongOrNull())

    /** When D365FO last confirmed this device's set; null before the first time. */
    val lastBackupAt: StateFlow<Long?> = _lastBackupAt.asStateFlow()

    private val _waiting = MutableStateFlow(false)

    /** The current set hasn't reached D365FO yet (offline, or it couldn't take it). */
    val waiting: StateFlow<Boolean> = _waiting.asStateFlow()

    @OptIn(FlowPreview::class)
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            dao.observeChanges().debounce(DEBOUNCE_MS).collect {
                runCatching { backUpNow() }.onFailure { Timber.w(it, "work backup failed") }
            }
        }
        scope.launch {
            while (isActive) {
                delay(RETRY_MS)
                if (_waiting.value) runCatching { backUpNow() }.onFailure { Timber.w(it, "work backup retry failed") }
            }
        }
    }

    /** Sends this device's set when it differs from the last one D365FO took. True when D365FO has it. */
    suspend fun backUpNow(): Boolean = lock.withLock {
        val company = company() ?: return@withLock false
        val items = snapshot()
        val itemsJson = json.encodeToString(ListSerializer(DeviceWorkItemDto.serializer()), items)
        val hash = sha256("$company|$itemsJson")
        if (hash == secureStore.getString(KEY_HASH)) {
            _waiting.value = false
            return@withLock true
        }
        val body = request(company, itemsJson = itemsJson, complete = true)
        val result = errorMapper.safeCall { api.syncWork(DeviceWorkServiceRequest(body)) }
        val answer = (result as? NetworkResult.Success)?.data
        answer?.let(cleanupSettings::update)
        if (answer?.success == true) {
            moduleSwitches.update(answer.disabledModules)
            secureStore.putString(KEY_HASH, hash)
            val now = System.currentTimeMillis()
            secureStore.putString(KEY_LAST_AT, now.toString())
            _lastBackupAt.value = now
            _waiting.value = false
            Timber.i("work backup: %d item(s) saved in D365FO", items.size)
            true
        } else {
            _waiting.value = true
            Timber.w("work backup: not saved - %s", (result as? NetworkResult.Error)?.failure?.message ?: answer?.errorCode)
            false
        }
    }

    /** This device's unsent work, one item per posting, refusal, job, draft or journal. */
    suspend fun snapshot(): List<DeviceWorkItemDto> {
        val items = mutableListOf<DeviceWorkItemDto>()
        dao.queued().forEach { e ->
            items += DeviceWorkItemDto(
                key = "posting:${e.dedupeKey}",
                userName = userOf(e.payloadJson) ?: e.workerId,
                warehouseId = warehouseOf(e.payloadJson).orEmpty(),
                kind = WorkKind.POSTING,
                reference = e.aggregateId,
                summary = "${label(e.operation)} ${e.aggregateId}",
                lineCount = linesOf(e.payloadJson),
                capturedMs = e.createdAt,
                payload = json.encodeToString(
                    SavedPosting.serializer(),
                    SavedPosting(e.operation.name, e.aggregateId, e.payloadJson, e.workerId, e.createdAt, e.dedupeKey),
                ),
            )
        }
        dao.refused().forEach { f ->
            items += DeviceWorkItemDto(
                key = "refused:${f.failureId}",
                userName = userOf(f.payloadJson) ?: f.workerId,
                warehouseId = warehouseOf(f.payloadJson).orEmpty(),
                kind = WorkKind.REFUSED,
                reference = f.aggregateId,
                summary = "${label(f.operation)} ${f.aggregateId}: ${f.errorMessage}".take(250),
                lineCount = linesOf(f.payloadJson),
                capturedMs = f.originallyQueuedAt,
                payload = json.encodeToString(
                    SavedRefusal.serializer(),
                    SavedRefusal(
                        f.failureId, f.operation.name, f.aggregateId, f.payloadJson, f.workerId,
                        f.originallyQueuedAt, f.failedAt, f.attempts, f.errorCode, f.errorMessage,
                    ),
                ),
            )
        }
        dao.jobs().forEach { j ->
            items += DeviceWorkItemDto(
                key = "job:${j.flowType.name}:${j.orderId}",
                userName = j.userName.orEmpty(),
                warehouseId = j.warehouseId.orEmpty(),
                kind = WorkKind.JOB,
                reference = j.orderId,
                summary = "${jobLabel(j.flowType)} ${j.orderId}",
                lineCount = j.lineCount,
                capturedMs = j.updatedAt,
                payload = json.encodeToString(
                    SavedJob.serializer(),
                    SavedJob(j.flowType.name, j.orderId, j.warehouseId, j.userName, j.capturedJson, j.lineCount, j.updatedAt),
                ),
            )
        }
        dao.orderDrafts().forEach { d ->
            items += DeviceWorkItemDto(
                key = "draft:${d.kind.name}:${d.userName}",
                userName = d.userName,
                kind = WorkKind.ORDER_DRAFT,
                reference = d.kind.name,
                summary = "${draftLabel(d.kind)} draft",
                lineCount = linesOf(d.json),
                capturedMs = d.updatedAt,
                payload = json.encodeToString(SavedOrderDraft.serializer(), SavedOrderDraft(d.kind.name, d.userName, d.json, d.updatedAt)),
            )
        }
        val lines = dao.unacceptedCounts().groupBy { it.journalId }
        val exceptions = dao.unsentExceptions().groupBy { it.journalId }
        (lines.keys + exceptions.keys).sorted().forEach { journalId ->
            val counted = lines[journalId].orEmpty()
            val extra = exceptions[journalId].orEmpty()
            val latest = counted.maxByOrNull { it.countedAt ?: 0 }
            items += DeviceWorkItemDto(
                key = "count:$journalId",
                userName = latest?.countedBy ?: extra.firstNotNullOfOrNull { it.capturedBy }.orEmpty(),
                warehouseId = counted.firstNotNullOfOrNull { it.warehouseId }.orEmpty(),
                kind = WorkKind.COUNT,
                reference = journalId,
                summary = "Counts $journalId",
                lineCount = counted.size + extra.size,
                capturedMs = (counted.mapNotNull { it.countedAt } + extra.map { it.capturedAt }).maxOrNull() ?: 0,
                payload = json.encodeToString(
                    SavedCount.serializer(),
                    SavedCount(
                        journalId,
                        counted.map { SavedCountLine(it.journalLineId, it.countedQuantity, it.markedCounted, it.countedBy, it.countedAt) },
                        extra.map {
                            SavedException(
                                it.clientId, it.itemId, it.locationId, it.quantity, it.batchId, it.serialId, it.note,
                                it.capturedBy, it.capturedAt, it.configId, it.sizeId, it.colorId, it.styleId,
                            )
                        },
                    ),
                ),
            )
        }
        return items
    }

    /** The worker's work saved from other devices; empty when there is none or D365FO can't say. */
    suspend fun savedElsewhere(userName: String): NetworkResult<List<DeviceWorkItemDto>> {
        val company = company() ?: return NetworkResult.Success(emptyList())
        return when (val result = errorMapper.safeCall { api.listWork(DeviceWorkServiceRequest(request(company, userName))) }) {
            is NetworkResult.Success -> {
                moduleSwitches.update(result.data.disabledModules)
                cleanupSettings.update(result.data)
                NetworkResult.Success(if (result.data.success) result.data.items else emptyList())
            }
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Success(emptyList())
        }
    }

    /**
     * Brings saved work onto this device. Nothing here is overwritten: a job, draft or count
     * already on this device wins. Counts need their journal on the device - [RestoreResult.notes]
     * says which to get first.
     */
    suspend fun restore(items: List<DeviceWorkItemDto>): RestoreResult {
        var restored = 0
        val notes = mutableListOf<String>()
        val deviceId = secureStore.deviceId()
        val now = System.currentTimeMillis()
        database.withTransaction {
            items.forEach { item ->
                val ok = runCatching {
                    when (item.kind) {
                        WorkKind.POSTING -> {
                            val p = json.decodeFromString(SavedPosting.serializer(), item.payload)
                            database.syncQueueDao().enqueue(
                                SyncQueueEntity(
                                    queueId = UUID.randomUUID().toString(),
                                    operation = SyncOperation.valueOf(p.operation),
                                    aggregateId = p.aggregateId,
                                    payloadJson = p.payloadJson,
                                    workerId = p.workerId,
                                    deviceId = deviceId,
                                    createdAt = p.createdAt,
                                    nextAttemptAt = now,
                                    dedupeKey = p.dedupeKey,
                                ),
                            )
                            true
                        }
                        WorkKind.REFUSED -> {
                            val r = json.decodeFromString(SavedRefusal.serializer(), item.payload)
                            database.syncQueueDao().insertFailure(
                                FailedSyncEntity(
                                    failureId = r.failureId,
                                    operation = SyncOperation.valueOf(r.operation),
                                    aggregateId = r.aggregateId,
                                    payloadJson = r.payloadJson,
                                    workerId = r.workerId,
                                    deviceId = deviceId,
                                    originallyQueuedAt = r.originallyQueuedAt,
                                    failedAt = r.failedAt,
                                    attempts = r.attempts,
                                    errorCode = r.errorCode,
                                    errorMessage = r.errorMessage,
                                    serverDetail = null,
                                ),
                            )
                            true
                        }
                        WorkKind.JOB -> {
                            val j = json.decodeFromString(SavedJob.serializer(), item.payload)
                            val type = FlowType.valueOf(j.flowType)
                            if (database.flowSessionDao().find(type, j.orderId) == null) {
                                database.flowSessionDao().upsert(
                                    FlowSessionEntity(type, j.orderId, j.warehouseId, j.userName, j.capturedJson, j.lineCount, j.updatedAt),
                                )
                            }
                            true
                        }
                        WorkKind.ORDER_DRAFT -> {
                            val d = json.decodeFromString(SavedOrderDraft.serializer(), item.payload)
                            val kind = OrderKind.valueOf(d.kind)
                            if (database.orderCreationDao().draft(kind, d.userName) == null) {
                                database.orderCreationDao().saveDraft(OrderDraftEntity(kind, d.userName, d.json, d.updatedAt))
                            }
                            true
                        }
                        WorkKind.COUNT -> {
                            val c = json.decodeFromString(SavedCount.serializer(), item.payload)
                            if (dao.journalOnDevice(c.journalId) == 0) {
                                notes += "Get data for ${c.journalId}, then restore its counts"
                                false
                            } else {
                                restoreCount(c)
                                true
                            }
                        }
                        else -> false
                    }
                }.onFailure { Timber.w(it, "restore: %s could not be read", item.key) }.getOrDefault(false)
                if (ok) restored++ else if (item.kind != WorkKind.COUNT) notes += "${item.summary} could not be restored"
            }
        }
        scheduler.requestImmediatePush()
        return RestoreResult(restored, notes)
    }

    /** Counts already entered on this device win; only lines still uncounted here take the saved count. */
    private suspend fun restoreCount(c: SavedCount) {
        val counting = database.countingDao()
        c.lines.forEach { saved ->
            val line = dao.journalLine(saved.journalLineId) ?: return@forEach
            if (line.countedQuantity != null || line.markedCounted) return@forEach
            val at = saved.countedAt ?: System.currentTimeMillis()
            if (saved.countedQuantity != null) {
                counting.recordCount(saved.journalLineId, saved.countedQuantity, saved.countedBy, at)
            } else if (saved.markedCounted) {
                counting.recordMarked(saved.journalLineId, true, saved.countedBy, at)
            }
        }
        c.exceptions.forEach { e ->
            counting.upsertException(
                CountExceptionEntity(
                    clientId = e.clientId,
                    journalId = c.journalId,
                    itemId = e.itemId,
                    locationId = e.locationId,
                    quantity = e.quantity,
                    batchId = e.batchId,
                    serialId = e.serialId,
                    note = e.note,
                    capturedBy = e.capturedBy,
                    capturedAt = e.capturedAt,
                    configId = e.configId,
                    sizeId = e.sizeId,
                    colorId = e.colorId,
                    styleId = e.styleId,
                ),
            )
        }
    }

    /**
     * Saves the items with these keys in D365FO as Archived, ahead of [DeviceCleanup] removing them
     * here. True only when D365FO has them all.
     */
    suspend fun archive(keys: List<String>): Boolean = lock.withLock {
        val company = company() ?: return@withLock false
        val wanted = keys.toSet()
        val items = snapshot().filter { it.key in wanted }.map { it.copy(archived = true) }
        if (items.size != wanted.size) return@withLock false
        val itemsJson = json.encodeToString(ListSerializer(DeviceWorkItemDto.serializer()), items)
        val result = errorMapper.safeCall { api.syncWork(DeviceWorkServiceRequest(request(company, itemsJson = itemsJson))) }
        val answer = (result as? NetworkResult.Success)?.data
        answer?.let(cleanupSettings::update)
        answer?.success == true && answer.stored == items.size
    }

    /**
     * The other device's copy is done with - restored here, or the worker chose not to keep it.
     * Sends this device's own set at the same time.
     */
    suspend fun releaseFrom(otherDeviceId: String, userName: String): Boolean = lock.withLock {
        val company = company() ?: return@withLock false
        val itemsJson = json.encodeToString(ListSerializer(DeviceWorkItemDto.serializer()), snapshot())
        val body = request(company, userName, itemsJson, complete = true, clearDeviceId = otherDeviceId)
        val result = errorMapper.safeCall { api.syncWork(DeviceWorkServiceRequest(body)) }
        val ok = (result as? NetworkResult.Success)?.data?.success == true
        if (ok) secureStore.putString(KEY_HASH, sha256("$company|$itemsJson"))
        ok
    }

    private fun company(): String? = companyProvider()?.takeIf { it.isNotBlank() }?.lowercase()

    private fun request(
        company: String,
        userName: String? = null,
        itemsJson: String = "",
        complete: Boolean = false,
        clearDeviceId: String = "",
    ) = DeviceWorkRequestBody(
        dataAreaId = company,
        deviceId = secureStore.deviceId(),
        // D365FO wants a name on every call; the device's own work carries its workers' names.
        userName = userName ?: sessions.session.value?.userName?.takeIf { it.isNotBlank() } ?: "device",
        appVersion = BuildConfig.VERSION_NAME,
        deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".take(121),
        itemsJson = itemsJson,
        complete = complete,
        clearDeviceId = clearDeviceId,
    )

    private fun payloadObject(payloadJson: String): JsonObject? =
        runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrNull()

    private fun userOf(payloadJson: String): String? =
        (payloadObject(payloadJson)?.get("userName") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun warehouseOf(payloadJson: String): String? =
        (payloadObject(payloadJson)?.get("warehouseId") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun linesOf(payloadJson: String): Int = (payloadObject(payloadJson)?.get("lines") as? JsonArray)?.size ?: 0

    private fun label(op: SyncOperation): String = when (op) {
        SyncOperation.POST_PRODUCT_RECEIPT -> "Receipt"
        SyncOperation.TRANSFER_ORDER_SHIP -> "Shipment"
        SyncOperation.TRANSFER_ORDER_RECEIVE -> "Transfer receipt"
        SyncOperation.CREATE_PURCHASE_ORDER -> "New PO"
        SyncOperation.CREATE_TRANSFER_ORDER -> "New transfer"
        SyncOperation.CONFIRM_PURCHASE_ORDER -> "PO confirmation"
        SyncOperation.CREATE_PURCHASE_RETURN -> "Return"
        SyncOperation.SEND_FEEDBACK -> "Feedback"
        else -> op.name.lowercase().replace('_', ' ')
    }

    private fun jobLabel(type: FlowType): String = when (type) {
        FlowType.RECEIVING -> "Receiving"
        FlowType.TRANSFER_SHIP -> "Shipping"
        FlowType.TRANSFER_RECEIVE -> "Transfer receiving"
    }

    private fun draftLabel(kind: OrderKind): String = kind.name.lowercase().replaceFirstChar { it.uppercase() }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val KEY_HASH = "work_backup_hash"
        const val KEY_LAST_AT = "work_backup_last_at"
        const val DEBOUNCE_MS = 4_000L
        const val RETRY_MS = 120_000L
    }
}
