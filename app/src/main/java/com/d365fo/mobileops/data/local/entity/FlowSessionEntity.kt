package com.d365fo.mobileops.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/** Which flow a saved session belongs to. */
enum class FlowType { RECEIVING, TRANSFER_SHIP, TRANSFER_RECEIVE }

/**
 * Work in progress on one order, so it survives the app dying.
 *
 * A receiving job is not one action, it is fifty scans over half an hour. Before this, all of
 * that lived in a ViewModel: the app being killed in the background, the battery going, or a
 * worker taking a call and coming back to a cold start all threw the lot away, and the only
 * recovery was to walk the pallet again. On a shared handheld at the end of a shift that is a
 * real cost, and it is the kind of loss that teaches people to distrust the device and keep a
 * paper tally alongside it.
 *
 * Only the *captured* lines are stored. Everything else on the screen — the order header, the
 * open lines, the suggested location — is a copy of something D365FO already knows and can be
 * fetched again. What cannot be fetched again is the fact that someone physically counted
 * twelve of something and put it on a rack.
 *
 * Keyed by flow and order, so shipping and receiving the same transfer are separate jobs, and
 * two orders can be part-done at once — which happens when a pallet turns out to belong to a
 * different delivery than the paperwork said.
 */
@Entity(
    tableName = "flow_session",
    primaryKeys = ["flowType", "orderId"],
    indices = [Index("updatedAt"), Index("userName")],
)
data class FlowSessionEntity(
    val flowType: FlowType,
    val orderId: String,
    /** Warehouse the work was started in; a session is not offered in a different one. */
    val warehouseId: String?,
    /** Who captured it. Resuming someone else's count is not the same as resuming your own. */
    val userName: String?,
    /** Captured lines, serialised. See FlowSessionRepository for the shape. */
    val capturedJson: String,
    val lineCount: Int,
    val updatedAt: Long,
)
