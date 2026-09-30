package com.d365fo.mobileops

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.d365fo.mobileops.data.logging.AppLogTree
import com.d365fo.mobileops.data.logging.LogStore
import com.d365fo.mobileops.data.sync.SyncNotifier
import com.d365fo.mobileops.data.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class D365MobileApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var syncNotifier: SyncNotifier
    @Inject lateinit var applicationScope: CoroutineScope
    @Inject lateinit var logStore: LogStore

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Independent of BuildConfig.ALLOW_SENSITIVE_LOGGING and of everything below - see
        // LogStore's own doc on why an in-app-only log store isn't bound by the same "never
        // leaves the device to an adb session" concern the trees below exist to enforce. Planted
        // first, ahead of the sign-in/license gates it needs to be able to explain problems in.
        Timber.plant(AppLogTree(logStore))

        // An uncaught exception on any thread never reaches Timber on its own - the process
        // just dies. "Every event, even the errors" (see LogStore's own doc) is hollow if the
        // single most important error a device can have is exactly the one that's missing, so
        // this logs the crash before handing it to whatever handler was already installed
        // (the platform default, or a crash reporter's, if one is ever added) - never swallowed,
        // only observed first.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
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
