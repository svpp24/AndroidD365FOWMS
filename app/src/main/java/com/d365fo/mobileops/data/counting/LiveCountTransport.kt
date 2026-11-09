package com.d365fo.mobileops.data.counting

import com.d365fo.mobileops.data.local.entity.CountExceptionEntity
import com.d365fo.mobileops.data.local.entity.CountLocationCheckEntity
import com.d365fo.mobileops.data.local.entity.CountSubmissionError
import com.d365fo.mobileops.data.repository.CountingJournalRepository
import com.d365fo.mobileops.domain.model.ImportJobStatus
import com.d365fo.mobileops.util.NetworkResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveCountTransport @Inject constructor(
    private val dmf: CountingJournalRepository,
    private val direct: DirectCountingRepository,
    private val staging: CountStagingRepository,
    private val bins: CountBinRepository,
) : CountTransport {

    override suspend fun submitBins(journalId: String, audit: SubmissionAudit) = bins.submit(journalId, audit)

    override suspend fun completeLocation(journalId: String, audit: SubmissionAudit, locationId: String): TransportResult<String> =
        when (val result = direct.completeLocation(journalId, audit, locationId)) {
            is com.d365fo.mobileops.util.NetworkResult.Success -> TransportResult.Ok(result.data)
            is com.d365fo.mobileops.util.NetworkResult.Error -> when (val failure = result.failure) {
                // The location's reason (lines with no count, someone else's) is the counter's to read.
                is com.d365fo.mobileops.util.AppFailure.BusinessRule -> TransportResult.Fail(CountSubmissionError.OTHER, failure.message)
                else -> failure.toTransport()
            }
            com.d365fo.mobileops.util.NetworkResult.Loading -> TransportResult.Retry("unexpected Loading")
        }

    override suspend fun submitStaged(journalId: String, audit: SubmissionAudit) = staging.submitJournal(journalId, audit)

    override suspend fun startDmfImport(journalId: String, audit: SubmissionAudit, claimToken: String?) =
        dmf.submitCounts(journalId, audit, claimToken).toTransport()

    override suspend fun pollDmfImport(executionId: String): TransportResult<ImportJobStatus> =
        dmf.pollImport(executionId).toTransport()

    override suspend fun submitDirect(journalId: String, audit: SubmissionAudit, claimToken: String?) =
        direct.submit(journalId, audit, claimToken).toTransport()

    override suspend fun submitExceptions(
        journalId: String,
        audit: SubmissionAudit,
        claimToken: String?,
        exceptions: List<CountExceptionEntity>,
        emptyLocations: List<CountLocationCheckEntity>,
        confirmations: List<com.d365fo.mobileops.data.local.entity.JournalLineEntity>,
    ) = direct.submitExceptions(journalId, audit, claimToken, exceptions, emptyLocations, confirmations).toTransport()

    private fun <T> NetworkResult<T>.toTransport(): TransportResult<T> = when (this) {
        is NetworkResult.Success -> TransportResult.Ok(data)
        is NetworkResult.Error -> failure.toTransport()
        NetworkResult.Loading -> TransportResult.Retry("did not complete")
    }
}
