package com.d365fo.mobileops.debugtools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.d365fo.mobileops.data.remote.interceptor.SimulatedOutage

/**
 * DEBUG BUILDS ONLY (src/debug). Turns the simulated D365FO outage on or off:
 *
 *   adb shell am broadcast -n com.d365fo.mobileops.debug/com.d365fo.mobileops.debugtools.SimulateOutageReceiver --ez on true
 *
 * Protected by android.permission.DUMP in the manifest, like LoadTestReceiver.
 */
class SimulateOutageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SimulatedOutage.enabled = intent.getBooleanExtra("on", false)
        Log.i("OUTAGE", "simulated D365FO outage: ${SimulatedOutage.enabled}")
    }
}
