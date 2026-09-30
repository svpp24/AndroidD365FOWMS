package com.d365fo.mobileops.di

import okhttp3.Interceptor

/**
 * Release builds never log request/response bodies - quantities, prices, and worker identity
 * have no business in logcat on a device that leaves the building. Debug builds get the real
 * interceptor; see the debug source set's copy of this file.
 */
internal fun debugHttpLoggingInterceptor(headerToRedact: String): Interceptor? = null
