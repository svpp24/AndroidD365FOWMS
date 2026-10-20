package com.d365fo.mobileops.data.sync

import kotlin.random.Random

/**
 * A random wait before background work starts, so hundreds of handhelds do not hit D365FO in
 * the same second - at shift start, or when a fleet goes on charge together. D365FO allows 52
 * concurrent requests per app registration per server; a synchronised fleet is what reaches it.
 * Only for background work: anything a worker tapped goes out at once.
 */
object FleetJitter {
    fun millisUpTo(maxMs: Long, random: Random = Random.Default): Long =
        if (maxMs <= 0) 0 else random.nextLong(maxMs)
}
