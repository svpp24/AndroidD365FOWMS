package com.d365fo.mobileops.debugtools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.d365fo.mobileops.data.remote.interceptor.SimulatedThrottle

/**
 * DEBUG BUILDS ONLY (src/debug). Answers every Nth D365FO call with 429, or stops (nth 0):
 *
 *   adb shell am broadcast -n com.d365fo.mobileops.debug/com.d365fo.mobileops.debugtools.SimulateThrottleReceiver --ei nth 3 --ei retry 30
 *
 * Protected by android.permission.DUMP in the manifest, like SimulateOutageReceiver.
 */
class SimulateThrottleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SimulatedThrottle.everyNth = intent.getIntExtra("nth", 0)
        SimulatedThrottle.retryAfterSeconds = intent.getIntExtra("retry", 30)
        Log.i("THROTTLE", "every ${SimulatedThrottle.everyNth}th call, retry after ${SimulatedThrottle.retryAfterSeconds} s, ${SimulatedThrottle.throttled.get()} so far")
    }
}
