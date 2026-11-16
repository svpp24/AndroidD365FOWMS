package com.d365fo.mobileops.presentation.common

import androidx.compose.ui.graphics.vector.ImageVector
import com.d365fo.mobileops.presentation.common.components.LineIcons
import org.junit.Assert.assertTrue
import org.junit.Test

/** No icon drawn twice across the app's menus (user, 16 Nov 2026). */
class MenuIconsTest {

    private fun path(icon: ImageVector): String = LineIcons.pathOf[icon] ?: "material:" + icon.name

    @Test
    fun noMenuShowsTheSameIconTwice() {
        val rows = mutableListOf<Pair<String, ImageVector>>()
        listOf("Receive", "Ship", "Count", "Create", "Activity", "Enquiry").zip(HOME_ICONS).forEach { rows += "Home > " + it.first to it.second }
        // A group with one module opens it straight away - its row is never seen in a menu.
        ModuleGroup.entries.forEach { group ->
            val modules = group.entries.mapNotNull { (w, _) -> MODULES.firstOrNull { it.workflow == w } }
            if (modules.size > 1) modules.forEach { rows += group.title + " > " + group.label(it) to it.icon }
        }
        modulesIn(ModuleArea.ENQUIRY, MODULES).forEach { rows += "Enquiry > " + it.title to it.icon }
        SettingsMenuItem.entries.forEach { rows += "Settings > " + it.title to it.icon }

        val repeats = rows.groupBy { path(it.second) }.values.filter { it.size > 1 }
        assertTrue(
            "Icons repeated: " + repeats.joinToString("; ") { r -> r.joinToString(", ") { it.first } },
            repeats.isEmpty(),
        )
    }
}
