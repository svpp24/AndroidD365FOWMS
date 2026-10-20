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
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps the Play Integrity Standard API: one warm token provider per process (preparing it is
 * the expensive step, several seconds on a cold start; requesting a token from an already-warm
 * provider is cheap), exposed as a plain suspend function. See `licensing/README.md` and
 * `azure-license-function/playintegrity.py` for what AOS/Azure do with the token this hands
 * them - this class only ever obtains it, never evaluates it. The pass/fail decision lives
 * entirely server-side, on purpose: nothing about "is this install genuine" should be something
 * a modified client could answer for itself, same reasoning [SelfIntegrityCheck] already
 * documents for its own, narrower, local-only check.
 *
 * **Never throws.** Any failure - no cloud project configured, Play Services unavailable, a
 * network hiccup preparing or requesting the token - returns null.
 * `HSMobileOpsLicense::verifyWithAttestation` treats a missing token as an UNVERIFIED verdict,
 * not a hard failure, deliberately - see that method's own doc comment on why, especially during
 * the rollout window before every device in a fleet has updated to a build carrying this class.
 */
@Singleton
class PlayIntegrityTokenProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager: StandardIntegrityManager by lazy { IntegrityManagerFactory.createStandard(context) }

    // Warmed once, lazily, on first use rather than blocking app start - most launches never
    // need a token at all (e.g. the license check is already inside its offline grace period).
    @Volatile
    private var cachedProvider: StandardIntegrityTokenProvider? = null

    suspend fun requestToken(): String? {
        val cloudProjectNumber = BuildConfig.INTEGRITY_CLOUD_PROJECT_NUMBER.toLongOrNull()
        if (cloudProjectNumber == null || cloudProjectNumber <= 0L) {
            Timber.w("INTEGRITY_CLOUD_PROJECT_NUMBER not configured - skipping Play Integrity")
            return null
        }

        val provider = cachedProvider ?: preparedProvider(cloudProjectNumber)?.also { cachedProvider = it } ?: return null

        return runCatching { requestFrom(provider) }
            .onFailure { Timber.w(it, "Play Integrity token request failed") }
            .getOrNull()
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
        }.onFailure { Timber.w(it, "Play Integrity prepareIntegrityToken failed") }.getOrNull()

    private suspend fun requestFrom(provider: StandardIntegrityTokenProvider): String =
        suspendCancellableCoroutine { cont ->
            provider.request(StandardIntegrityTokenRequest.builder().build())
                .addOnSuccessListener { cont.resume(it.token()) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
}
