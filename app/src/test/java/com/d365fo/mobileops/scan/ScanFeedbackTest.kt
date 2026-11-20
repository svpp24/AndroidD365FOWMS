package com.d365fo.mobileops.scan

import android.content.Context
import com.d365fo.mobileops.presentation.common.scan.ScanFeedback
import com.google.common.truth.Truth.assertThat
import io.mockk.confirmVerified
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The scan cue must never run on the caller's thread: vibrating is a binder call into
 * system_server, and crash testing froze the app on the main thread inside it (20 Nov 2026).
 */
class ScanFeedbackTest {

    @Test
    fun `cues are handed to the executor, never run on the calling thread`() {
        val context = mockk<Context>(relaxed = true)
        val queued = mutableListOf<Runnable>()
        val feedback = ScanFeedback(context) { queued += it }

        feedback.success()
        feedback.error()
        feedback.warning()

        assertThat(queued).hasSize(3)
        // Nothing reached the system services yet - all of that happens when the tasks run.
        confirmVerified(context)
    }

    @Test
    fun `a stuck feedback thread keeps only the newest cues and never blocks the caller`() {
        val executor = ScanFeedback.feedbackExecutor()
        val stuck = CountDownLatch(1)
        val ran = mutableListOf<Int>()
        executor.execute { stuck.await(5, TimeUnit.SECONDS) } // a vibrate that never returns

        val started = System.nanoTime()
        repeat(20) { i -> executor.execute { synchronized(ran) { ran += i } } }
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertThat(tookMs).isLessThan(500)
        stuck.countDown()
        // shutdown still runs what is queued; awaitTermination waits for it.
        (executor as ThreadPoolExecutor).shutdown()
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()

        // Only the newest cues survived the wait, in the order they were scanned.
        val kept = ScanFeedback.MAX_PENDING_CUES
        assertThat(ran).containsExactlyElementsIn((20 - kept until 20).toList()).inOrder()
    }
}
