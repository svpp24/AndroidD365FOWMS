package com.d365fo.mobileops.presentation.common.scan

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audible and haptic scan feedback.
 *
 * Never silent, and success and failure are deliberately not distinguished by pitch alone:
 * success is one short tone with a brief tick, failure is a longer tone with a long buzz. A
 * picker wearing ear defenders feels the difference; one in a noisy aisle hears it. Both
 * channels always fire, because either one alone gets missed under real conditions.
 */
@Singleton
class ScanFeedback @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * One shared generator. [ToneGenerator.startTone] with an explicit duration is
     * asynchronous — it returns immediately and the tone stops on its own — so this is safe
     * to call from the main thread during a scan callback.
     */
    private val toneGenerator: ToneGenerator? by lazy {
        runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME)
        }.getOrNull()
    }

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }

    fun success() {
        vibrate(SUCCESS_VIBRATION_MS)
        playTone(ToneGenerator.TONE_PROP_BEEP, SUCCESS_TONE_MS)
    }

    fun error() {
        vibrate(ERROR_VIBRATION_MS)
        playTone(ToneGenerator.TONE_SUP_ERROR, ERROR_TONE_MS)
    }

    /** Soft validation, so an override prompt is not mistaken for a hard stop. */
    fun warning() {
        vibrate(WARNING_VIBRATION_MS)
        playTone(ToneGenerator.TONE_PROP_NACK, WARNING_TONE_MS)
    }

    /** Released when the hosting Activity is destroyed; the audio session is a finite resource. */
    fun release() {
        runCatching { toneGenerator?.release() }
    }

    private fun playTone(tone: Int, durationMs: Int) {
        runCatching { toneGenerator?.startTone(tone, durationMs) }
    }

    private fun vibrate(durationMs: Long) {
        val effect = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)
                    ?.vibrate(CombinedVibration.createParallel(effect))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(effect)
            }
        }
    }

    private companion object {
        const val TONE_VOLUME = 100
        const val SUCCESS_TONE_MS = 90
        const val ERROR_TONE_MS = 400
        const val WARNING_TONE_MS = 220
        const val SUCCESS_VIBRATION_MS = 40L
        const val ERROR_VIBRATION_MS = 500L
        const val WARNING_VIBRATION_MS = 180L
    }
}
