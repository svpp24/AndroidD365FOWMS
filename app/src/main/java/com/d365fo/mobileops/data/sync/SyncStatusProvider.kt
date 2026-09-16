package com.d365fo.mobileops.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.d365fo.mobileops.data.local.dao.SyncWatermarkDao
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the persistent status bar renders.
 *
 * [online] and [d365Reachable] are two different questions, confirmed live to actually
 * diverge: this device's own internet can be fine - [ConnectivityMonitor] validated, able to
 * reach google.com - while the AOS tenant itself is unreachable (a dev environment that had
 * gone down produced exactly this: `curl: (28) Failed to connect... Could not connect to
 * server` to the AOS host while general internet kept working the whole time). Conflating
 * them would tell an operator to check their Wi-Fi for a problem that is not theirs to fix.
 */
data class SyncStatus(
    val online: Boolean,
    val d365Reachable: Boolean,
    val pendingCount: Int,
    val failedCount: Int,
    val lastSyncAt: Long?,
    val oldestQueuedAt: Long?,
) {
    /**
     * A backlog that is not merely large but *old* means sync has been failing quietly.
     * Surfacing it lets a supervisor intervene before the shift ends and the device goes
     * back on the charger with unposted work on it.
     */
    val isStale: Boolean
        get() = oldestQueuedAt?.let { System.currentTimeMillis() - it > STALE_BACKLOG_MS } ?: false

    /** Nothing about reaching D365FO is degraded right now - device internet and the tenant both answer. */
    val fullyOnline: Boolean get() = online && d365Reachable

    companion object {
        private const val STALE_BACKLOG_MS = 30 * 60 * 1000L
        val UNKNOWN = SyncStatus(
            online = false,
            // Optimistic, same as AosReachabilityMonitor's own default - nothing has yet
            // proven the tenant unreachable, so this does not claim it is.
            d365Reachable = true,
            pendingCount = 0,
            failedCount = 0,
            lastSyncAt = null,
            oldestQueuedAt = null,
        )
    }
}

@Singleton
class ConnectivityMonitor @Inject constructor(context: Context) {

    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    /**
     * Emits validated connectivity, not merely "a network exists".
     *
     * NET_CAPABILITY_VALIDATED is the difference that matters in a warehouse: a handheld
     * associated with an access point that has no working backhaul reports a network, and
     * every posting against it times out. Treating that as offline keeps the queue intact
     * instead of burning retries.
     */
    val isOnline: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                trySend(
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                )
            }

            override fun onLost(network: Network) {
                trySend(false)
            }

            override fun onUnavailable() {
                trySend(false)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)

        trySend(currentlyOnline())

        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    fun currentlyOnline(): Boolean {
        val active = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

/**
 * Whether the AOS tenant itself is answering - as distinct from [ConnectivityMonitor], which
 * only ever speaks to whether *this device* has internet at all. Android's own validated-
 * network check pings a Google/AOSP URL, not the AOS host this app actually talks to, so it
 * has nothing to say about a tenant that is down while the rest of the internet is fine - see
 * the [SyncStatus] class doc for the live incident this is built from.
 *
 * Updated from the outcome of real requests (see `AosReachabilityInterceptor`) the moment the
 * device is actively working. That alone left a real gap, confirmed live: a lookup fails once
 * (a transient DNS blip, an AOS restart mid-request), the flag flips to unreachable, and if the
 * operator is not immediately queuing more postings or making more calls - just standing on
 * Home, say - nothing ever calls again to notice the tenant already recovered. The banner then
 * sits there stuck long after D365FO is genuinely back up, which reads as "the app is lying to
 * me" rather than "nothing has checked since". [SyncStatusProvider] closes that gap by polling
 * [AosReachabilityProber] on an interval for as long as this stays false, not just reacting to
 * whatever the operator happens to do next.
 */
@Singleton
class AosReachabilityMonitor @Inject constructor() {
    // Optimistic: nothing has proven the tenant unreachable yet, so a cold start does not
    // claim it is before the first request has even gone out.
    private val _reachable = MutableStateFlow(true)
    val reachable: StateFlow<Boolean> = _reachable.asStateFlow()

    /** Any HTTP response - even a 4xx/5xx - proves the host answered. See the interceptor. */
    fun markReachable() {
        _reachable.value = true
    }

    /** A transport-level failure: no HTTP answer came back at all. See the interceptor. */
    fun markUnreachable() {
        _reachable.value = false
    }
}

/** The sync-queue-derived quarter of [SyncStatus], combined separately - see [SyncStatusProvider]. */
private data class QueueSignals(
    val pending: Int,
    val failed: Int,
    val lastSyncAt: Long?,
    val oldestQueuedAt: Long?,
)

@Singleton
class SyncStatusProvider @Inject constructor(
    connectivityMonitor: ConnectivityMonitor,
    aosReachabilityMonitor: AosReachabilityMonitor,
    prober: AosReachabilityProber,
    syncQueueRepository: SyncQueueRepository,
    watermarkDao: SyncWatermarkDao,
    private val scheduler: SyncScheduler,
    scope: CoroutineScope,
) {
    init {
        // Answers "immediately after opening the app" - AosReachabilityMonitor is otherwise
        // purely reactive to real calls (see its class doc), which leaves a cold start over a
        // tenant that is actually down reading as fully online until the first scan finds out
        // the hard way. distinctUntilChanged still lets the *first* emission through - a cold
        // start with internet already up probes right away - and fires again every time
        // device internet comes back, so a Wi-Fi recovery re-checks D365 promptly too rather
        // than waiting on whatever the operator happens to do next.
        scope.launch {
            connectivityMonitor.isOnline.distinctUntilChanged().collect { online ->
                if (online) prober.probe()
            }
        }

        // Self-heals a stuck "unreachable" reading - see the class doc above on the gap this
        // closes. Only polls while actually unreachable: the moment a real request (or this
        // loop's own probe) marks it reachable again, aosReachabilityMonitor.reachable emits
        // true, this collector's suspended body returns, and nothing is polled again until the
        // next time it goes false. So a healthy tenant costs nothing here, same as before.
        scope.launch {
            aosReachabilityMonitor.reachable.collect { reachable ->
                if (!reachable) {
                    while (!aosReachabilityMonitor.reachable.value) {
                        delay(RECOVERY_PROBE_INTERVAL_MS)
                        prober.probe()
                    }
                }
            }
        }
    }

    private val queueSignals: Flow<QueueSignals> = combine(
        syncQueueRepository.observePendingCount(),
        syncQueueRepository.observeUnacknowledgedFailureCount(),
        watermarkDao.observeLastSyncAt(),
        syncQueueRepository.observeOldestQueuedAt(),
    ) { pending, failed, lastSync, oldest -> QueueSignals(pending, failed, lastSync, oldest) }

    val status: Flow<SyncStatus> = combine(
        connectivityMonitor.isOnline,
        aosReachabilityMonitor.reachable,
        queueSignals,
    ) { online, d365Reachable, queue ->
        // Connectivity coming back is the cue to drain the queue. Doing it here rather than
        // in a separate observer means there is exactly one place that reacts to it. Gated on
        // d365Reachable too - retrying into a tenant that is still down is exactly the doomed
        // attempt the reachability signal exists to avoid, not just to report.
        if (online && d365Reachable && queue.pending > 0) scheduler.requestImmediatePush()
        SyncStatus(online, d365Reachable, queue.pending, queue.failed, queue.lastSyncAt, queue.oldestQueuedAt)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), SyncStatus.UNKNOWN)

    private companion object {
        /**
         * How often to re-check while [AosReachabilityMonitor.reachable] is false. Short enough
         * that "F&O is back up" doesn't sit stale on the banner for minutes on a device standing
         * idle; long enough not to hammer a tenant that is genuinely down with a probe every few
         * seconds from every idle handheld in the fleet.
         */
        const val RECOVERY_PROBE_INTERVAL_MS = 30_000L
    }
}
