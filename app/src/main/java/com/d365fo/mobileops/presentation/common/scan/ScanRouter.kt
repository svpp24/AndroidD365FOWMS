package com.d365fo.mobileops.presentation.common.scan

import com.d365fo.mobileops.domain.model.RawScan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one entry point for scans on redesigned screens (redesign brief §3): identify the barcode
 * ([ScanIdentifier]), route it for the screen in front ([routeScan]), hand the result to that
 * screen. Every source arrives here - the wedge, the vendor scan broadcast and the camera all
 * publish through [HidScannerBridge], which this listens to, and anything else can [submit].
 *
 * **Nothing is dropped by rapid scanning.** Scans go into an unbounded queue and are handled one
 * at a time, in the order they were scanned: the next is identified only after the screen has
 * dealt with the one before, so a screen that updates its [ScanContext] on a scan (the PO
 * scanned, now items) routes the very next scan by the new context.
 *
 * **Hand-over between screens.** A scan that lands while one screen has let go and the next
 * hasn't attached yet - Home opening PO receiving straight after the PO scan, say - waits up to
 * [HANDOFF_MS] for the new screen instead of being lost. A scan with no attached screen and no
 * hand-over in progress belongs to a screen that isn't on the router yet (they move over phase by
 * phase and keep their own collectors until then), so it is left to them.
 *
 * One screen is attached at a time: [attach] replaces whatever was attached before, so a screen
 * underneath in the back stack can never receive a scan meant for the one on top.
 */
@Singleton
class ScanRouter @Inject constructor(
    private val identifier: ScanIdentifier,
    private val scope: CoroutineScope,
    bridge: HidScannerBridge,
) {
    /** The router's clock, for the hand-over window - replaced by the tests' virtual time. */
    internal var now: () -> Long = System::currentTimeMillis

    private val queue = Channel<RawScan>(Channel.UNLIMITED)
    private val attached = MutableStateFlow<Handle?>(null)
    @Volatile private var lastDetachedAt = Long.MIN_VALUE / 2

    /** A screen's registration. [update] its context as the flow moves; [close] when it leaves. */
    inner class Handle internal constructor(
        context: ScanContext,
        internal val onRoute: suspend (ScanRoute) -> Unit,
    ) {
        @Volatile var context: ScanContext = context
            private set

        fun update(context: ScanContext) {
            this.context = context
        }

        /** Stop receiving. A no-op when another screen has already replaced this one. */
        fun close() {
            if (attached.compareAndSet(this, null)) lastDetachedAt = now()
        }
    }

    init {
        scope.launch { bridge.scans.collect { submit(it) } }
        scope.launch { for (raw in queue) process(raw) }
    }

    /**
     * Start receiving scans, routed for [context]. [onRoute] runs on the router's own coroutine;
     * the next scan waits until it returns.
     */
    fun attach(context: ScanContext, onRoute: suspend (ScanRoute) -> Unit): Handle {
        val handle = Handle(context, onRoute)
        attached.value = handle
        return handle
    }

    /** A scan being handed on to a screen that isn't on the router - not routed a second time. */
    @Volatile private var passThrough: RawScan? = null

    /** The next time [raw] (this very object) comes through, leave it to the screen's own collector. */
    fun passThrough(raw: RawScan) {
        passThrough = raw
    }

    /** Queue a scan from a source that doesn't go through [HidScannerBridge]. Never blocks. */
    fun submit(raw: RawScan) {
        queue.trySend(raw)
    }

    private suspend fun process(raw: RawScan) {
        if (raw === passThrough) {
            passThrough = null
            return
        }
        val target = attached.value ?: awaitHandOver(raw) ?: run {
            Timber.d("scan left to an unrouted screen: %d chars", raw.value.length)
            return
        }
        val identity = identifier.identify(raw)
        // The screen may have left, or been replaced, while the lookup ran (the scan before this
        // one navigated): then whichever screen took over gets it, if one does in time.
        val handle = if (attached.value === target) target else attached.value ?: awaitHandOver(raw) ?: return
        val route = routeScan(identity, handle.context)
        runCatching { handle.onRoute(route) }
            .onFailure { Timber.e(it, "scan handler failed for %s", route::class.simpleName) }
    }

    /** The next screen to attach, when a screen let go within [HANDOFF_MS] of this scan. */
    private suspend fun awaitHandOver(raw: RawScan): Handle? {
        if (raw.scannedAt - lastDetachedAt > HANDOFF_MS) return null
        val wait = (lastDetachedAt + HANDOFF_MS - now()).coerceAtLeast(0)
        return withTimeoutOrNull(wait) { attached.filterNotNull().first() }
    }

    companion object {
        /**
         * How long a scan waits for the next screen after one lets go. A Compose navigation and
         * the new screen's first composition take a few hundred milliseconds; two seconds covers
         * a slow device with room to spare, and is short enough that a scan never lands on a
         * screen the worker didn't expect it on.
         */
        const val HANDOFF_MS = 2_000L
    }
}
