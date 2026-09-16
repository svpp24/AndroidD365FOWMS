package com.d365fo.mobileops.di

import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor

/**
 * The debug-variant half of this pair - see [debugHttpLoggingInterceptor]'s doc on the release
 * side for why a pair of source-set files exists instead of one conditional in `NetworkModule`.
 *
 * HEADERS, not BODY - see the long comment at the [NetworkModule] call site for the measured
 * reason (a 15 KB response taking 29 s to log versus 117 ms to actually answer).
 */
internal fun debugHttpLoggingInterceptor(headerToRedact: String): Interceptor =
    HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.HEADERS
        redactHeader(headerToRedact)
    }
