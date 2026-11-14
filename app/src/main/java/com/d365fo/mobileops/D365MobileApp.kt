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
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var skuSync: com.d365fo.mobileops.data.repository.SkuSyncRepository
    @Inject lateinit var sessions: com.d365fo.mobileops.data.repository.WorkerSessionRepository
    @Inject lateinit var syncNotifier: SyncNotifier
    @Inject lateinit var applicationScope: CoroutineScope
    @Inject lateinit var logStore: LogStore
    @Inject lateinit var claimHeartbeat: ClaimHeartbeat
    @Inject lateinit var activeSessionGuard: com.d365fo.mobileops.data.sync.ActiveSessionGuard
    @Inject lateinit var countSubmissions: CountSubmissions
    @Inject lateinit var triggeredCountAlerts: com.d365fo.mobileops.data.counting.TriggeredCountAlerts
    @Inject lateinit var featureFlags: com.d365fo.mobileops.domain.FeatureFlags
    @Inject lateinit var workBackup: com.d365fo.mobileops.data.sync.WorkBackup
    @Inject lateinit var moduleSwitches: com.d365fo.mobileops.data.sync.ModuleSwitches
    @Inject lateinit var deviceCleanup: com.d365fo.mobileops.data.sync.DeviceCleanup

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
        Timber.w("database: %s", com.d365fo.mobileops.data.local.DatabaseFileCheck.lastOutcome)

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
        // A copy of unsent work in D365FO, and the module switches - see WorkBackup, ModuleSwitches.
        moduleSwitches.start()
        workBackup.start()
        // Old records removed once a day, by the days on Mobile clean-up in D365FO.
        deviceCleanup.start()

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
            syncScheduler.schedulePeriodicWork()
            // A handheld with no item cache yet (new install) or a sync left part way gets it
            // within minutes of being docked, not after the daily job's start delay of up to
            // four hours. Spread over five minutes so a fleet started together does not all ask at once.
            if (skuSync.needsCatchUp()) {
                syncScheduler.scheduleSkuContinuation(
                    TimeUnit.MINUTES.toMillis(1) + com.d365fo.mobileops.data.sync.FleetJitter.millisUpTo(TimeUnit.MINUTES.toMillis(4)),
                    replace = true,
                )
            }
            // Another warehouse chosen (sign-in, warehouse switch): sync now - it may be a store
            // that syncs by assortment in D365FO, with other items. The old items stay usable
            // until the new ones are all here.
            applicationScope.launch {
                sessions.session
                    .map { it?.selected?.warehouseId.orEmpty() }
                    .distinctUntilChanged()
                    .collect { warehouse ->
                        if (skuSync.warehouseChosen(warehouse)) {
                            Timber.i("warehouse %s chosen - syncing its items", warehouse)
                            syncScheduler.syncMastersNow(com.d365fo.mobileops.data.repository.ManualSync.CHANGES)
                        }
                    }
            }
            // Counting: keep claims alive, and give every unfinished End count a job again.
            claimHeartbeat.start()
            // One active device per worker: a sign-in elsewhere signs this device out.
            activeSessionGuard.start()
            triggeredCountAlerts.schedule(featureFlags.countTriggeredAlerts)
            runCatching { countSubmissions.resumePending() }
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
