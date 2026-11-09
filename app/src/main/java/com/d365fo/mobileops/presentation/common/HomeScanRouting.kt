package com.d365fo.mobileops.presentation.common

import com.d365fo.mobileops.data.local.entity.FlowType
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import com.d365fo.mobileops.presentation.common.scan.ScanRoute
import com.d365fo.mobileops.presentation.common.scan.TransferDirection

/**
 * Where a scan on Home goes (redesign brief §3.2, phase 8) - the navigation half of
 * [ScanRoute]'s Home routes. [ScanRoute] says what the barcode is for; this says which screen
 * opens and how the scan reaches it, after the worker's own Menu access has had its say.
 */
sealed interface HomeScanTarget {

    /**
     * A flow that takes its order the way "Continue where you left off" hands it over
     * ([com.d365fo.mobileops.data.repository.PendingFlowResume]): PO receiving, ship or receive
     * a transfer. The flow opens with the order already scanned.
     */
    data class Resume(val flowType: FlowType, val orderId: String, val route: String) : HomeScanTarget

    /** A transfer the device can't place: ask Ship or Receive - only the ones the worker has. */
    data class AskTransferDirection(val transferId: String, val choices: List<TransferDirection>) : HomeScanTarget

    /**
     * A screen that reads scans itself (store count, location inquiry, a counting journal):
     * open it, then give it the same barcode once it is listening.
     */
    data class Replay(val route: String, val raw: String) : HomeScanTarget

    /** An item: On-hand, with that item looked up. */
    data class OnHand(val itemId: String) : HomeScanTarget

    /** Nothing opens: say why in Home's status band. [warn] is a Warn band, otherwise Bad. */
    data class Notice(val title: String, val line: String, val warn: Boolean = false) : HomeScanTarget
}

/**
 * [route] from [com.d365fo.mobileops.presentation.common.scan.routeScan] on Home, as a screen.
 *
 * @param allowed the workflows on the worker's menu - what Home's own tiles show
 *   ([visibleModules]). A scan never opens a module the worker can't open by tapping.
 * @param journalOnDevice whether the scanned counting journal is downloaded here: then counting
 *   opens on it; otherwise the journal list, where it can be fetched.
 */
fun homeScanTarget(
    route: ScanRoute,
    allowed: Set<WarehouseWorkflow>,
    journalOnDevice: Boolean = false,
): HomeScanTarget = when (route) {
    is ScanRoute.OpenPoReceiving ->
        if (WarehouseWorkflow.PO_RECEIVING in allowed) {
            HomeScanTarget.Resume(FlowType.RECEIVING, route.purchId, Routes.RECEIVE_FLOW)
        } else {
            notOnMenu(route.purchId, WarehouseWorkflow.PO_RECEIVING)
        }

    is ScanRoute.OpenTransfer -> {
        val choices = buildList {
            if (WarehouseWorkflow.TRANSFER_SHIP in allowed) add(TransferDirection.SHIP)
            if (WarehouseWorkflow.TRANSFER_RECEIVE in allowed) add(TransferDirection.RECEIVE)
        }
        val known = route.direction
        when {
            known != null && known in choices -> transferTarget(route.transferId, known)
            known != null -> notOnMenu(route.transferId, workflowOf(known))
            choices.isEmpty() -> HomeScanTarget.Notice(
                route.transferId,
                "Transfers aren't on your menu - ask your supervisor",
            )
            // Only one direction is open to this worker: no real choice, so no question.
            choices.size == 1 -> transferTarget(route.transferId, choices.single())
            else -> HomeScanTarget.AskTransferDirection(route.transferId, choices)
        }
    }

    is ScanRoute.StartStoreCount ->
        if (WarehouseWorkflow.INVENTORY_COUNT in allowed) {
            HomeScanTarget.Replay(Routes.COUNTING_STORE, route.identity.raw.value)
        } else {
            notOnMenu(route.bin.binId, WarehouseWorkflow.INVENTORY_COUNT)
        }

    is ScanRoute.OpenCountJournal -> when {
        WarehouseWorkflow.INVENTORY_COUNT !in allowed -> notOnMenu(route.journalId, WarehouseWorkflow.INVENTORY_COUNT)
        journalOnDevice -> HomeScanTarget.Replay(Routes.countingCapture(route.journalId), route.identity.raw.value)
        else -> HomeScanTarget.Replay(Routes.countingJournals(), route.identity.raw.value)
    }

    is ScanRoute.OpenLocation ->
        if (WarehouseWorkflow.LOCATION_INQUIRY in allowed) {
            HomeScanTarget.Replay(Routes.INQUIRY_LOCATION, route.identity.raw.value)
        } else {
            notOnMenu(route.locationId, WarehouseWorkflow.LOCATION_INQUIRY)
        }

    // On-hand is the scan button's own screen, on every tab - not a menu module.
    is ScanRoute.OpenOnHand -> HomeScanTarget.OnHand(route.identity.item.itemId)

    is ScanRoute.Reject -> HomeScanTarget.Notice(route.title, route.line)

    // Flow routes - never produced for ScanContext.Home.
    is ScanRoute.Accept, is ScanRoute.SwitchDocument, is ScanRoute.Leave ->
        HomeScanTarget.Notice("Not recognised", route.identity.raw.value.trim())
}

/** The flow for a transfer the worker scanned, by [direction]. */
fun transferTarget(transferId: String, direction: TransferDirection): HomeScanTarget.Resume = when (direction) {
    TransferDirection.SHIP -> HomeScanTarget.Resume(FlowType.TRANSFER_SHIP, transferId, Routes.TRANSFER_SHIP)
    TransferDirection.RECEIVE -> HomeScanTarget.Resume(FlowType.TRANSFER_RECEIVE, transferId, Routes.TRANSFER_RECEIVE)
}

private fun workflowOf(direction: TransferDirection) = when (direction) {
    TransferDirection.SHIP -> WarehouseWorkflow.TRANSFER_SHIP
    TransferDirection.RECEIVE -> WarehouseWorkflow.TRANSFER_RECEIVE
}

private fun notOnMenu(id: String, workflow: WarehouseWorkflow) = HomeScanTarget.Notice(
    id,
    "${workflow.label} isn't on your menu - ask your supervisor",
    warn = true,
)
