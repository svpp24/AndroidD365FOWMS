package com.d365fo.mobileops.util

object Constants {

    /** Room */
    const val DATABASE_NAME = "d365_mobileops.db"
    const val DB_BATCH_SIZE = 100

    /** SkuSyncWorker only - DB_BATCH_SIZE above is tuned for interactive-sized writes (PO
     *  lines, locations); a 1M-row barcode/item master load needs a much larger transaction
     *  chunk or it would be 10,000+ individual transactions. */
    const val SKU_SYNC_BATCH_SIZE = 5000

    const val ORDER_PAGE_SIZE = 50           // order list page
    const val ODATA_BATCH_WRITE_SIZE = 50
    const val MAX_CONCURRENT_REQUESTS = 10

    const val MAX_RETRY_COUNT = 8
    val BACKOFF_LADDER_MINUTES = longArrayOf(1, 5, 15, 60)

    const val WORK_PUSH_TRANSACTIONS = "d365.push.transactions"
    const val WORK_PULL_MASTER_DATA = "d365.pull.masterdata"
    const val WORK_PULL_ORDER_STATUS = "d365.pull.orderstatus"
    /** SkuSyncWorker - deliberately a new name, not a revival of WORK_PULL_MASTER_DATA above
     *  (that one was cancelled on purpose; see SyncScheduler's own doc on why). Wi-Fi+charging
     *  gated, so it can never repeat the bandwidth contention that got the old one killed. */
    const val WORK_SYNC_SKU_MASTER = "d365.sync.skumaster"

    /** Security */
    const val DEFAULT_IDLE_LOCK_MS = 5 * 60 * 1000L

    const val SCAN_DEBOUNCE_MS = 250L
}
