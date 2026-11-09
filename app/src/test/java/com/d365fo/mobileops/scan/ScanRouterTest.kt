package com.d365fo.mobileops.scan

import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.common.scan.ScanContext
import com.d365fo.mobileops.presentation.common.scan.ScanIdentifier
import com.d365fo.mobileops.presentation.common.scan.ScanIdentity
import com.d365fo.mobileops.presentation.common.scan.ScanKind
import com.d365fo.mobileops.presentation.common.scan.ScanRoute
import com.d365fo.mobileops.presentation.common.scan.ScanRouter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.AfterEach
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** The scan queue: nothing lost to rapid scanning, order kept, hand-over between screens. */
class ScanRouterTest {

    private val bridge = HidScannerBridge()

    // Not backgroundScope: advanceUntilIdle() stops once only background work is left, so the
    // router's queue would never be drained. A plain scope on the test's scheduler is run like any
    // other work, and is cancelled after each test.
    private val routerJob = Job()

    @AfterEach
    fun stopRouter() = routerJob.cancel()

    private fun TestScope.router() =
        ScanRouter(ScanIdentifier(FakeScanLookups()), CoroutineScope(StandardTestDispatcher(testScheduler) + routerJob), bridge)
            .also { r -> r.now = { testScheduler.currentTime } }

    private fun ScanRoute.code() = identity.raw.value

    @Test
    fun `fifty rapid scans behind a slow screen all arrive, in order`() = runTest {
        val router = router()
        val got = mutableListOf<String>()
        router.attach(ScanContext.Home) { route ->
            delay(100) // a lookup or a recomposition per scan
            got += route.code()
        }
        val sent = (1..50).map { "BSP-PO-${it.toString().padStart(9, '0')}" }
        sent.forEach { router.submit(scan(it, at = testScheduler.currentTime)) }
        advanceUntilIdle()
        assertThat(got).containsExactlyElementsIn(sent).inOrder()
    }

    @Test
    fun `scans from the wedge, vendor broadcast and camera all come through the bridge`() = runTest {
        val router = router()
        val got = mutableListOf<String>()
        router.attach(ScanContext.Home) { got += it.code() }
        runCurrent()
        bridge.submitBroadcastScan("BSP-PO-000161763")
        bridge.submitCameraScan("5012345678900")
        advanceUntilIdle()
        assertThat(got).containsExactly("BSP-PO-000161763", "5012345678900").inOrder()
    }

    @Test
    fun `the next scan is routed by the context the screen set on the last one`() = runTest {
        val router = router()
        val routes = mutableListOf<ScanRoute>()
        lateinit var handle: ScanRouter.Handle
        handle = router.attach(ScanContext.Flow(accepts = setOf(ScanKind.PURCHASE_ORDER), documentKind = ScanKind.PURCHASE_ORDER)) { route ->
            routes += route
            if (route is ScanRoute.Accept && route.identity is ScanIdentity.Document) {
                handle.update(
                    ScanContext.Flow(
                        accepts = setOf(ScanKind.ITEM),
                        documentKind = ScanKind.PURCHASE_ORDER,
                        currentDocument = (route.identity as ScanIdentity.Document).documentId,
                        documentItemIds = setOf("BSR-11397-0"),
                    ),
                )
            }
        }
        // Both scanned before the first is handled - the second must still see the new context.
        router.submit(scan("BSP-PO-000161763"))
        router.submit(scan("(01)09506000134352(10)24A17"))
        router.submit(scan("5012345678900"))
        advanceUntilIdle()
        assertThat(routes.map { it::class.simpleName }).containsExactly("Accept", "Accept", "Reject").inOrder()
    }

    @Test
    fun `a scan during the hand-over to the next screen waits for it`() = runTest {
        val router = router()
        val home = router.attach(ScanContext.Home) {}
        advanceTimeBy(1_000)
        home.close() // Home navigates to PO receiving
        router.submit(scan("5012345678900", at = testScheduler.currentTime + 100))
        advanceTimeBy(600)
        val got = mutableListOf<String>()
        router.attach(ScanContext.Flow(accepts = setOf(ScanKind.ITEM))) { got += it.code() }
        advanceUntilIdle()
        assertThat(got).containsExactly("5012345678900")
    }

    @Test
    fun `a hand-over that never completes doesn't deliver the scan to a later screen`() = runTest {
        val router = router()
        router.attach(ScanContext.Home) {}.close()
        router.submit(scan("5012345678900", at = testScheduler.currentTime))
        advanceTimeBy(ScanRouter.HANDOFF_MS + 500)
        val got = mutableListOf<String>()
        router.attach(ScanContext.Home) { got += it.code() }
        advanceUntilIdle()
        assertThat(got).isEmpty()
    }

    @Test
    fun `with no screen attached and no hand-over, a scan is left to the screens not on the router yet`() = runTest {
        val router = router()
        advanceTimeBy(10_000)
        router.submit(scan("5012345678900", at = testScheduler.currentTime))
        // runCurrent, not advanceUntilIdle: the clock must not move, or a router that wrongly
        // waited for a screen would time out on virtual time and pass this test anyway.
        runCurrent()
        val got = mutableListOf<String>()
        router.attach(ScanContext.Home) { got += it.code() }
        runCurrent()
        assertThat(got).isEmpty()
    }

    @Test
    fun `only the screen on top receives - attaching replaces the one underneath`() = runTest {
        val router = router()
        val under = mutableListOf<String>()
        val top = mutableListOf<String>()
        val underneath = router.attach(ScanContext.Home) { under += it.code() }
        router.attach(ScanContext.Home) { top += it.code() }
        router.submit(scan("BSP-PO-000161763"))
        advanceUntilIdle()
        // The one underneath closing later doesn't detach the one on top.
        underneath.close()
        router.submit(scan("BSP-PO-000161764"))
        advanceUntilIdle()
        assertThat(under).isEmpty()
        assertThat(top).containsExactly("BSP-PO-000161763", "BSP-PO-000161764").inOrder()
    }

    @Test
    fun `a screen that throws doesn't stop the queue`() = runTest {
        val router = router()
        val got = mutableListOf<String>()
        router.attach(ScanContext.Home) {
            if (it.code() == "BSP-PO-000000001") error("boom")
            got += it.code()
        }
        router.submit(scan("BSP-PO-000000001"))
        router.submit(scan("BSP-PO-000000002"))
        advanceUntilIdle()
        assertThat(got).containsExactly("BSP-PO-000000002")
    }
}
