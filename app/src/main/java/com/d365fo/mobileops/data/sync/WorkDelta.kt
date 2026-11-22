package com.d365fo.mobileops.data.sync

import com.d365fo.mobileops.data.remote.dto.DeviceWorkItemDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What [WorkBackup] sends when only part of the set changed (performance plan, phase 5): the
 * items new or different since D365FO last took the set, and the keys it no longer has.
 */
internal object WorkDelta {

    @Serializable
    private data class SentItems(val owner: String, val items: Map<String, String>)

    fun changed(items: List<DeviceWorkItemDto>, hashes: Map<String, String>, sent: Map<String, String>): List<DeviceWorkItemDto> =
        items.filter { sent[it.key] != hashes[it.key] }

    fun removed(hashes: Map<String, String>, sent: Map<String, String>): List<String> =
        (sent.keys - hashes.keys).sorted()

    /** What D365FO should hold for the device: its rows that are not archived (as syncWork counts Held). */
    fun held(items: List<DeviceWorkItemDto>): Int = items.map { it.key to it.archived }.distinctBy { it.first }.count { !it.second }

    /** [owner]: company and backup ID - a record for another one (after a wipe or a company switch) is not used. */
    fun encodeSent(json: Json, owner: String, items: Map<String, String>): String =
        json.encodeToString(SentItems.serializer(), SentItems(owner, items))

    fun decodeSent(json: Json, text: String, owner: String): Map<String, String>? =
        runCatching { json.decodeFromString(SentItems.serializer(), text) }.getOrNull()?.takeIf { it.owner == owner }?.items
}
