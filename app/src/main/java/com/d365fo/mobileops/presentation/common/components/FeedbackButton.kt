package com.d365fo.mobileops.presentation.common.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.drawToBitmap
import com.d365fo.mobileops.presentation.common.tour.tourTarget

/**
 * Opens the feedback screen with a screenshot of the screen it was tapped on. Provided by
 * AppNavGraph for the signed-in app; null (no feedback item) everywhere else, including on the
 * feedback screen itself.
 */
val LocalFeedbackLauncher = staticCompositionLocalOf<((Bitmap?) -> Unit)?> { null }

/**
 * The top bar's ⋮ menu - the only one on a screen: the screen's own actions first ([items],
 * e.g. Start over), then Send feedback (with a screenshot of this screen - see
 * [LocalFeedbackLauncher]) and, on Home, Sign out. One button instead of a row of icons leaves
 * the bar room for the title (feedback, 8-14 Oct). Nothing is shown when the menu would be empty.
 */
@Composable
fun TopBarMenu(
    onSignOut: (() -> Unit)? = null,
    items: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
) {
    val launch = LocalFeedbackLauncher.current
    if (launch == null && onSignOut == null && items == null) return
    val view = LocalView.current
    var open by remember { mutableStateOf(false) }
    IconButton(
        onClick = { open = true },
        modifier = if (onSignOut != null) Modifier.tourTarget("signout") else Modifier,
    ) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        items?.invoke(this) { open = false }
        if (launch != null) {
            DropdownMenuItem(
                text = { Text("Send feedback") },
                leadingIcon = { Icon(Icons.Outlined.Feedback, contentDescription = null) },
                onClick = {
                    open = false
                    // The menu is its own window, so the screenshot is of the screen alone.
                    launch(runCatching { view.rootView.drawToBitmap() }.getOrNull())
                },
            )
        }
        if (onSignOut != null) {
            DropdownMenuItem(
                text = { Text("Sign out") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                onClick = {
                    open = false
                    onSignOut()
                },
            )
        }
    }
}
