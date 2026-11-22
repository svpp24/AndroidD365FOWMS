package com.d365fo.mobileops.sync

import com.d365fo.mobileops.data.remote.dto.DeviceWorkItemDto
import com.d365fo.mobileops.data.sync.WorkDelta
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/** WorkBackup sends only what changed (performance plan, phase 5). */
class WorkDeltaTest {

    private fun item(key: String, summary: String = "s", archived: Boolean = false) =
        DeviceWorkItemDto(key = key, kind = "POSTING", summary = summary, archived = archived)

    @Test
    fun `only new and changed items go, and the keys that are gone`() {
        val sent = mapOf("a" to "h1", "b" to "h2", "c" to "h3")
        val items = listOf(item("a"), item("b"), item("d"))
        val hashes = mapOf("a" to "h1", "b" to "h2-changed", "d" to "h4")

        assertThat(WorkDelta.changed(items, hashes, sent).map { it.key }).containsExactly("b", "d").inOrder()
        assertThat(WorkDelta.removed(hashes, sent)).containsExactly("c")
    }

    @Test
    fun `nothing changed is nothing to send`() {
        val hashes = mapOf("a" to "h1")
        assertThat(WorkDelta.changed(listOf(item("a")), hashes, hashes)).isEmpty()
        assertThat(WorkDelta.removed(hashes, hashes)).isEmpty()
    }

    @Test
    fun `held counts what D365FO keeps live - archived items are not counted`() {
        assertThat(WorkDelta.held(listOf(item("a"), item("b", archived = true), item("c")))).isEqualTo(2)
    }

    @Test
    fun `the record is only used for the company and backup id it was made for`() {
        val text = WorkDelta.encodeSent(Json, "bsp|device-1", mapOf("a" to "h1"))
        assertThat(WorkDelta.decodeSent(Json, text, "bsp|device-1")).containsExactly("a", "h1")
        assertThat(WorkDelta.decodeSent(Json, text, "bsp|device-1~1")).isNull()
        assertThat(WorkDelta.decodeSent(Json, text, "usmf|device-1")).isNull()
        assertThat(WorkDelta.decodeSent(Json, "not json", "bsp|device-1")).isNull()
    }
}
