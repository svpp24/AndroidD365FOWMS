package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ListCacheTest {
    private var clock = 0L
    private val cache = ListCache().apply { now = { clock } }
    private var calls = 0
    private suspend fun fetch(): NetworkResult<List<String>> { calls++; delay(10); return NetworkResult.Success(listOf("PO-$calls")) }

    @Test fun answerIsSharedUntilItExpires() = runTest {
        assertEquals(NetworkResult.Success(listOf("PO-1")), cache.get("k") { fetch() })
        clock += ListCache.TTL_MS - 1
        assertEquals(NetworkResult.Success(listOf("PO-1")), cache.get("k") { fetch() })
        clock += 1
        assertEquals(NetworkResult.Success(listOf("PO-2")), cache.get("k") { fetch() })
        assertEquals(2, calls)
    }

    @Test fun callersAtTheSameMomentShareOneRequest() = runTest {
        val answers = List(3) { async { cache.get("k") { fetch() } } }.awaitAll()
        assertEquals(1, calls)
        answers.forEach { assertEquals(NetworkResult.Success(listOf("PO-1")), it) }
    }

    @Test fun failuresAreNotKeptAndInvalidateAsksAgain() = runTest {
        cache.get<List<String>>("k") { calls++; NetworkResult.Error(AppFailure.Offline("down")) }
        cache.get("k") { fetch() }
        assertEquals(2, calls)
        cache.invalidate("k")
        cache.get("k") { fetch() }
        assertEquals(3, calls)
        cache.get("other") { fetch() }
        cache.invalidate()
        cache.get("other") { fetch() }
        assertEquals(5, calls)
    }
}
