package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.data.local.dao.FlowSessionDao
import com.d365fo.mobileops.data.local.entity.FlowSessionEntity
import com.d365fo.mobileops.data.local.entity.FlowType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One captured line, in the shape it is stored in.
 *
 * A storage mirror of the flows' own captured-line types rather than those types themselves.
 * They live in the presentation layer and change as the screens change; a stored row has to be
 * readable by the *next* version of the app, after an upgrade that happened while a job was
 * half done. Quantities are strings because a BigDecimal round-tripped through a Double is not
 * necessarily the number the operator typed, and a receipt is not the place to discover that.
 */
@Serializable
data class StoredLine(
    val lineNumber: Double,
    val itemId: String,
    val itemName: String = "",
    val quantity: String,
    val unitSymbol: String = "",
    val locationId: String? = null,
    val batchId: String? = null,
    /** Set only when the batch above was new to D365FO at capture time. */
    val batchManufacturingDate: String? = null,
    val batchExpiryDate: String? = null,
    val serialIds: List<String> = emptyList(),
    /** Transfer shipment: already picked in D365FO - kept so a resumed session does not pick it twice. */
    val picked: Boolean = false,
) {
    val quantityValue: BigDecimal get() = runCatching { BigDecimal(quantity) }.getOrDefault(BigDecimal.ZERO)
}

/** An unfinished job, as the home screen shows it. */
data class UnfinishedWork(
    val flowType: FlowType,
    val orderId: String,
    val warehouseId: String?,
    val userName: String?,
    val lineCount: Int,
    val updatedAt: Long,
)

/**
 * Saves and restores work in progress.
 *
 * Writes are best-effort and never propagate: a failure to persist must not abort the scan
 * that triggered it. Losing the ability to resume is bad; refusing the scan in front of the
 * operator because the write failed is worse, and they cannot do anything about either.
 */
@Singleton
class FlowSessionRepository @Inject constructor(
    private val dao: FlowSessionDao,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher,
) {

    val unfinished: Flow<List<UnfinishedWork>> =
        dao.observeAll().map { rows -> rows.map { it.toUnfinished() } }

    suspend fun save(
        flowType: FlowType,
        orderId: String,
        warehouseId: String?,
        userName: String?,
        lines: List<StoredLine>,
    ) = withContext(ioDispatcher) {
        // Nothing banked means nothing to come back to. Storing an empty session would put a
        // job on the home screen that resumes to a blank screen.
        if (orderId.isBlank() || lines.isEmpty()) {
            runCatching { dao.delete(flowType, orderId) }
            return@withContext
        }

        runCatching {
            dao.upsert(
                FlowSessionEntity(
                    flowType = flowType,
                    orderId = orderId,
                    warehouseId = warehouseId,
                    userName = userName,
                    capturedJson = json.encodeToString(ListSerializer(StoredLine.serializer()), lines),
                    lineCount = lines.size,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }.onFailure { Timber.w(it, "could not save %s work on %s", flowType, orderId) }
        Unit
    }

    /** The saved lines for a job, or empty when there are none or they cannot be read. */
    suspend fun restore(flowType: FlowType, orderId: String): List<StoredLine> =
        withContext(ioDispatcher) {
            val row = runCatching { dao.find(flowType, orderId) }.getOrNull()
                ?: return@withContext emptyList()

            runCatching { json.decodeFromString<List<StoredLine>>(row.capturedJson) }
                .getOrElse {
                    // Unreadable rather than absent: an upgrade changed the shape. Drop it, so
                    // the operator gets a clean start instead of a screen that fails to open
                    // every time they touch this order.
                    Timber.w(it, "discarding unreadable saved work on %s", orderId)
                    runCatching { dao.delete(flowType, orderId) }
                    emptyList()
                }
        }

    /** Called once the work has been posted, or when the operator abandons it. */
    suspend fun clear(flowType: FlowType, orderId: String) = withContext(ioDispatcher) {
        runCatching { dao.delete(flowType, orderId) }
        Unit
    }

    private fun FlowSessionEntity.toUnfinished() = UnfinishedWork(
        flowType = flowType,
        orderId = orderId,
        warehouseId = warehouseId,
        userName = userName,
        lineCount = lineCount,
        updatedAt = updatedAt,
    )
}
