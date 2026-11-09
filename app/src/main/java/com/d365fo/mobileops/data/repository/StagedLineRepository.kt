package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.data.local.dao.StagedLineDao
import com.d365fo.mobileops.data.local.entity.FlowType
import com.d365fo.mobileops.data.local.entity.StagedLineEntity
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * See the class doc on [StagedLineEntity] for why this exists: AOS never reduces a purchase or
 * transfer order's outstanding quantity for a line that has only been staged (Pending), not yet
 * actually registered - that waits for its batch job (every few minutes) or Process pending.
 * This is the client-side stand-in for the gap,
 * read once per order lookup and netted in-memory from then on - never a per-scan network or DB
 * cost.
 */
@Singleton
class StagedLineRepository @Inject constructor(
    private val dao: StagedLineDao,
) {
    suspend fun stage(
        flowType: FlowType,
        orderId: String,
        queueId: String,
        lines: List<Pair<Double, BigDecimal>>,
        now: Long = System.currentTimeMillis(),
    ) {
        if (lines.isEmpty()) return
        dao.insertAll(
            lines.map { (lineNumber, quantity) ->
                StagedLineEntity(
                    flowType = flowType,
                    orderId = orderId,
                    lineNumber = lineNumber,
                    quantity = quantity.toPlainString(),
                    queueId = queueId,
                    stagedAtEpochMs = now,
                )
            },
        )
    }

    /**
     * lineNumber -> total quantity staged and not yet sent to D365FO. Holds already sent are
     * dropped here too: D365FO's own outstanding figure includes them from then on.
     */
    suspend fun stagedFor(flowType: FlowType, orderId: String): Map<Double, BigDecimal> =
        dao.also { it.clearSent() }.forOrder(flowType, orderId)
            .groupBy { it.lineNumber }
            .mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { total, row -> total + BigDecimal(row.quantity) } }

    /** Called once the Activity screen's queue-status poll confirms nothing for this order is still Pending. */
    suspend fun clearForOrder(flowType: FlowType, orderId: String) = dao.clearForOrder(flowType, orderId)

    /** One posting's lines, once D365FO has registered them (Post immediately). */
    suspend fun clearForQueue(queueId: String) = dao.clearForQueue(queueId)
}
