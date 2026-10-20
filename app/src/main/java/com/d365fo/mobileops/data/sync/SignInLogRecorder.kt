package com.d365fo.mobileops.data.sync

import android.content.Context
import android.os.Build
import com.d365fo.mobileops.BuildConfig
import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.SignInLogRequestBody
import com.d365fo.mobileops.data.remote.dto.SignInLogServiceRequest
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Worker sign-in history (HSMobileOpsSignInLog in D365FO): each session - sign-in, sign-out, how
 * it ended - kept on the device and sent to HSMobileOpsSignInLogSvc when D365FO can be reached,
 * so a session signed in offline is recorded too. Sent when it starts and again when it ends;
 * D365FO works out the duration.
 *
 * A session the app never saw end (the app was stopped, the device switched off) is closed at
 * the worker's last activity the next time the app starts, as "App closed".
 *
 * Kept in plain preferences: worker names and times, nothing secret.
 */
@Singleton
class SignInLogRecorder @Inject constructor(
    @ApplicationContext context: Context,
    private val api: MobileOpsServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val secureStore: SecureStore,
    private val reachability: AosReachabilityMonitor,
    private val json: Json,
) {
    @Serializable
    data class Session(
        val sessionId: String,
        val dataAreaId: String,
        val userName: String,
        val warehouseId: String,
        val signedInOffline: Boolean,
        val signInMillis: Long,
        val signOutMillis: Long = 0,
        val endReason: String = "",
        /** Changed since D365FO last had it. */
        val unsent: Boolean = true,
    )

    private val prefs = context.getSharedPreferences("signin_log", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val listSerializer = ListSerializer(Session.serializer())

    init {
        scope.launch {
            lock.withLock {
                // Left open by the last run: it ended when the worker last did anything.
                prefs.getString(KEY_OPEN, null)?.let { open ->
                    val end = prefs.getLong(KEY_LAST_ACTIVE, 0L)
                    update(open) { it.copy(signOutMillis = maxOf(end, it.signInMillis), endReason = "App closed", unsent = true) }
                    prefs.edit().remove(KEY_OPEN).apply()
                }
            }
            send()
        }
        // Whatever could not be sent goes the moment D365FO answers again.
        scope.launch { reachability.reachable.filter { it }.collect { send() } }
    }

    /** A worker signed in - online or offline. */
    fun started(dataAreaId: String, userName: String, warehouseId: String?, signedInOffline: Boolean) {
        val now = System.currentTimeMillis()
        scope.launch {
            lock.withLock {
                // Signed in again without the last one signing out: that one ended now.
                prefs.getString(KEY_OPEN, null)?.let { previous ->
                    update(previous) { it.copy(signOutMillis = now, endReason = "Signed in again", unsent = true) }
                }
                val session = Session(
                    sessionId = UUID.randomUUID().toString(),
                    dataAreaId = dataAreaId,
                    userName = userName,
                    warehouseId = warehouseId.orEmpty(),
                    signedInOffline = signedInOffline,
                    signInMillis = now,
                )
                save((load() + session).takeLast(MAX_KEPT))
                prefs.edit().putString(KEY_OPEN, session.sessionId).putLong(KEY_LAST_ACTIVE, now).apply()
            }
            send()
        }
    }

    /** The warehouse of the open session, once the worker has chosen one - if it had none. */
    fun warehouseChosen(warehouseId: String) {
        scope.launch {
            lock.withLock {
                val open = prefs.getString(KEY_OPEN, null) ?: return@withLock
                update(open) { if (it.warehouseId.isBlank()) it.copy(warehouseId = warehouseId, unsent = true) else it }
            }
            send()
        }
    }

    /** The worker did something: a session the app never sees end is closed at this time. */
    fun touched() {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_ACTIVE, 0L) >= ACTIVITY_GRANULARITY_MS) {
            prefs.edit().putLong(KEY_LAST_ACTIVE, now).apply()
        }
    }

    /** The open session ended - [reason] is "Signed out" or "Timed out". */
    fun ended(reason: String) {
        val now = System.currentTimeMillis()
        scope.launch {
            lock.withLock {
                val open = prefs.getString(KEY_OPEN, null) ?: return@withLock
                update(open) { it.copy(signOutMillis = now, endReason = reason, unsent = true) }
                prefs.edit().remove(KEY_OPEN).apply()
            }
            send()
        }
    }

    private suspend fun send() {
        if (!reachability.reachable.value) return
        lock.withLock {
            val deviceId = secureStore.deviceId()
            val deviceName = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
            for (session in load().filter { it.unsent }) {
                val result = errorMapper.safeCall {
                    api.logSignInSession(
                        SignInLogServiceRequest(
                            SignInLogRequestBody(
                                sessionId = session.sessionId,
                                dataAreaId = session.dataAreaId,
                                userName = session.userName,
                                deviceId = deviceId,
                                deviceName = deviceName,
                                warehouseId = session.warehouseId,
                                appVersionName = BuildConfig.VERSION_NAME,
                                signedInOffline = session.signedInOffline,
                                signInMillis = session.signInMillis,
                                signOutMillis = session.signOutMillis,
                                endReason = session.endReason,
                            ),
                        ),
                    )
                }
                val done = when (result) {
                    // INVALID_REQUEST will never be accepted: dropped, not retried for ever. Any
                    // other failure - even an HTTP error from an AOS without this service yet -
                    // keeps it to send again.
                    is NetworkResult.Success -> result.data.success || result.data.errorCode == "INVALID_REQUEST"
                    is NetworkResult.Error, NetworkResult.Loading -> false
                }
                if (!done) {
                    Timber.i("sign-in history: %s kept to send later", session.sessionId)
                    return@withLock
                }
                update(session.sessionId) { it.copy(unsent = false) }
            }
            // Ended and in D365FO: nothing left to keep on the device.
            save(load().filterNot { !it.unsent && it.signOutMillis > 0 })
        }
    }

    private fun load(): List<Session> =
        prefs.getString(KEY_SESSIONS, null)
            ?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }
            .orEmpty()

    private fun save(sessions: List<Session>) {
        prefs.edit().putString(KEY_SESSIONS, json.encodeToString(listSerializer, sessions)).apply()
    }

    private fun update(sessionId: String, change: (Session) -> Session) {
        save(load().map { if (it.sessionId == sessionId) change(it) else it })
    }

    private companion object {
        const val KEY_SESSIONS = "sessions"
        const val KEY_OPEN = "open_session"
        const val KEY_LAST_ACTIVE = "last_active"
        const val MAX_KEPT = 500
        const val ACTIVITY_GRANULARITY_MS = 60_000L
    }
}
