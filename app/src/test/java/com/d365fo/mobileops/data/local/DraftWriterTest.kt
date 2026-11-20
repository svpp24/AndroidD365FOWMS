package com.d365fo.mobileops.data.local

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

class DraftWriterTest {

    /** Runs nothing until [drain] - stands in for a writer thread that is busy. */
    private class HeldExecutor : Executor {
        val queued = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queued.addLast(command) }
        fun drain() { while (queued.isNotEmpty()) queued.removeFirst().run() }
    }

    @Test
    fun `nothing runs on the caller's thread`() {
        val executor = HeldExecutor()
        val written = mutableListOf<Int>()
        DraftWriter(executor).submit("k") { written += 1 }
        assertEquals(emptyList<Int>(), written)
        executor.drain()
        assertEquals(listOf(1), written)
    }

    @Test
    fun `only the newest waiting draft per key is written`() {
        val executor = HeldExecutor()
        val writer = DraftWriter(executor)
        val written = mutableListOf<String>()
        (1..50).forEach { n -> writer.submit("a") { written += "a$n" } }
        writer.submit("b") { written += "b1" }
        assertEquals(2, executor.queued.size)
        executor.drain()
        assertEquals(listOf("a50", "b1"), written)
    }

    @Test
    fun `a draft submitted after the last write ran is written too`() {
        val executor = HeldExecutor()
        val writer = DraftWriter(executor)
        val written = mutableListOf<Int>()
        writer.submit("k") { written += 1 }
        executor.drain()
        writer.submit("k") { written += 2 }
        executor.drain()
        assertEquals(listOf(1, 2), written)
    }

    @Test
    fun `a failing write does not stop the next`() {
        val executor = HeldExecutor()
        val writer = DraftWriter(executor)
        val written = mutableListOf<Int>()
        writer.submit("k") { error("disk full") }
        executor.drain()
        writer.submit("k") { written += 2 }
        executor.drain()
        assertEquals(listOf(2), written)
    }
}
