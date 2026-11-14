package com.d365fo.mobileops.data.sync

import com.d365fo.mobileops.BuildConfig
import com.d365fo.mobileops.data.remote.api.DeviceWorkServiceApi
import com.d365fo.mobileops.data.remote.dto.DeviceWorkRequestBody
import com.d365fo.mobileops.data.remote.dto.DeviceWorkServiceRequest
import com.d365fo.mobileops.data.remote.dto.ModuleSwitchDto
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Modules switched off in D365FO (Mobile module switches) - a broken module is stopped for every
 * device without touching one, until a fixed app is installed.
 *
 * Offline first: the last answer is kept on the device and used until D365FO answers again. With
 * no answer yet, nothing is switched off - not reaching D365FO never locks anyone out of a
 * module. Asked every [POLL_MS], and refreshed by every work backup answer as well.
 */
@Singleton
class ModuleSwitches @Inject constructor(
    private val secureStore: SecureStore,
    private val json: Json,
    private val api: DeviceWorkServiceApi,
    /** The same answer carries the handheld's clean-up days. */
    private val cleanupSettings: DeviceCleanupSettingsStore,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val scope: CoroutineScope,
    /** The same answer can ask for a full item re-sync (Mobile item snapshot in D365FO). */
    private val skuSync: dagger.Lazy<com.d365fo.mobileops.data.repository.SkuSyncRepository>,
    private val syncScheduler: dagger.Lazy<SyncScheduler>,
) {
    private val _disabled = MutableStateFlow(load())

    /** Switched-off modules and what the worker is told about each. */
    val disabled: StateFlow<Map<WarehouseWorkflow, String>> = _disabled.asStateFlow()

    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            delay(FIRST_POLL_MS)
            while (isActive) {
                runCatching { refresh() }.onFailure { Timber.w(it, "module switches: check failed") }
                delay(POLL_MS)
            }
        }
    }

    suspend fun refresh() {
        val company = companyProvider()?.takeIf { it.isNotBlank() }?.lowercase() ?: return
        val body = DeviceWorkRequestBody(
            dataAreaId = company,
            deviceId = secureStore.deviceId(),
            appVersion = BuildConfig.VERSION_NAME,
        )
        val result = errorMapper.safeCall { api.getAppControl(DeviceWorkServiceRequest(body)) }
        // Offline, or a D365FO without the service: keep what we have.
        val answer = (result as? NetworkResult.Success)?.data?.takeIf { it.success } ?: return
        update(answer.disabledModules)
        cleanupSettings.update(answer)
        resyncIfAsked(answer.fullResyncAfterUtc)
    }

    /**
     * D365FO asked every device for a full re-sync after [askedUtc]: one, unless this device's last
     * full download is newer - or it already started one for the same request.
     */
    private fun resyncIfAsked(askedUtc: String) {
        if (askedUtc.isBlank() || askedUtc == secureStore.getString(KEY_RESYNC_HANDLED)) return
        val asked = runCatching {
            java.time.LocalDateTime.parse(askedUtc.trim().removeSuffix("Z").take(19)).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        }.getOrNull() ?: return
        val lastFull = skuSync.get().lastFullAt() ?: 0L
        secureStore.putString(KEY_RESYNC_HANDLED, askedUtc)
        if (lastFull >= asked) return
        Timber.w("item list: D365FO asked for a full re-sync (%s) - starting one", askedUtc)
        syncScheduler.get().syncMastersNow(com.d365fo.mobileops.data.repository.ManualSync.FULL)
    }

    /** D365FO's current list - replaces the one kept. */
    fun update(rows: List<ModuleSwitchDto>) {
        val map = rows.mapNotNull { row ->
            val workflow = runCatching { WarehouseWorkflow.valueOf(row.module.trim()) }.getOrNull() ?: return@mapNotNull null
            workflow to row.message.trim().ifBlank { DEFAULT_MESSAGE }
        }.toMap()
        if (map == _disabled.value) return
        Timber.w("module switches: %s switched off", map.keys.joinToString().ifBlank { "none" })
        _disabled.value = map
        secureStore.putString(
            KEY,
            json.encodeToString(ListSerializer(ModuleSwitchDto.serializer()), map.map { (w, m) -> ModuleSwitchDto(w.name, m) }),
        )
    }

    fun messageFor(workflow: WarehouseWorkflow): String? = _disabled.value[workflow]

    private fun load(): Map<WarehouseWorkflow, String> = runCatching {
        val text = secureStore.getString(KEY) ?: return emptyMap()
        json.decodeFromString(ListSerializer(ModuleSwitchDto.serializer()), text).mapNotNull { row ->
            runCatching { WarehouseWorkflow.valueOf(row.module) }.getOrNull()?.let { it to row.message }
        }.toMap()
    }.getOrDefault(emptyMap())

    companion object {
        const val DEFAULT_MESSAGE = "Switched off for now. Ask your supervisor."
        private const val KEY = "module_switches"
        /** The last "Ask all devices to full re-sync" this device acted on. */
        private const val KEY_RESYNC_HANDLED = "full_resync_handled"
        private const val FIRST_POLL_MS = 3_000L
        // 30 minutes (was 3): the work backup's answer carries the switches whenever work
        // changes (WorkBackup.update), so this is only the fallback for an idle device.
        private const val POLL_MS = 1_800_000L
    }
}
