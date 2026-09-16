package com.d365fo.mobileops.di

import okhttp3.Interceptor

/**
 * The release-variant half of this pair.
 *
 * `okhttp-logging-interceptor` is `debugImplementation`-only (see `build.gradle.kts`) - deliberately,
 * so a logging class that can dump headers (and, misconfigured, a bearer token or a response body)
 * never ships in the APK operators actually carry. That means the class itself is not on this
 * variant's classpath, not just unused at runtime: `NetworkModule`'s `BuildConfig.ALLOW_SENSITIVE_LOGGING`
 * check already keeps this from being *called* in release, but Kotlin still has to compile the
 * call site, which is what this stub exists for. Returns null rather than a real interceptor -
 * "no logging interceptor in this variant" is the honest answer, not a disguised no-op.
 */
internal fun debugHttpLoggingInterceptor(headerToRedact: String): Interceptor? = null
