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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audible and haptic scan feedback.
 *
 * Never silent, and success and failure are deliberately not distinguished by pitch alone:
 * success is one short tone with a brief tick, failure is a longer tone with a long buzz. A
 * picker wearing ear defenders feels the difference; one in a noisy aisle hears it. Both
 * channels always fire, because either one alone gets missed under real conditions.
 *
 * **Off the main thread (20 Nov 2026).** Vibrating is a synchronous binder call into
 * system_server, and creating a [ToneGenerator] opens an audio session - both can block. Crash
 * testing froze the app for minutes on the transfer screen: system_server's binder threads were
 * saturated, the main thread sat in `VibratorManager.vibrate` inside a scan callback, and Android
 * raised "isn't responding". So every cue now runs on one background thread, in order. The
 * queue is short and drops the oldest cue when full: when the system is that slow, a buzz for a
 * scan from seconds ago helps nobody, and the newest one is the one the worker is waiting for.
 */
@Singleton
class ScanFeedback internal constructor(
    private val context: Context,
    private val executor: Executor,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, feedbackExecutor())

    /**
     * The shared generator, created on first use and again after [release] - this class is a
     * singleton that outlives the activity whose onDestroy releases it, so a generator released
     * on rotation must not be the one the next scan reaches for. Touched only on [executor].
     */
    private var toneGenerator: ToneGenerator? = null

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }

    fun success() = cue(SUCCESS_VIBRATION_MS, ToneGenerator.TONE_PROP_BEEP, SUCCESS_TONE_MS)

    fun error() = cue(ERROR_VIBRATION_MS, ToneGenerator.TONE_SUP_ERROR, ERROR_TONE_MS)

    /** Soft validation, so an override prompt is not mistaken for a hard stop. */
    fun warning() = cue(WARNING_VIBRATION_MS, ToneGenerator.TONE_PROP_NACK, WARNING_TONE_MS)

    /** Released when the hosting Activity is destroyed; the audio session is a finite resource. */
    fun release() {
        executor.execute {
            runCatching { toneGenerator?.release() }
            toneGenerator = null
        }
    }

    /** Returns at once on any thread; the vibration and tone happen on [executor]. */
    private fun cue(vibrationMs: Long, tone: Int, toneMs: Int) {
        executor.execute {
            vibrate(vibrationMs)
            playTone(tone, toneMs)
        }
    }

    private fun playTone(tone: Int, durationMs: Int) {
        runCatching {
            val generator = toneGenerator
                ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME).also { toneGenerator = it }
            // With an explicit duration startTone returns at once and the tone stops by itself.
            generator.startTone(tone, durationMs)
        }
    }

    private fun vibrate(durationMs: Long) {
        runCatching {
            val effect = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)
                    ?.vibrate(CombinedVibration.createParallel(effect))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(effect)
            }
        }
    }

    internal companion object {
        const val TONE_VOLUME = 100
        const val SUCCESS_TONE_MS = 90
        const val ERROR_TONE_MS = 400
        const val WARNING_TONE_MS = 220
        const val SUCCESS_VIBRATION_MS = 40L
        const val ERROR_VIBRATION_MS = 500L
        const val WARNING_VIBRATION_MS = 180L

        /** Cues waiting behind a slow one; past this the oldest is dropped. */
        const val MAX_PENDING_CUES = 4

        /** One daemon thread, in-order, bounded, oldest cue dropped when full. */
        fun feedbackExecutor(): Executor = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(MAX_PENDING_CUES),
            { runnable -> Thread(runnable, "scan-feedback").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardOldestPolicy(),
        )
    }
}
