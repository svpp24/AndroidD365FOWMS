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
    suspend fun unsentExceptions(journalId: String): List<CountExceptionEntity>
    suspend fun unsentExceptionById(clientId: String): CountExceptionEntity?
}

@Singleton
class RoomCountCaptureStore @Inject constructor(private val dao: CountingDao) : CountCaptureStore {
    override suspend fun lines(journalId: String) = dao.getCountLines(journalId)
    override suspend fun recordCount(journalLineId: String, quantity: String, countedBy: String?, at: Long) =
        dao.recordCount(journalLineId, quantity, countedBy, at)
    override suspend fun saveLocationCheck(check: CountLocationCheckEntity) = dao.upsertLocationCheck(check)
    override suspend fun saveException(exception: CountExceptionEntity) = dao.upsertException(exception)
    override suspend fun deleteUnsentException(clientId: String) = dao.deleteUnsentException(clientId)
    override suspend fun unsentExceptions(journalId: String) = dao.unsentExceptions(journalId)
    override suspend fun unsentExceptionById(clientId: String) = dao.unsentException(clientId)
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
        /** The variant found; required when [variantRequired] - the item has product dimensions. */
        variant: com.d365fo.mobileops.domain.model.ProductVariant = com.d365fo.mobileops.domain.model.ProductVariant(),
        variantRequired: Boolean = false,
    ): UnexpectedItemResult {
        val item = itemId.trim()
        if (item.isEmpty()) return UnexpectedItemResult.Invalid("Enter the item number.")
        if (variantRequired && variant.isEmpty) {
            return UnexpectedItemResult.Invalid("Choose the variant")
        }
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
                configId = variant.configId?.takeIf { it.isNotBlank() },
                sizeId = variant.sizeId?.takeIf { it.isNotBlank() },
                colorId = variant.colorId?.takeIf { it.isNotBlank() },
                styleId = variant.styleId?.takeIf { it.isNotBlank() },
            ),
        )
        return UnexpectedItemResult.Saved(clientId)
    }

    /**
     * A scan of an item not on the journal (user, 14 Oct: no form - it goes in the list): one
     * more on the unsent row of the same item, variant and location, or a new row of 1.
     * Returns the row's quantity now.
     */
    suspend fun addOneNotOnJournal(
        journalId: String,
        itemId: String,
        locationId: String?,
        variant: com.d365fo.mobileops.domain.model.ProductVariant,
        capturedBy: String?,
    ): String {
        val location = locationId?.trim()?.takeIf { it.isNotEmpty() }
        fun String?.n() = this?.trim().orEmpty()
        val existing = store.unsentExceptions(journalId).firstOrNull {
            it.itemId.equals(itemId, ignoreCase = true) && it.locationId.n().equals(location.n(), ignoreCase = true) &&
                it.batchId.isNullOrBlank() && it.serialId.isNullOrBlank() &&
                it.configId.n() == variant.configId.n() && it.sizeId.n() == variant.sizeId.n() &&
                it.colorId.n() == variant.colorId.n() && it.styleId.n() == variant.styleId.n()
        }
        val quantity = ((existing?.quantity?.toLongOrNull() ?: 0L) + 1).toString()
        store.saveException(
            existing?.copy(quantity = quantity, capturedAt = clock(), capturedBy = capturedBy ?: existing.capturedBy)
                ?: CountExceptionEntity(
                    clientId = UUID.randomUUID().toString(),
                    journalId = journalId,
                    itemId = itemId.trim(),
                    locationId = location,
                    quantity = quantity,
                    batchId = null,
                    serialId = null,
                    note = null,
                    capturedBy = capturedBy,
                    capturedAt = clock(),
                    configId = variant.configId?.takeIf { it.isNotBlank() },
                    sizeId = variant.sizeId?.takeIf { it.isNotBlank() },
                    colorId = variant.colorId?.takeIf { it.isNotBlank() },
                    styleId = variant.styleId?.takeIf { it.isNotBlank() },
                ),
        )
        return quantity
    }

    /** The quantity found of an unsent not-on-the-journal row, as the worker corrected it. */
    suspend fun setUnexpectedQuantity(clientId: String, quantity: String) {
        val row = store.unsentExceptionById(clientId) ?: return
        store.saveException(row.copy(quantity = quantity, capturedAt = clock()))
    }

    /** Removes an unexpected item that has not been sent yet. */
    suspend fun removeUnexpectedItem(clientId: String) = store.deleteUnsentException(clientId)
}
