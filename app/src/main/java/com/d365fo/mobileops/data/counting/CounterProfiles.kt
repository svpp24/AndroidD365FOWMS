package com.d365fo.mobileops.data.counting

import com.d365fo.mobileops.data.local.dao.CountingDao
import com.d365fo.mobileops.domain.FeatureFlags
import com.d365fo.mobileops.domain.counting.CounterProfile
import com.d365fo.mobileops.domain.counting.CountTaskRepository
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signed-in worker's counter profile (external counter? team? supervisor?), fetched from the
 * server on entering counting and held in memory only. Drives what the app *shows*; the
 * counting services enforce the same rules themselves.
 */
@Singleton
class CounterProfiles @Inject constructor(
    private val remote: CountTaskRepository,
    private val sessions: com.d365fo.mobileops.data.repository.WorkerSessionRepository,
) {
    private val _profile = MutableStateFlow<CounterProfile?>(null)
    val profile: StateFlow<CounterProfile?> = _profile.asStateFlow()
    private var fetchedFor: String? = null
    private var fetchedAt = 0L

    /** Asked once per signed-in worker per [MAX_AGE_MS] - the Count menu and the list both asked (calls, 10 Nov). */
    suspend fun refresh(): CounterProfile? {
        val user = sessions.session.value?.userName.orEmpty().uppercase()
        val now = System.currentTimeMillis()
        if (_profile.value != null && fetchedFor == user && now - fetchedAt < MAX_AGE_MS) return _profile.value
        if (fetchedFor != user) _profile.value = null
        val result = remote.counterProfile()
        if (result is NetworkResult.Success) {
            _profile.value = result.data
            fetchedFor = user
            fetchedAt = now
        }
        return _profile.value
    }

    private companion object {
        const val MAX_AGE_MS = 600_000L
    }
}

/** What the counting list may offer this worker. Cosmetic - the server enforces each one. */
data class CountingCapabilities(
    val externalCounter: Boolean,
    val canReviewVariance: Boolean,
)

/**
 * An external counter never gets supervisor screens, whatever the session
 * says. Without a profile (feature off, or the server unreachable) the sign-in flags apply.
 */
fun countingCapabilities(flags: FeatureFlags, profile: CounterProfile?, sessionIsCycleCountSupervisor: Boolean): CountingCapabilities {
    val external = profile?.isExternalCounter == true
    val supervisor = !external && (profile?.isCycleCountSupervisor ?: sessionIsCycleCountSupervisor)
    return CountingCapabilities(
        externalCounter = external,
        canReviewVariance = flags.countSupervisor && supervisor,
    )
}

/** Applies the server's location type / owner / verify-only to a journal's lines after a download. */
@Singleton
class LineAttributesSync @Inject constructor(
    private val remote: CountTaskRepository,
    private val countingDao: CountingDao,
) {
    suspend fun apply(journalId: String) {
        val attributes = (remote.lineAttributes(journalId) as? NetworkResult.Success)?.data ?: return
        attributes.forEach {
            countingDao.setLineAttributes(journalId, it.lineNumber, it.locationType, it.owner, it.verifyOnly)
        }
    }
}
