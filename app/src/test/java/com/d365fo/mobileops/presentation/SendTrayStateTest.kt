package com.d365fo.mobileops.presentation

import com.d365fo.mobileops.data.sync.SyncStatus
import com.d365fo.mobileops.presentation.common.components.SendTrayState.Kind
import com.d365fo.mobileops.presentation.common.components.sendTrayState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The send tray's wording for each sync state - most urgent first. */
class SendTrayStateTest {

    private val idle = SyncStatus(
        online = true, d365Reachable = true, pendingCount = 0, failedCount = 0,
        lastSyncAt = null, oldestQueuedAt = null,
    )

    @Test
    fun `nothing pending reads as sent, with the flow's own last result`() {
        val state = sendTrayState(idle, lastSent = "GRN BSP-000000053 posted")
        assertThat(state.kind).isEqualTo(Kind.Sent)
        assertThat(state.title).isEqualTo("All sent")
        assertThat(state.line).isEqualTo("GRN BSP-000000053 posted")
    }

    @Test
    fun `pending while online is sending`() {
        val state = sendTrayState(idle.copy(pendingCount = 2))
        assertThat(state.kind).isEqualTo(Kind.Sending)
        assertThat(state.title).isEqualTo("Sending 2 items")
    }

    @Test
    fun `pending while offline is waiting, and says why`() {
        val state = sendTrayState(idle.copy(pendingCount = 1, online = false))
        assertThat(state.kind).isEqualTo(Kind.Waiting)
        assertThat(state.title).isEqualTo("1 item waiting to send")
        assertThat(state.line).contains("offline")
    }

    @Test
    fun `D365FO down is told apart from the device being offline`() {
        val state = sendTrayState(idle.copy(pendingCount = 3, d365Reachable = false))
        assertThat(state.kind).isEqualTo(Kind.Waiting)
        assertThat(state.line).contains("D365FO")
    }

    @Test
    fun `posting switched off holds work on the device`() {
        val state = sendTrayState(idle.copy(pendingCount = 1, postingEnabled = false, online = false))
        assertThat(state.kind).isEqualTo(Kind.Held)
        assertThat(state.line).contains("Settings")
    }

    @Test
    fun `a failure wins over everything else`() {
        val state = sendTrayState(idle.copy(pendingCount = 4, failedCount = 1, online = false))
        assertThat(state.kind).isEqualTo(Kind.Failed)
        assertThat(state.title).isEqualTo("1 item not sent")
    }
}
