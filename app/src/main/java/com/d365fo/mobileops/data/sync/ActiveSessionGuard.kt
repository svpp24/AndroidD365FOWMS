package com.d365fo.mobileops.data.sync

import com.d365fo.mobileops.data.remote.api.SessionServiceApi
import com.d365fo.mobileops.data.remote.dto.SessionRequestBody
import com.d365fo.mobileops.data.remote.dto.SessionServiceRequest
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Another device still holds this worker's unfinished work - asked before taking the session
 * over. [reportedAt] is when that device last reported it (D365FO's time, UTC text), or null.
 */
data class TakeoverPrompt(val userName: String, val work: PendingWork, val reportedAt: String?)

/**
 * One active device per worker (feedback, 10 Oct: "same user should not be in two devices as
 * active session"). A sign-in here makes this device the worker's active one in D365FO
 * (HSMobileOpsSessionSvc/startSession), taking over from any other; every [CHECK_MS] this device
 * asks whether it still is, and when another device has signed in since, it signs out with
 * [ELSEWHERE_MESSAGE] on the sign-in screen. Taking over rather than refusing: a lost or flat
 * handheld never locks its worker out.
 *
 * Unfinished work stays on the device it was done on (feedback, 11 Oct). So every call reports
 * this device's [PendingWork], and before taking over, the new device asks peekSession: when the
 * other device still holds the worker's jobs or counts, [prompt] asks the worker first - continue
 * here, or sign out and finish them there. The device that is signed out lists what it still
 * holds in its own notice.
 *
 * Offline or unreachable, nothing happens - the start is retried at the next check, and a
 * device is never signed out for not reaching D365FO. A server without the service (404) is
 * treated the same way: the rule is simply not enforced there.
 */
@Singleton
class ActiveSessionGuard @Inject constructor(
    private val api: SessionServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val sessions: WorkerSessionRepository,
    private val companyProvider: CompanyProvider,
    private val secureStore: SecureStore,
    private val pendingWork: PendingWorkCounter,
    private val scope: CoroutineScope,
) {
    /** A sign-in not yet recorded in D365FO - the user name it was for. */
    @Volatile private var pendingStart: String? = null
    @Volatile private var signedOutElsewhere = false
    private val lock = Mutex()
    private var started = false

    private val _prompt = MutableStateFlow<TakeoverPrompt?>(null)

    /** Set while the worker decides whether to take the session over; nothing is checked meanwhile. */
    val prompt: StateFlow<TakeoverPrompt?> = _prompt.asStateFlow()

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            sessions.signIns.collect { user ->
                signedOutElsewhere = false
                runCatching { beforeTakeover(user) }.onFailure { Timber.w(it, "session start failed") }
            }
        }
        scope.launch {
            var lastUser: String? = null
            sessions.session.collect { session ->
                val user = session?.userName?.takeIf { it.isNotBlank() }
                // An ordinary sign-out (or the idle one) gives the session back; a takeover does
                // not - the row is the other device's now.
                if (user == null && lastUser != null && !signedOutElsewhere) {
                    val ended = lastUser!!
                    pendingStart = null
                    _prompt.value = null
                    runCatching { end(ended) }.onFailure { Timber.w(it, "session end failed") }
                }
                lastUser = user
            }
        }
        scope.launch {
            // A session restored at start-up (debug builds) is checked straight away, not started.
            delay(FIRST_CHECK_MS)
            while (isActive) {
                runCatching { check() }.onFailure { Timber.w(it, "session check failed") }
                delay(CHECK_MS)
            }
        }
    }

    /** The worker chose to continue here: take the session over. */
    fun continueHere() {
        val prompt = _prompt.value ?: return
        _prompt.value = null
        pendingStart = prompt.userName
        scope.launch { runCatching { check() }.onFailure { Timber.w(it, "session start failed") } }
    }

    /** The worker chose to finish the work on the other device: sign out here, other session untouched. */
    fun signOutInstead() {
        if (_prompt.value == null) return
        _prompt.value = null
        // Not ours to end: the row belongs to the other device (endSession ignores it anyway).
        signedOutElsewhere = true
        sessions.signOut(reason = "Signed out - work on another device")
    }

    /** A new sign-in: ask what the other device holds, then start, or ask the worker first. */
    internal suspend fun beforeTakeover(user: String) {
        val body = request(user)
        if (body == null) {
            pendingStart = user
            return
        }
        val peek = errorMapper.safeCall { api.peekSession(body) }
        val answer = (peek as? NetworkResult.Success)?.data
        val other = answer?.takeIf { it.success }?.let { PendingWork(it.otherDevicePendingJobs, it.otherDevicePendingCounts) }
        if (other != null && !other.isEmpty) {
            _prompt.value = TakeoverPrompt(user, other, answer.otherDeviceReportedUtc)
            return
        }
        pendingStart = user
        check()
    }

    /** Records a pending sign-in, or asks whether this device is still the active one. */
    internal suspend fun check() = lock.withLock {
        if (_prompt.value != null) return@withLock
        val user = sessions.session.value?.userName?.takeIf { it.isNotBlank() } ?: return@withLock
        val body = request(user) ?: return@withLock
        if (pendingStart.equals(user, ignoreCase = true)) {
            val result = errorMapper.safeCall { api.startSession(body) }
            if ((result is NetworkResult.Success && result.data.success) || result.notDeployed()) pendingStart = null
            return@withLock
        }
        val result = errorMapper.safeCall { api.checkSession(body) }
        val answer = (result as? NetworkResult.Success)?.data ?: return@withLock
        if (answer.success && answer.signedInElsewhere && sessions.session.value?.userName == user) {
            Timber.w("'%s' signed in on another device - signing out here", user)
            signedOutElsewhere = true
            val here = body.request.let { PendingWork(it.pendingJobs, it.pendingCounts) }
            sessions.signOutElsewhere(elsewhereMessage(user, here))
        }
    }

    private suspend fun end(user: String) {
        val body = request(user) ?: return
        errorMapper.safeCall { api.endSession(body) }
    }

    private suspend fun request(user: String): SessionServiceRequest? {
        val company = companyProvider()?.takeIf { it.isNotBlank() }?.lowercase() ?: return null
        val work = pendingWork.forUser(user)
        return SessionServiceRequest(SessionRequestBody(company, user, secureStore.deviceId(), work.jobs, work.counts))
    }

    private fun NetworkResult<*>.notDeployed(): Boolean =
        this is NetworkResult.Error && (failure as? AppFailure.BusinessRule)?.httpCode == 404

    companion object {
        const val CHECK_MS = 120_000L
        const val FIRST_CHECK_MS = 5_000L
        const val ELSEWHERE_MESSAGE =
            "%s signed in on another device, so this device was signed out. Sign in again to use it here."

        /** The notice on the device that was signed out, naming what it still holds. */
        fun elsewhereMessage(user: String, here: PendingWork): String =
            ELSEWHERE_MESSAGE.format(user) + if (here.isEmpty) "" else
                " This device still holds ${here.describe()} for $user - they stay here until $user signs in here to finish them."

        /** The question asked before a takeover. */
        fun promptMessage(prompt: TakeoverPrompt): String =
            "You have ${prompt.work.describe()} on another device. They stay on that device - " +
                "finish them there, or continue here and come back to them later."
    }
}
