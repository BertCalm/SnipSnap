package com.snipsnap.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The phone's motor as part of the instrument: a short tick under a pad, two
 * when the tape arms, a light notch on a stepper, a firm one when a snip
 * lands. Four verbs and no more - [pad], [arm], [tick], [land] - so a
 * screen asks for a feeling and never for a duration.
 *
 * **Silent whenever the mic is armed.** The phone's microphone sits a few
 * centimetres from its motor and hears it: a tick under a pad would be on
 * the tape under the sound the pad made. [allowed] is the one gate, and App
 * builds it from the HAPTICS switch on SETUP and from the dock's capture
 * light (`DockModel.Capture.Armed`), read at the moment of each call and
 * never cached, so the switch and the mic both take effect on the very next
 * hit. [arm] is the one buzz that belongs to the armed state, and it is
 * fired on the request, before the session starts: the gate is still open
 * then, and the tape has not begun.
 *
 * `minSdk` is 29, which is where [VibrationEffect.createPredefined] begins,
 * so the predefined ticks need no version guard; the only branch is the
 * vibrator itself, which moved to `VibratorManager` in 31.
 *
 * Reached through [LocalHaptics], not threaded through parameters: a pad
 * grid is four composables deep and a stepper is anywhere. The default is
 * [Silent], so a screen composed without App (a preview, an instrumented
 * test) feels nothing and needs no stand-in.
 */
class Haptics(
    private val vibrator: Vibrator?,
    private val allowed: () -> Boolean,
) {

    /**
     * One short tick under a pad hit. The amplitude follows [velocity]
     * (0..1, the grid's own touch-Y reading) from [MIN_AMPLITUDE] to 255 and
     * the length from [MIN_PAD_MS] to [MAX_PAD_MS], so a soft hit is a
     * lighter, shorter touch than a hard one.
     */
    fun pad(velocity: Float) {
        val v = velocity.coerceIn(0f, 1f)
        val amplitude = (MIN_AMPLITUDE + (MAX_AMPLITUDE - MIN_AMPLITUDE) * v).toInt()
        val ms = MIN_PAD_MS + ((MAX_PAD_MS - MIN_PAD_MS) * v).toLong()
        play { VibrationEffect.createOneShot(ms, amplitude) }
    }

    /** Two ticks, the second answering the first: the tape is armed. */
    fun arm() {
        play {
            VibrationEffect.createWaveform(
                longArrayOf(0L, ARM_TICK_MS, ARM_GAP_MS, ARM_TICK_MS),
                intArrayOf(0, ARM_AMPLITUDE, 0, ARM_AMPLITUDE),
                NO_REPEAT,
            )
        }
    }

    /** A light notch: a stepper moved one place. */
    fun tick() {
        play { VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK) }
    }

    /** One firm tick: a snip, or a send, landed. */
    fun land() {
        play { VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK) }
    }

    private inline fun play(effect: () -> VibrationEffect) {
        val motor = vibrator ?: return
        if (!allowed() || !motor.hasVibrator()) return
        motor.vibrate(effect())
    }

    companion object {
        /** A [Haptics] that never moves the motor: the [LocalHaptics] default. */
        val Silent = Haptics(null) { false }

        /** The device's motor under [allowed], or a silent one on hardware that has none. */
        fun of(context: Context, allowed: () -> Boolean): Haptics {
            val motor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            return Haptics(motor, allowed)
        }

        private const val MIN_AMPLITUDE = 60
        private const val MAX_AMPLITUDE = 255
        private const val MIN_PAD_MS = 8L
        private const val MAX_PAD_MS = 14L
        private const val ARM_TICK_MS = 12L
        private const val ARM_GAP_MS = 55L
        private const val ARM_AMPLITUDE = 200
        private const val NO_REPEAT = -1
    }
}

/** The motor, for any screen that wants a feeling. App provides the real one; the default is [Haptics.Silent]. */
val LocalHaptics = staticCompositionLocalOf { Haptics.Silent }
