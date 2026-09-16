package com.d365fo.mobileops.data.security

import com.d365fo.mobileops.util.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class LockState { UNLOCKED, LOCKED }

/**
 * Idle auto-lock. A handheld left on a pallet must not stay open on a live journal.
 *
 * Locking is a *UI* gate only — it never interrupts the sync queue. Queued postings keep
 * flowing to D365FO while the screen is locked, which is the whole point of offline-first:
 * the worker's captured work is already committed to Room and owned by the sync engine.
 */
@Singleton
class SessionGuard @Inject constructor(
    private val secureStore: SecureStore,
) {
    private val _lockState = MutableStateFlow(LockState.LOCKED)
    val lockState: StateFlow<LockState> = _lockState.asStateFlow()

    private val idleTimeoutMs: Long
        get() = secureStore.getLong(SecureStore.KEY_IDLE_LOCK_MS, Constants.DEFAULT_IDLE_LOCK_MS)

    fun setIdleTimeout(millis: Long) =
        secureStore.putLong(SecureStore.KEY_IDLE_LOCK_MS, millis)

    fun onUnlocked() {
        _lockState.value = LockState.UNLOCKED
        touch()
    }

    fun lock() {
        _lockState.value = LockState.LOCKED
    }

    /** Called on every user interaction and on each successful scan. */
    fun touch() =
        secureStore.putLong(SecureStore.KEY_LAST_FOREGROUND_AT, System.currentTimeMillis())

    /** Called when the app returns to the foreground. */
    fun evaluateOnResume(): LockState {
        val last = secureStore.getLong(SecureStore.KEY_LAST_FOREGROUND_AT)
        val idleFor = System.currentTimeMillis() - last
        if (last == 0L || idleFor >= idleTimeoutMs) {
            lock()
        }
        return _lockState.value
    }

    fun onBackgrounded() = touch()
}
