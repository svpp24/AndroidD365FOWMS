package com.d365fo.mobileops.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.d365fo.mobileops.data.crash.CrashLoopGuard
import com.d365fo.mobileops.data.security.DisplayPreferences
import com.d365fo.mobileops.data.security.SessionGuard
import com.d365fo.mobileops.data.security.ThemePreferences
import com.d365fo.mobileops.data.sync.SyncStatus
import com.d365fo.mobileops.data.sync.SyncStatusProvider
import com.d365fo.mobileops.presentation.common.AppRoot
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.common.scan.OemScannerReceiver
import com.d365fo.mobileops.presentation.common.scan.ScanFeedback
import com.d365fo.mobileops.presentation.common.theme.D365MobileTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var scannerBridge: HidScannerBridge
    @Inject lateinit var sessionGuard: SessionGuard
    @Inject lateinit var syncStatusProvider: SyncStatusProvider
    @Inject lateinit var activeSessionGuard: com.d365fo.mobileops.data.sync.ActiveSessionGuard
    @Inject lateinit var scanFeedback: ScanFeedback
    @Inject lateinit var themePreferences: ThemePreferences
    @Inject lateinit var displayPreferences: DisplayPreferences

    /**
     * Registered only while the app is in the foreground; see [OemScannerReceiver] for why.
     */
    private var scanReceiver: OemScannerReceiver? = null

    /**
     * The system permission dialog itself is the entire handler here - there is nothing to do
     * with a denial beyond leaving it denied. [SyncNotifier] already checks the grant before
     * every post and silently no-ops without it, so a worker who dismisses this dialog just
     * keeps the in-app failed-sync list as their only signal, exactly as before this feature
     * existed.
     */
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hideSystemBars()

        // A manifest <uses-permission> for POST_NOTIFICATIONS does nothing by itself on API 33+;
        // it only makes the runtime prompt legal to show. Asked once, up front, rather than at
        // the moment of the first dead-letter - by definition that first failure happens in the
        // background, where a permission prompt cannot be shown at all.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Stopped again and again at start: the recovery screen instead of the app, so unsent
        // work can be saved before anyone reinstalls (see CrashLoopGuard).
        val recovery = CrashLoopGuard.inRecovery(this)
        setContent {
            val palette by themePreferences.palette.collectAsState()
            val scale by displayPreferences.scale.collectAsState()
            D365MobileTheme(palette = palette, scale = scale) {
                if (recovery) {
                    com.d365fo.mobileops.presentation.recovery.RecoveryScreen(
                        lastCrash = CrashLoopGuard.lastCrash(this),
                        onStartNormally = ::restartNormally,
                    )
                } else {
                    val status by syncStatusProvider.status.collectAsState(initial = SyncStatus.UNKNOWN)
                    // The scan pill's typed codes go down the same path as a trigger scan.
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.d365fo.mobileops.presentation.common.focus.LocalScanBridge provides scannerBridge,
                    ) {
                        AppRoot(syncStatus = status)
                    }
                }
            }
        }
    }

    /** Leaves recovery: a fresh process, so everything recovery mode skipped starts as usual. */
    private fun restartNormally() {
        CrashLoopGuard.reset(this)
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
        }
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    /**
     * Scanner keystrokes are intercepted before the view hierarchy sees them.
     *
     * Doing this at the Activity rather than per field is what stops a scan from being typed
     * into whichever text field happens to hold focus — the single most common failure mode
     * for wedge-scanner apps.
     *
     * This handheld's physical scan trigger also fires a phantom system Back shortly after
     * the scan - see [HidScannerBridge.justScanned] for the live-traced evidence. That does
     * NOT arrive here as a KEYCODE_BACK KeyEvent (confirmed with diagnostic logging: nothing
     * from this method ever logged around the phantom back, only the screen's own BackHandler
     * did) - this app's target SDK routes back through the predictive-back
     * OnBackInvokedCallback channel instead, which bypasses dispatchKeyEvent entirely. The
     * suppression lives in each step-flow screen's BackHandler instead - see
     * ReceivingFlowScreen/TransferFlowScreen.
     */
    // Activity.dispatchKeyEvent is public API; lint flags the override only because androidx
    // annotates ComponentActivity's own override @RestrictTo.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        sessionGuard.touch()
        if (scannerBridge.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Starts listening for imager broadcasts.
     *
     * This is the path that actually works on this handheld. Its scan head is not a keyboard
     * wedge - the device exposes no alphabetic keyboard at all - so dispatchKeyEvent above never
     * sees a scan, and without this registration pulling the trigger did nothing.
     */
    override fun onStart() {
        super.onStart()
        scanReceiver = OemScannerReceiver.register(this) { barcode ->
            sessionGuard.touch()
            scannerBridge.submitBroadcastScan(barcode)
        }
    }

    override fun onStop() {
        scanReceiver?.let(::unregisterReceiver)
        scanReceiver = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        sessionGuard.evaluateOnResume()
        // Back from sleep or another app: the connection status is re-read, not left stale.
        syncStatusProvider.refresh()
        // One device per worker, checked on return rather than every 2 minutes (calls, 10 Nov).
        activeSessionGuard.checkOnResume()
        // Held only while the app is in the foreground. A picker working through a pallet
        // should not have the screen time out between scans, but the wake lock is released
        // the moment the app is backgrounded so a handheld in a pocket does not cook itself.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The system re-shows both bars behind a permission dialog, a returning app switch,
        // anything that takes focus away and back - so this is re-applied on every resume, the
        // same reasoning as the wake lock right above it, not just once in onCreate.
        hideSystemBars()
    }

    /**
     * Hides the status bar and navigation bar - a warehouse handheld's screen is small enough
     * that the clock and the three-button/gesture bar are real space taken from the scan field,
     * the line list, the captured-so-far card. "Immersive sticky": a swipe from either edge
     * brings both back temporarily for whoever actually needs them (pulling down notifications,
     * using system back/home), and they hide again on their own - never a mode an operator has
     * to remember to turn off.
     */
    private fun hideSystemBars() {
        // Deliberately NOT setDecorFitsSystemWindows(window, false) - that switches the whole
        // activity to edge-to-edge, where Compose's own content has to account for system-bar
        // insets itself via Modifier.systemBarsPadding()/safeDrawingPadding() or every screen's
        // layout math goes wrong. Confirmed live: with it set, the app's own bottom tab bar
        // collapsed to zero height and Home/On-hand/Activity/Settings became unreachable - a
        // real regression, not a cosmetic one. Left at the default (true), a hidden system bar
        // just stops reserving space and a transient one (brought back by the edge swipe below)
        // overlays on top rather than resizing content either way, so hide/show-by-swipe works
        // exactly the same without that cost.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * A dialog or sheet (the camera scanner, a system prompt) is its own window:
     * while it has focus the system shows both bars, and closing it hands focus back without a
     * resume - so the bars stayed for the rest of the session (10 Oct). Re-hidden on regaining
     * focus as well.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sessionGuard.onBackgrounded()
        super.onPause()
    }

    override fun onDestroy() {
        // ScanFeedback holds a ToneGenerator, which is a finite system audio resource — held
        // open it can starve other apps of a tone channel on a device that scans all shift.
        scanFeedback.release()
        super.onDestroy()
    }
}
