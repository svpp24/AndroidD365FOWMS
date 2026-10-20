package com.d365fo.mobileops.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The last answer D365FO gave to one read call, kept so the same call still has an answer
 * while D365FO can't be reached. See OfflineReadCacheInterceptor.
 *
 * [key] is a hash of the method, URL and request body, so the legal entity, warehouse and order
 * the call asked about are all part of it. [body] is the response JSON, as received.
 */
@Entity(tableName = "offline_read_cache")
data class ReadCacheEntity(
    @PrimaryKey val key: String,
    /** The service operation, e.g. "HSMobileOpsReceivingSvc/getPurchaseOrder" - for diagnosis. */
    val path: String,
    val body: String,
    val savedAt: Long,
)
