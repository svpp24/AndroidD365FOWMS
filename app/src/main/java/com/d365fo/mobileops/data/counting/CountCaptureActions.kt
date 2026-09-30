package com.d365fo.mobileops.data.counting

import com.d365fo.mobileops.data.local.dao.CountLine
import com.d365fo.mobileops.data.local.dao.CountingDao
import com.d365fo.mobileops.data.local.entity.CountExceptionEntity
import com.d365fo.mobileops.data.local.entity.CountLocationCheckEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The writes location-first counting and unexpected items need - Room in the app, a map in tests. */
interface CountCaptureStore {
    suspend fun lines(journalId: String): List<CountLine>
    suspend fun recordCount(journalLineId: String, quantity: String, countedBy: String?, at: Long)
    suspend fun saveLocationCheck(check: CountLocationCheckEntity)
    suspend fun saveException(exception: CountExceptionEntity)
    suspend fun deleteUnsentException(clientId: String)
}

@Singleton
class RoomCountCaptureStore @Inject constructor(private val dao: CountingDao) : CountCaptureStore {
    override suspend fun lines(journalId: String) = dao.getCountLines(journalId)
    override suspend fun recordCount(journalLineId: String, quantity: String, countedBy: String?, at: Long) =
        dao.recordCount(journalLineId, quantity, countedBy, at)
    override suspend fun saveLocationCheck(check: CountLocationCheckEntity) = dao.upsertLocationCheck(check)
    override suspend fun saveException(exception: CountExceptionEntity) = dao.upsertException(exception)
    override suspend fun deleteUnsentException(clientId: String) = dao.deleteUnsentException(clientId)
}

sealed interface UnexpectedItemResult {
    data class Saved(val clientId: String) : UnexpectedItemResult
    data class Invalid(val message: String) : UnexpectedItemResult
}

/**
 * Location-first counting and unexpected items. Nothing here decides anything about stock: an
 * empty location is what the counter confirmed, an unexpected item is what they found. The app
 * never adds a journal line - an unexpected item is an exception the server or planner resolves.
 */
@Singleton
class CountCaptureActions @Inject constructor(private val store: CountCaptureStore) {
    internal var clock: () -> Long = System::currentTimeMillis

    /** Lines at [locationId] (case-insensitive), in line order. */
    suspend fun linesAt(journalId: String, locationId: String): List<CountLine> =
        store.lines(journalId).filter { it.locationId.equals(locationId, ignoreCase = true) }

    /**
     * "Location is empty", after the counter confirmed it: every line at [locationId] is counted
     * as zero and the verification is recorded with who and when. Returns how many lines changed.
     */
    suspend fun markLocationEmpty(journalId: String, locationId: String, verifiedBy: String?): Int {
        val lines = linesAt(journalId, locationId)
        if (lines.isEmpty()) return 0
        val now = clock()
        lines.forEach { store.recordCount(it.journalLineId, "0", verifiedBy, now) }
        store.saveLocationCheck(
            CountLocationCheckEntity(
                journalId = journalId,
                locationId = lines.first().locationId ?: locationId,
                verifiedEmpty = true,
                verifiedBy = verifiedBy,
                verifiedAt = now,
            ),
        )
        return lines.size
    }

    /** "Not on this journal": records what was found. Whole pieces, like every other count. */
    suspend fun addUnexpectedItem(
        journalId: String,
        itemId: String,
        locationId: String?,
        quantityText: String,
        batchId: String?,
        serialId: String?,
        note: String?,
        capturedBy: String?,
    ): UnexpectedItemResult {
        val item = itemId.trim()
        if (item.isEmpty()) return UnexpectedItemResult.Invalid("Enter the item number.")
        val digits = quantityText.trim()
        if (digits.isEmpty() || digits.any { !it.isDigit() }) {
            return UnexpectedItemResult.Invalid("Enter the quantity found, in whole pieces.")
        }
        if (!serialId.isNullOrBlank() && digits.trimStart('0') != "1") {
            return UnexpectedItemResult.Invalid("A serial number is one piece - enter 1.")
        }
        val clientId = UUID.randomUUID().toString()
        store.saveException(
            CountExceptionEntity(
                clientId = clientId,
                journalId = journalId,
                itemId = item,
                locationId = locationId?.trim()?.takeIf { it.isNotEmpty() },
                quantity = digits.trimStart('0').ifEmpty { "0" },
                batchId = batchId?.trim()?.takeIf { it.isNotEmpty() },
                serialId = serialId?.trim()?.takeIf { it.isNotEmpty() },
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                capturedBy = capturedBy,
                capturedAt = clock(),
            ),
        )
        return UnexpectedItemResult.Saved(clientId)
    }

    /** Removes an unexpected item that has not been sent yet. */
    suspend fun removeUnexpectedItem(clientId: String) = store.deleteUnsentException(clientId)
}
