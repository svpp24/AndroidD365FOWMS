package com.d365fo.mobileops.presentation.common

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.focus.ActionRow
import com.d365fo.mobileops.presentation.common.focus.FocusButton
import com.d365fo.mobileops.presentation.common.focus.FocusCaption
import com.d365fo.mobileops.presentation.common.focus.FocusTask
import com.d365fo.mobileops.presentation.common.focus.FocusTaskBox
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusType
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import timber.log.Timber

/**
 * "Update the app" when Google Play has a newer version, on opening the app (user, 18 Nov 2026).
 * Never required: the app is shared by every customer and each version keeps working, so Later
 * always works - it asks again the next day. Update hands over to Google Play's own update
 * screen, which installs the new version and reopens the app; unsent work stays in the device
 * database meanwhile. A device that did not install from Google Play never sees it.
 */
@Composable
fun BoxScope.AppUpdatePrompt() {
    val context = LocalContext.current
    val manager = remember { updateManager(context) }
    var offer by remember { mutableStateOf<AppUpdateInfo?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
    fun start(info: AppUpdateInfo) {
        runCatching { manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()) }
            .onFailure { Timber.w(it, "app update: could not start") }
    }
    LaunchedEffect(Unit) {
        // Once per app start - not again on every return to Home.
        if (checked) return@LaunchedEffect
        checked = true
        val info = runCatching { manager.requestAppUpdateInfo() }.onFailure { Timber.i(it, "app update: no answer from Google Play") }.getOrNull()
            ?: return@LaunchedEffect
        when {
            // An update started earlier and left part way: Google Play carries on with it.
            info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> start(info)
            info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) &&
                !snoozed(context, info.availableVersionCode()) -> offer = info
        }
    }
    val info = offer ?: return
    val later = { snooze(context, info.availableVersionCode()); offer = null }
    BackHandler(onBack = later)
    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x73000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(FocusColors.surface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .padding(top = 16.dp),
        ) {
            FocusTaskBox(FocusTask("Update the app", icon = Icons.Outlined.SystemUpdate), Modifier.padding(horizontal = 16.dp))
            FocusCaption("New version", Modifier.padding(top = 12.dp))
            Text(versionName(info.availableVersionCode()), style = FocusType.code, color = FocusColors.ink)
            Text(
                "From Google Play. Work not sent yet stays on this device.",
                style = FocusType.description,
                color = FocusColors.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            ActionRow {
                FocusButton("Later", onClick = later, modifier = Modifier.weight(1f))
                FocusButton("Update", onClick = { offer = null; start(info) }, primary = true, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Already asked Google Play since the app started. */
private var checked = false

private const val PREFS = "app_update"

private fun updateManager(context: Context): AppUpdateManager {
    // Debug only: a file "fake_update" in the app's files makes Google Play's test double offer
    // 1.5.(this+1), so the prompt can be seen on an emulator.
    if (com.d365fo.mobileops.BuildConfig.DEBUG && java.io.File(context.filesDir, "fake_update").exists()) {
        return com.google.android.play.core.appupdate.testing.FakeAppUpdateManager(context).apply {
            setUpdateAvailable(com.d365fo.mobileops.BuildConfig.VERSION_CODE + 1, AppUpdateType.IMMEDIATE)
        }
    }
    return AppUpdateManagerFactory.create(context)
}

/** Later on this version: not again until tomorrow. */
private fun snooze(context: Context, versionCode: Int) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putInt("later_version", versionCode)
        .putLong("later_day", java.time.LocalDate.now().toEpochDay())
        .apply()
}

private fun snoozed(context: Context, versionCode: Int): Boolean {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return p.getInt("later_version", -1) == versionCode && p.getLong("later_day", Long.MIN_VALUE) >= java.time.LocalDate.now().toEpochDay()
}

/** 10538 -> "1.5.38": versionCode is MAJOR*10000 + MINOR*100 + PATCH (version.properties). */
private fun versionName(code: Int) = "${code / 10000}.${code / 100 % 100}.${code % 100}"
