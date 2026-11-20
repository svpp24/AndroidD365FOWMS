package com.d365fo.mobileops

import java.util.concurrent.TimeUnit
import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.d365fo.mobileops.data.counting.ClaimHeartbeat
import com.d365fo.mobileops.data.counting.CountSubmissions
import com.d365fo.mobileops.data.logging.AppLogTree
import com.d365fo.mobileops.data.logging.LogStore
import com.d365fo.mobileops.data.sync.SyncNotifier
import com.d365fo.mobileops.data.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class D365MobileApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncNotifier: SyncNotifier
    @Inject lateinit var applicationScope: CoroutineScope
    @Inject lateinit var logStore: LogStore

    // Lazy, all of these, and first touched in applicationScope (IO), never here (performance
    // plan, phase 1, 20 Nov 2026). Field injection builds whatever it is handed during
    // super.onCreate(), on the main thread, ahead of the first frame - and between them these
    // pull in the database, the HTTP clients, the encrypted preferences and the Keystore. None
    // of them is needed before the first screen; every one of them only starts background work.
    @Inject lateinit var syncScheduler: dagger.Lazy<SyncScheduler>
    @Inject lateinit var skuSync: dagger.Lazy<com.d365fo.mobileops.data.repository.SkuSyncRepository>
    @Inject lateinit var sessions: dagger.Lazy<com.d365fo.mobileops.data.repository.WorkerSessionRepository>
    @Inject lateinit var claimHeartbeat: dagger.Lazy<ClaimHeartbeat>
    @Inject lateinit var activeSessionGuard: dagger.Lazy<com.d365fo.mobileops.data.sync.ActiveSessionGuard>
    @Inject lateinit var countSubmissions: dagger.Lazy<CountSubmissions>
    @Inject lateinit var triggeredCountAlerts: dagger.Lazy<com.d365fo.mobileops.data.counting.TriggeredCountAlerts>
    @Inject lateinit var featureFlags: dagger.Lazy<com.d365fo.mobileops.domain.FeatureFlags>
    @Inject lateinit var workBackup: dagger.Lazy<com.d365fo.mobileops.data.sync.WorkBackup>
    @Inject lateinit var moduleSwitches: dagger.Lazy<com.d365fo.mobileops.data.sync.ModuleSwitches>
    @Inject lateinit var deviceCleanup: dagger.Lazy<com.d365fo.mobileops.data.sync.DeviceCleanup>
    @Inject lateinit var selfIntegrityCheck: dagger.Lazy<com.d365fo.mobileops.data.security.SelfIntegrityCheck>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.INFO)
            .build()

    override fun onCreate() {
        // Before super.onCreate(): Hilt may build the HTTP clients there, and they read it.
        com.d365fo.mobileops.data.demo.DemoMode.init(this)
        super.onCreate()

        // Independent of BuildConfig.ALLOW_SENSITIVE_LOGGING and of everything below - see
        // LogStore's own doc on why an in-app-only log store isn't bound by the same "never
        // leaves the device to an adb session" concern the trees below exist to enforce. Planted
        // first, ahead of the sign-in/license gates it needs to be able to explain problems in.
        Timber.plant(AppLogTree(logStore))
        // The database outcome (DatabaseFileCheck) is logged where the database is first opened,
        // now that this no longer opens it during start-up.

        // An uncaught exception on any thread never reaches Timber on its own - the process
        // just dies. "Every event, even the errors" (see LogStore's own doc) is hollow if the
        // single most important error a device can have is exactly the one that's missing, so
        // this logs the crash before handing it to whatever handler was already installed
        // (the platform default, or a crash reporter's, if one is ever added) - never swallowed,
        // only observed first.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // First, and synchronously: a crash loop has to be noticed even when logging fails.
            com.d365fo.mobileops.data.crash.CrashLoopGuard.recordCrash(this, throwable)
            Timber.e(throwable, "Uncaught exception on thread '${thread.name}' - app is terminating")
            // Blocks briefly (see LogStore.flush/LogFilePersistence.flush) so the crash line
            // above has actually reached disk before the process dies - logging it into the
            // in-memory tree alone would be lost the instant this handler returns.
            runCatching { logStore.flush() }
            previousHandler?.uncaughtException(thread, throwable)
        }

        if (BuildConfig.ALLOW_SENSITIVE_LOGGING) {
            Timber.plant(Timber.DebugTree())
        } else {
            // Release builds keep warnings and errors for crash triage but never write
            // quantities, prices or order numbers to logcat.
            Timber.plant(ReleaseTree())
        }

        // Registering a channel is idempotent and does no I/O - safe and cheap on the main
        // thread, and it has to exist before the first background posting failure tries to
        // notify through it.
        syncNotifier.createChannel()

        // The signing-certificate check (a PackageManager round trip and a SHA-256) is read by
        // AppRoot before anything else is shown. Started here, off the main thread, it is usually
        // done by then; `tampered` is a synchronized lazy, so an early read just waits for it.
        // Ahead of the crash-loop return below: cheap, and safe whichever screen comes next.
        applicationScope.launch { selfIntegrityCheck.get().tampered }

        // Stopping again and again at start: nothing below runs - one of these may be what keeps
        // failing - and MainActivity shows the recovery screen (see CrashLoopGuard).
        if (com.d365fo.mobileops.data.crash.CrashLoopGuard.inRecovery(this)) {
            Timber.w("crash loop - starting in recovery mode, background work not started")
            return
        }
        applicationScope.launch {
            kotlinx.coroutines.delay(com.d365fo.mobileops.data.crash.CrashLoopGuard.STABLE_MS)
            com.d365fo.mobileops.data.crash.CrashLoopGuard.reset(this@D365MobileApp)
        }
        applicationScope.launch {
            // A copy of unsent work in D365FO, and the module switches - see WorkBackup, ModuleSwitches.
            moduleSwitches.get().start()
            workBackup.get().start()
            // Old records removed once a day, by the days on Mobile clean-up in D365FO.
            deviceCleanup.get().start()
        }

        // Off the main thread, and deliberately so.
        //
        // This touches WorkManager's own database — two cancels and an enqueue — which forces
        // that database open on whichever thread asks first. Done here inline it happened
        // during Application.onCreate, ahead of the first frame, on a handheld whose CPU is
        // the slowest part of it. Nothing downstream waits on the result: the queue's real
        // cadence comes from requestImmediatePush, and this periodic job is only the safety
        // net that drains it if the app is killed. A few hundred milliseconds late costs
        // nothing; a few hundred milliseconds of startup is visible every single shift.
        applicationScope.launch {
            syncScheduler.get().schedulePeriodicWork()
            // A handheld with no item cache yet (new install) or a sync left part way gets it
            // within minutes of being docked, not after the daily job's start delay of up to
            // four hours. Spread over five minutes so a fleet started together does not all ask at once.
            if (skuSync.get().needsCatchUp()) {
                syncScheduler.get().scheduleSkuContinuation(
                    TimeUnit.MINUTES.toMillis(1) + com.d365fo.mobileops.data.sync.FleetJitter.millisUpTo(TimeUnit.MINUTES.toMillis(4)),
                    replace = true,
                )
            }
            // Another warehouse chosen (sign-in, warehouse switch): sync now - it may be a store
            // that syncs by assortment in D365FO, with other items. The old items stay usable
            // until the new ones are all here.
            applicationScope.launch {
                sessions.get().session
                    .map { it?.selected?.warehouseId.orEmpty() }
                    .distinctUntilChanged()
                    .collect { warehouse ->
                        if (skuSync.get().warehouseChosen(warehouse)) {
                            Timber.i("warehouse %s chosen - syncing its items", warehouse)
                            syncScheduler.get().syncMastersNow(com.d365fo.mobileops.data.repository.ManualSync.CHANGES)
                        }
                    }
            }
            // Counting: keep claims alive, and give every unfinished End count a job again.
            claimHeartbeat.get().start()
            // One active device per worker: a sign-in elsewhere signs this device out.
            activeSessionGuard.get().start()
            triggeredCountAlerts.get().schedule(featureFlags.get().countTriggeredAlerts)
            runCatching { countSubmissions.get().resumePending() }
                .onFailure { Timber.w(it, "could not resume pending count submissions") }
        }
    }

    private class ReleaseTree : Timber.Tree() {
        override fun isLoggable(tag: String?, priority: Int): Boolean =
            priority >= android.util.Log.WARN

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (!isLoggable(tag, priority)) return
            android.util.Log.println(priority, tag ?: "D365", message)
        }
    }
}
