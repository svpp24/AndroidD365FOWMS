package com.d365fo.mobileops.presentation.modules.ordercreate

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a worker chose last on the order-creation screens, so the next order starts there (brief
 * §4): Create PO's vendor comes first in the list, a return's reason and action are filled in.
 * Per worker, on this device only - a convenience, not data: losing it only costs one tap.
 */
@Singleton
class OrderCreateMemory @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = runCatching { context.getSharedPreferences("order_create_last_used", Context.MODE_PRIVATE) }.getOrNull()

    fun lastPoVendor(user: String): String? = get("po.vendor", user)
    fun setLastPoVendor(user: String, account: String) = put("po.vendor", user, account)

    fun lastReturnReason(user: String): String? = get("return.reason", user)
    fun lastReturnAction(user: String): String? = get("return.action", user)

    fun setLastReturnCodes(user: String, reason: String, action: String) {
        put("return.reason", user, reason)
        put("return.action", user, action)
    }

    private fun get(key: String, user: String): String? =
        prefs?.getString("$key.${user.lowercase()}", null)?.takeIf { it.isNotBlank() }

    private fun put(key: String, user: String, value: String) {
        if (value.isBlank()) return
        prefs?.edit()?.putString("$key.${user.lowercase()}", value)?.apply()
    }
}
