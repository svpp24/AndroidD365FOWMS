package com.d365fo.mobileops.debugtools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.d365fo.mobileops.BuildConfig
import com.d365fo.mobileops.data.repository.WorkerSession
import com.d365fo.mobileops.data.security.SecureStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * DEBUG BUILDS ONLY (src/debug). With DEV_SKIP_SIGN_IN on, makes the next start of the app run as
 * another worker, so dual counting can be tested on one device with no credentials entered. Holds
 * no password: D365FO knows the counter only by name in these calls. Restart the app after it.
 *
 *   adb shell am broadcast -n com.d365fo.mobileops.debug/com.d365fo.mobileops.debugtools.DevUserReceiver \
 *       --es user 003 --ez supervisor false
 *
 * Protected by android.permission.DUMP in the manifest, like LoadTestReceiver.
 */
@AndroidEntryPoint
class DevUserReceiver : BroadcastReceiver() {

    @Inject lateinit var secureStore: SecureStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEV_SKIP_SIGN_IN) return
        val user = intent.getStringExtra("user")?.takeIf { it.isNotBlank() } ?: return
        val supervisor = intent.getBooleanExtra("supervisor", false)
        // Keeps what the session carries (warehouses, module access) and changes only who it is.
        val current = secureStore.getString(SecureStore.KEY_DEBUG_WORKER_SESSION)
            ?.let { runCatching { json.decodeFromString(WorkerSession.serializer(), it) }.getOrNull() }
        if (current == null) {
            Log.w("DEVUSER", "no session to change: start the app once first")
            return
        }
        val session = current.copy(
            userName = user,
            displayName = user,
            isSupervisor = supervisor,
            isCycleCountSupervisor = supervisor,
        )
        secureStore.putString(SecureStore.KEY_DEBUG_WORKER_SESSION, json.encodeToString(WorkerSession.serializer(), session))
        Log.i("DEVUSER", "next start runs as $user, supervisor $supervisor")
    }
}
