package com.d365fo.mobileops.domain.model

/**
 * The worker's handheld menus, as set up in D365FO (Mobile device menus): Home is [rootId], and
 * each menu lists modules and sub-menus in order - like a mobile device menu in Warehouse
 * management. Menu IDs are matched without regard to case, as D365FO does.
 */
data class HandheldMenu(val rootId: String, val menus: Map<String, MenuDefinition>) {

    fun menu(menuId: String): MenuDefinition? = menus[menuId.uppercase()]

    /**
     * What [menuId] shows: the modules [isShown] lets through, and the sub-menus with at least
     * one of those somewhere below them. A sub-menu that leads back to one already open is
     * left out rather than followed.
     */
    fun visibleEntries(menuId: String, isShown: (WarehouseWorkflow) -> Boolean): List<MenuEntry> {
        val key = menuId.uppercase()
        return menu(key)?.entries.orEmpty().filter { entry ->
            when (entry) {
                is MenuEntry.Module -> isShown(entry.workflow)
                is MenuEntry.SubMenu -> hasVisible(entry.menuId, isShown, setOf(key))
            }
        }
    }

    private fun hasVisible(menuId: String, isShown: (WarehouseWorkflow) -> Boolean, open: Set<String>): Boolean {
        val key = menuId.uppercase()
        if (key in open) return false
        return menu(key)?.entries.orEmpty().any { entry ->
            when (entry) {
                is MenuEntry.Module -> isShown(entry.workflow)
                is MenuEntry.SubMenu -> hasVisible(entry.menuId, isShown, open + key)
            }
        }
    }
}

data class MenuDefinition(val id: String, val name: String, val entries: List<MenuEntry>)

sealed interface MenuEntry {
    data class Module(val workflow: WarehouseWorkflow) : MenuEntry
    data class SubMenu(val menuId: String) : MenuEntry
}
