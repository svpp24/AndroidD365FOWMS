package com.d365fo.mobileops.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A posting D365FO has taken but not yet given a final answer for - staged, or still being worked.
 *
 * Kept with its whole payload, because D365FO can still refuse it after the sync queue has let go
 * of it: when that happens it goes to Failed postings like any other refusal, and Resubmit sends
 * it again with nothing to rescan. See [com.d365fo.mobileops.data.sync.PostingFollowUp]. Not on
 * the sync queue itself: a row there means "not sent yet", which is what the staged-line holds and
 * the pending counts read.
 */
@Entity(tableName = "sent_posting")
data class SentPostingEntity(
    @PrimaryKey val idempotencyKey: String,
    val dataAreaId: String,
    val operation: SyncOperation,
    val aggregateId: String,
    val payloadJson: String,
    val workerId: String,
    val deviceId: String,
    val originallyQueuedAt: Long,
    val sentAt: Long,
    val attempts: Int,
)
