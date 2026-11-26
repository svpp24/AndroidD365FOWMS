package com.d365fo.mobileops.domain.countcontrol

import com.d365fo.mobileops.util.NetworkResult

/**
 * Count control for the store supervisor (spec section 4, 24 Nov 2026): the store journal of the
 * signed-in store, its bins counted twice, counters, blind counts and lines with variance;
 * deciding a bin, hold, release and post. D365FO checks on every call that the user is a cycle
 * count supervisor and that the store is their default warehouse.
 */
interface CountControlRepository {
    suspend fun overview(): NetworkResult<CountControlOverview>
    suspend fun countedTwice(decided: Boolean): NetworkResult<CountedTwice>
    suspend fun counters(): NetworkResult<List<StoreCounter>>
    suspend fun blindCounts(): NetworkResult<List<StoreBlindCount>>
    suspend fun varianceLines(): NetworkResult<List<VarianceRow>>
    /** [lineId] is the kept count for [BinDecision.KEEP]; any count of the bin otherwise. */
    suspend fun decide(lineId: String, decision: BinDecision): NetworkResult<String>
    suspend fun hold(hold: Boolean): NetworkResult<String>
    suspend fun post(): NetworkResult<String>
}

enum class BinDecision { KEEP, SUM, REJECT }

data class CountControlOverview(
    val storeId: String,
    val storeName: String,
    val binsCountedTwice: Int,
    val countersCounting: Int,
    val blindCountsToday: Int,
    val linesWithVariance: Int,
    /** Null when the store has no open store journal. */
    val journal: StoreJournalSummary?,
)

data class StoreJournalSummary(
    val journalId: String,
    /** HSMobileOpsCountStoreJournalStatus symbol: Counting, WaitingForSupervisor, FailedToCreate, OnHold, ReadyToPost, Posted. */
    val status: String,
    val statusLabel: String,
    val blindCounts: Int,
    val lines: Int,
    val countedTwice: Int,
    val onHold: Boolean,
    val heldBy: String,
    val postMessage: String,
)

data class CountedTwice(val toDecide: Int, val decided: Int, val bins: List<CountedTwiceBin>)

data class CountedTwiceBin(
    val lineId: String,
    val locationId: String,
    val binId: String,
    val itemId: String,
    val itemName: String,
    val onHand: Double,
    /** HSMobileOpsCountDuplicateDecision symbol: None, KeepThis, Sum, Reject. */
    val decision: String,
    val counts: List<BinCount>,
) {
    val title: String get() = if (binId.isBlank()) locationId else "$locationId · Bin $binId"
    val total: Double get() = counts.sumOf { it.qty }
}

data class BinCount(
    val lineId: String,
    val counter: String,
    val counterName: String,
    val masterId: String,
    /** ISO UTC, e.g. 2026-11-24T08:14:00Z; blank when unknown. */
    val countedAt: String,
    val qty: Double,
    val included: Boolean,
)

data class StoreCounter(
    val userName: String,
    val name: String,
    val masterId: String,
    val deviceId: String,
    val startedAt: String,
    val lines: Int,
    val lastScanAt: String,
    val ended: Boolean,
)

data class StoreBlindCount(
    val masterId: String,
    val description: String,
    val status: String,
    val counters: Int,
    val lines: Int,
    val startedAt: String,
    val inJournal: Boolean,
    val message: String,
)

data class VarianceRow(
    val itemId: String,
    val itemName: String,
    val locationId: String,
    val batchId: String,
    val serialId: String,
    val counted: Double,
    val onHand: Double,
    val variance: Double,
)
