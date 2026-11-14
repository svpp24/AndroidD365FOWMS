package com.d365fo.mobileops.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `HSMobileOpsDeviceWorkSvc` - this device's unsent work saved in D365FO, and the module switches. */
@Serializable
data class DeviceWorkServiceRequest(@SerialName("_request") val request: DeviceWorkRequestBody)

@Serializable
data class DeviceWorkRequestBody(
    @SerialName("DataAreaId") val dataAreaId: String,
    @SerialName("DeviceId") val deviceId: String,
    @SerialName("UserName") val userName: String = "",
    @SerialName("AppVersion") val appVersion: String = "",
    @SerialName("DeviceModel") val deviceModel: String = "",
    /** A JSON array of [DeviceWorkItemDto] - inbound lists don't bind on AOS (see PostRequest). */
    @SerialName("ItemsJson") val itemsJson: String = "",
    /** [itemsJson] is the device's whole set: D365FO removes this device's rows not in it. */
    @SerialName("Complete") val complete: Boolean = false,
    /** Work restored here from that device: D365FO removes [userName]'s rows of it. */
    @SerialName("ClearDeviceId") val clearDeviceId: String = "",
)

/** One piece of unsent work, as sent in ItemsJson and returned by listWork. */
@Serializable
data class DeviceWorkItemDto(
    @SerialName("Key") val key: String,
    @SerialName("UserName") val userName: String = "",
    @SerialName("WarehouseId") val warehouseId: String = "",
    @SerialName("Kind") val kind: String,
    @SerialName("Reference") val reference: String = "",
    @SerialName("Summary") val summary: String = "",
    @SerialName("LineCount") val lineCount: Int = 0,
    @SerialName("CapturedMs") val capturedMs: Long = 0,
    @SerialName("Payload") val payload: String = "",
    /** Old work this device removed in its clean-up: D365FO keeps it, but doesn't offer it for restore. */
    @SerialName("Archived") val archived: Boolean = false,
    /** Only on listWork answers. */
    @SerialName("DeviceId") val deviceId: String = "",
    @SerialName("UpdatedUtc") val updatedUtc: String = "",
    @SerialName("AppVersion") val appVersion: String = "",
)

@Serializable
data class ModuleSwitchDto(
    @SerialName("Module") val module: String = "",
    @SerialName("Message") val message: String = "",
)

@Serializable
data class DeviceWorkServiceResponse(
    @SerialName("Success") val success: Boolean = false,
    @SerialName("ErrorCode") val errorCode: String = "",
    @SerialName("Message") val message: String = "",
    @SerialName("Stored") val stored: Int = 0,
    /** This device's own clean-up, from Mobile clean-up in D365FO. Days 0: never. */
    @SerialName("DeviceCleanup") val deviceCleanup: Boolean = true,
    @SerialName("FinishedJournalDays") val finishedJournalDays: Int = 7,
    @SerialName("OrderHistoryDays") val orderHistoryDays: Int = 30,
    @SerialName("OldWorkDays") val oldWorkDays: Int = 30,
    @SerialName("Items") val items: List<DeviceWorkItemDto> = emptyList(),
    @SerialName("DisabledModules") val disabledModules: List<ModuleSwitchDto> = emptyList(),
    /** Mobile item snapshot, Ask all devices to full re-sync: when (UTC); blank = never asked. */
    @SerialName("FullResyncAfterUtc") val fullResyncAfterUtc: String = "",
)
