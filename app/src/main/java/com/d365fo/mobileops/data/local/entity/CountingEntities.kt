package com.d365fo.mobileops.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * How a counting journal's lines and counts move, decided by the server per journal
 * (`TransferMode` on the journal list). DMF: bulk export/import packages. DIRECT: lines and
 * counts through `HSMobileOpsCountTaskSvc` in one call each, for small journals.
 */
enum class TransferMode {
    DMF,
    DIRECT;

    companion object {
        fun parse(value: String?): TransferMode =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: DMF
    }
}

/**
 * The system on-hand quantity of one counting line, as exported. Kept out of
 * [JournalLineEntity] and every counter-facing projection on purpose (blind counting): only the
 * DMF submit path reads it, to send a line marked counted without a quantity as a zero-variance
 * count. Never shown to a counter.
 */
@Entity(
    tableName = "counting_line_reference",
    foreignKeys = [
        ForeignKey(
            entity = JournalEntity::class,
            parentColumns = ["journalId"],
            childColumns = ["journalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("journalId")],
)
data class CountingLineReferenceEntity(
    @PrimaryKey val journalLineId: String,
    val journalId: String,
    val onHandQuantity: String?,
)

/**
 * This device's claim on a counting journal (or one location range of it). Taken before
 * "Get data", so it is not tied to [JournalEntity] by a foreign key - the journal row only
 * exists once the lines are downloaded. [lost] is set when a heartbeat is refused or the
 * claim's expiry passes without a successful one: counting continues, submitting is blocked
 * until the journal is claimed again.
 */
@Entity(tableName = "count_claim")
data class CountClaimEntity(
    @PrimaryKey val journalId: String,
    val claimToken: String,
    val rangeId: String?,
    val locationFrom: String?,
    val locationTo: String?,
    val claimedBy: String,
    val claimedAt: Long,
    val expiresAt: Long?,
    val heartbeatSeconds: Int,
    val lastHeartbeatAt: Long? = null,
    val lost: Boolean = false,
    val lostReason: String? = null,
)

enum class CountSubmissionState {
    /** Waiting for WorkManager (e.g. offline). */
    QUEUED,
    UPLOADING,

    /** DMF import enqueued on the server; [CountSubmissionEntity.executionId] is being polled. */
    IMPORTING,
    SUCCEEDED,

    /** Accepted, but D365FO refused some lines - [CountSubmissionEntity.message] says which. */
    PARTIAL,
    FAILED;

    val inProgress: Boolean get() = this == QUEUED || this == UPLOADING || this == IMPORTING
}

/** Why a submission failed, so the card can say exactly what happened. */
enum class CountSubmissionError {
    ALREADY_POSTED,
    JOURNAL_NOT_FOUND,
    CLAIM_LOST,
    DMF_FAILED,
    NOTHING_COUNTED,

    /** The server does not offer this operation yet (NOT_IMPLEMENTED / service not deployed). */
    NOT_AVAILABLE,
    OTHER,
}

/**
 * One journal's End count, persisted so WorkManager can resume it after the process dies - the
 * row is the source of truth for submission state; the tracker only renders it. The audit
 * fields are fixed when End count is pressed, not when the job finally runs.
 */
@Entity(tableName = "count_submission")
data class CountSubmissionEntity(
    @PrimaryKey val journalId: String,
    /** Client-generated UUID - the server's idempotency key for this submission. */
    val submissionId: String,
    val transferMode: TransferMode,
    val state: CountSubmissionState,
    val error: CountSubmissionError? = null,
    val message: String? = null,
    /** DMF only: set once the import is enqueued, so a resumed job polls instead of re-importing. */
    val executionId: String? = null,
    val percent: Int? = null,
    val userId: String,
    val deviceId: String,
    val originType: String,
    val passNumber: Int,
    val startedAt: Long?,
    val submittedAt: Long,
    val updatedAt: Long,
    val attempts: Int = 0,
)
