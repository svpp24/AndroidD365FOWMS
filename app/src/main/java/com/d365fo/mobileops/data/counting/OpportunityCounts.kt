package com.d365fo.mobileops.data.counting

import com.d365fo.mobileops.data.local.dao.CountingDao
import com.d365fo.mobileops.data.local.dao.JournalDao
import com.d365fo.mobileops.data.local.entity.JournalEntity
import com.d365fo.mobileops.data.local.entity.JournalLineEntity
import com.d365fo.mobileops.data.local.entity.JournalStatus
import com.d365fo.mobileops.data.local.entity.JournalType
import com.d365fo.mobileops.data.local.entity.TransferMode
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.domain.FeatureFlags
import com.d365fo.mobileops.domain.counting.CountTaskRepository
import com.d365fo.mobileops.domain.counting.OpportunityTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device-side part of an opportunity count decision: the feature is on, and this
 * warehouse / location / item has not been asked about yet this session. Everything else -
 * whether the spot is eligible at all - is the server's answer.
 */
@Singleton
class OpportunityGate @Inject constructor(
    private val remote: CountTaskRepository,
    private val flags: FeatureFlags,
) {
    private val asked: MutableSet<String> = Collections.synchronizedSet(HashSet())

    suspend fun offer(warehouseId: String, locationId: String?, itemId: String?): OpportunityTask? {
        if (!flags.countOpportunity) return null
        val location = locationId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!asked.add("$warehouseId|${location.uppercase()}|${itemId?.uppercase().orEmpty()}")) return null
        return runCatching { remote.opportunityCount(warehouseId, location, itemId) }.getOrNull()
    }
}

/**
 * Opportunity counts: after a pick or putaway is confirmed, ask the server whether this spot
 * should be counted now. The server decides eligibility; the device only avoids asking twice
 * for the same warehouse / location / item in one app session. Accepting creates the one-line
 * journal the server returned in Room and sends the count through the normal End count job
 * (DIRECT mode). Called from the receiving and transfer flows; does nothing unless
 * [FeatureFlags.countOpportunity] is on.
 */
@Singleton
class OpportunityCounts @Inject constructor(
    private val gate: OpportunityGate,
    private val journalDao: JournalDao,
    private val countingDao: CountingDao,
    private val submissions: CountSubmissions,
    private val workerSession: WorkerSessionRepository,
    private val secureStore: SecureStore,
    private val scope: CoroutineScope,
) {
    private val _prompt = MutableStateFlow<OpportunityTask?>(null)
    val prompt: StateFlow<OpportunityTask?> = _prompt.asStateFlow()

    internal var clock: () -> Long = System::currentTimeMillis

    /** A pick or putaway at [locationId] was just confirmed. Fire and forget. */
    fun afterMovement(locationId: String?, itemId: String?) {
        val warehouse = workerSession.session.value?.selected?.warehouseId ?: return
        scope.launch {
            val task = gate.offer(warehouse, locationId, itemId) ?: return@launch
            if (_prompt.value == null) _prompt.value = task
        }
    }

    fun dismiss() {
        _prompt.value = null
    }

    /** The counter entered [quantity] (whole pieces) for [task]: count it and send it. */
    suspend fun submit(task: OpportunityTask, quantity: String): EndCountResult {
        val entered = quantity.filter { it.isDigit() }
        if (entered.isEmpty()) return EndCountResult.Refused("Enter how many you counted.")
        val digits = entered.trimStart('0').ifEmpty { "0" }

        val now = clock()
        val by = workerSession.session.value?.userName
        if (journalDao.getJournal(task.journalId) == null) {
            journalDao.upsertJournal(
                JournalEntity(
                    journalId = task.journalId,
                    type = JournalType.COUNTING,
                    journalNameId = task.journalNameId,
                    serverJournalNumber = task.journalId,
                    description = task.description ?: "Opportunity count",
                    journalDate = now,
                    siteId = task.siteId,
                    warehouseId = task.warehouseId,
                    workerId = secureStore.getString(SecureStore.KEY_WORKER_ID) ?: "unknown",
                    deviceId = secureStore.deviceId(),
                    status = JournalStatus.DRAFT,
                    countStartedAt = now,
                    transferMode = TransferMode.DIRECT,
                    originType = "OPPORTUNITY",
                ),
            )
        }
        val lineId = "${task.journalId}-${task.lineNumber}"
        journalDao.upsertLines(
            listOf(
                JournalLineEntity(
                    journalLineId = lineId,
                    journalId = task.journalId,
                    lineNumber = task.lineNumber,
                    itemId = task.itemId,
                    itemName = task.itemName,
                    quantity = "",
                    unitId = "",
                    siteId = task.siteId,
                    warehouseId = task.warehouseId,
                    locationId = task.locationId,
                    batchId = task.batchId,
                    serialId = task.serialId,
                    inventDimId = null,
                    capturedAt = now,
                    lineCountStartedAt = now,
                ),
            ),
        )
        countingDao.recordCount(lineId, digits, by, now)

        val result = submissions.endCount(task.journalId)
        if (result is EndCountResult.Queued) _prompt.value = null
        return result
    }
}
