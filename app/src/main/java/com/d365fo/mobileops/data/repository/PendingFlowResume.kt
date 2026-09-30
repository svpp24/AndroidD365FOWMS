package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.data.local.entity.FlowType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries the order to resume from Home's "Continue where you left off" card into the flow
 * screen it navigates to.
 *
 * The nav route itself carries no argument for this (see Routes.RECEIVE_FLOW/TRANSFER_SHIP/
 * TRANSFER_RECEIVE - plain, parameter-less strings), so without this, tapping the card landed on
 * a completely fresh order prompt - confirmed live: no PO/transfer number filled in, nothing
 * restored, indistinguishable from tapping the plain module tile. The flow's own restore path
 * (FlowSessionRepository, keyed off the order number once it is looked up again - see
 * ReceivingFlowViewModel.onPurchaseOrder) already existed for a *different* reason: re-verifying
 * the order with the server before reattaching locally-banked lines. This only automates
 * supplying that number, the same as if the operator had re-scanned or re-typed the order they
 * already worked on minutes ago.
 */
@Singleton
class PendingFlowResume @Inject constructor() {
    private var pending: Pair<FlowType, String>? = null

    fun set(flowType: FlowType, orderId: String) {
        pending = flowType to orderId
    }

    /**
     * Returns and clears the pending order id for [flowType] - null if none is pending, or the
     * pending one is for a different flow type. Consume-once: a screen that is merely
     * recomposed, not freshly navigated to, must not resubmit the same order a second time.
     */
    fun consume(flowType: FlowType): String? {
        val (type, orderId) = pending ?: return null
        if (type != flowType) return null
        pending = null
        return orderId
    }
}
