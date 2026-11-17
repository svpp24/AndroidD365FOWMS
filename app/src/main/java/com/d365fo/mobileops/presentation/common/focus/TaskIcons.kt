package com.d365fo.mobileops.presentation.common.focus

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.ui.graphics.vector.ImageVector

/** The task boxes' icons, in one place (17 Nov 2026). */
object TaskIcons {
    val Vendor: ImageVector get() = Icons.Outlined.Storefront
    val Pick: ImageVector get() = Icons.Outlined.TouchApp
    val Lines: ImageVector get() = Icons.AutoMirrored.Outlined.ListAlt
    val Done: ImageVector get() = Icons.Outlined.CheckCircle
    val Notice: ImageVector get() = Icons.Outlined.Info
    val Reason: ImageVector get() = Icons.AutoMirrored.Outlined.Assignment
    val Scan: ImageVector get() = Icons.Outlined.QrCodeScanner
    val Place: ImageVector get() = Icons.Outlined.Place
}
