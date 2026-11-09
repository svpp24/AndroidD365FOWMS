package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.data.remote.api.MenuServiceApi
import com.d365fo.mobileops.data.remote.dto.MenuRequestBody
import com.d365fo.mobileops.data.remote.dto.MenuServiceRequest
import com.d365fo.mobileops.data.remote.dto.MenuServiceResponse
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.domain.model.HandheldMenu
import com.d365fo.mobileops.domain.model.MenuDefinition
import com.d365fo.mobileops.domain.model.MenuEntry
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signed-in worker's handheld menus (Mobile device menus in D365FO). The last answer is kept
 * per company and worker, so Home shows the same menus offline and straight after a restart;
 * [refresh] replaces it whenever D365FO answers. Null means no menus are set up: Home keeps its
 * plain list of modules.
 */
@Singleton
class HandheldMenuRepository @Inject constructor(
    private val api: MenuServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val secureStore: SecureStore,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _menu = MutableStateFlow<HandheldMenu?>(null)
    val menu: StateFlow<HandheldMenu?> = _menu.asStateFlow()

    @Volatile private var loadedKey: String? = null

    /** Shows the kept menus for [userName] at once - before, and without, a network call. */
    fun load(userName: String) {
        val key = cacheKey(userName)
        if (key == loadedKey) return
        loadedKey = key
        _menu.value = secureStore.getString(key)
            ?.let { runCatching { json.decodeFromString(MenuServiceResponse.serializer(), it) }.getOrNull() }
            ?.let(::toMenu)
    }

    /** Asks D365FO for the menus. A failure keeps what is shown, so a worker offline keeps theirs. */
    suspend fun refresh(userName: String) = withContext(ioDispatcher) {
        load(userName)
        val result = errorMapper.safeCall {
            api.getMenu(MenuServiceRequest(MenuRequestBody(dataAreaId = company(), userName = userName)))
        }
        if (result is NetworkResult.Success && result.data.success) {
            secureStore.putString(cacheKey(userName), json.encodeToString(MenuServiceResponse.serializer(), result.data))
            _menu.value = toMenu(result.data)
        } else if (result is NetworkResult.Error) {
            Timber.i("menus not refreshed: %s", result.failure.message)
        }
    }

    private fun company(): String = companyProvider()?.lowercase().orEmpty()

    private fun cacheKey(userName: String) = "handheld_menu:${company()}:${userName.uppercase()}"

    /** Modules this app version doesn't know are left out. */
    private fun toMenu(response: MenuServiceResponse): HandheldMenu? {
        if (response.rootMenuId.isBlank()) return null
        val menus = response.menus.filter { it.menuId.isNotBlank() }.associate { row ->
            row.menuId.uppercase() to MenuDefinition(
                id = row.menuId,
                name = row.name.ifBlank { row.menuId },
                entries = row.lines.mapNotNull { line ->
                    when (line.lineType.uppercase()) {
                        "MODULE" -> WarehouseWorkflow.entries.firstOrNull { it.name == line.module }
                            ?.let { MenuEntry.Module(it, line.countLocationId.takeIf { l -> l.isNotBlank() }) }
                        "MENU" -> line.subMenuId.takeIf { it.isNotBlank() }?.let { MenuEntry.SubMenu(it) }
                        else -> null
                    }
                },
            )
        }
        return HandheldMenu(response.rootMenuId, menus).takeIf { it.menu(response.rootMenuId) != null }
    }
}
