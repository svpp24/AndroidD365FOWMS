package com.d365fo.mobileops.data.security

import android.content.Context
import com.d365fo.mobileops.BuildConfig
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps the Play Integrity Standard API: one warm token provider per process (preparing it is
 * the expensive step, several seconds on a cold start; requesting a token from an already-warm
 * provider is cheap), exposed as a plain suspend function. Since 15 Nov 2026 nothing evaluates
 * the token (there is no vendor server to decode it; D365FO ignores it) - it is still sent so a
 * later server-side check needs no app change. This class only ever obtains it. The pass/fail decision lives
 * entirely server-side, on purpose: nothing about "is this install genuine" should be something
 * a modified client could answer for itself, same reasoning [SelfIntegrityCheck] already
 * documents for its own, narrower, local-only check.
 *
 * **Never throws.** Any failure - no cloud project configured, Play Services unavailable, a
 * network hiccup preparing or requesting the token - returns null.
 * D365FO does not require the token, so a null never blocks a sign-in.
 */
@Singleton
class PlayIntegrityTokenProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager: StandardIntegrityManager by lazy { IntegrityManagerFactory.createStandard(context) }

    // Prepared once per process: by [warmUp] in the background when the app starts with nobody
    // signed in, else on first use. Never on the main thread or ahead of the first frame.
    @Volatile
    private var cachedProvider: StandardIntegrityTokenProvider? = null
    private val preparing = Mutex()

    /**
     * Prepares the provider ahead of a sign-in (performance plan, phase 4): the several seconds
     * it takes on a cold start then pass while the worker types, not after they tap Sign in.
     */
    suspend fun warmUp() {
        cloudProjectNumber()?.let { provider(it) }
    }

    /**
     * A token, or null. Waits at most [WAIT_MS]: nothing checks the token today, so a sign-in
     * must not wait on Play Services - it used to wait with no limit at all.
     */
    suspend fun requestToken(): String? {
        val cloudProjectNumber = cloudProjectNumber() ?: return null
        return withTimeoutOrNull(WAIT_MS) {
            val provider = provider(cloudProjectNumber) ?: return@withTimeoutOrNull null
            runCatching { requestFrom(provider) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Timber.w(it, "Play Integrity token request failed") }
                .getOrNull()
        }.also { if (it == null) Timber.w("Play Integrity: no token within %d ms - signing in without", WAIT_MS) }
    }

    private fun cloudProjectNumber(): Long? {
        val number = BuildConfig.INTEGRITY_CLOUD_PROJECT_NUMBER.toLongOrNull()
        if (number == null || number <= 0L) {
            Timber.w("INTEGRITY_CLOUD_PROJECT_NUMBER not configured - skipping Play Integrity")
            return null
        }
        return number
    }

    /** One preparation at a time: a sign-in during [warmUp] waits for it rather than starting another. */
    private suspend fun provider(cloudProjectNumber: Long): StandardIntegrityTokenProvider? =
        cachedProvider ?: preparing.withLock {
            cachedProvider ?: preparedProvider(cloudProjectNumber)?.also { cachedProvider = it }
        }

    private companion object {
        const val WAIT_MS = 5_000L
    }

    private suspend fun preparedProvider(cloudProjectNumber: Long): StandardIntegrityTokenProvider? =
        runCatching {
            suspendCancellableCoroutine<StandardIntegrityTokenProvider> { cont ->
                manager.prepareIntegrityToken(
                    PrepareIntegrityTokenRequest.builder()
                        .setCloudProjectNumber(cloudProjectNumber)
                        .build(),
                )
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Timber.w(it, "Play Integrity prepareIntegrityToken failed") }.getOrNull()

    private suspend fun requestFrom(provider: StandardIntegrityTokenProvider): String =
        suspendCancellableCoroutine { cont ->
            provider.request(StandardIntegrityTokenRequest.builder().build())
                .addOnSuccessListener { cont.resume(it.token()) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
}
