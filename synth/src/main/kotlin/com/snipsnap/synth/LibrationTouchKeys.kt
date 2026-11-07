package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.pow

/**
 * Two captures of the existing held exciter. TOUCH remains the hard anchor;
 * the softer capture changes its velocity-dependent partials and attack smoothing.
 * Trajectories, seeds, pitch and saved source models remain the same.
 *
 * Remove only the softer capture's relative velocity^.7 level law. The authored
 * TOUCH still sets the reference level, and the player applies live note gain once.
 * A common peak guard protects both captures without matching their loudness.
 */
object LibrationTouchKeys {
    const val RENDER_VERSION = 1
    // Musical capture and switch points, not calibrated physical forces.
    val TOUCHES: List<Float> = listOf(.35f, 1f)
    val VELOCITY_WINDOWS: List<IntRange> = listOf(1..63, 64..127)

    data class Layer(
        val strength: Float,
        val velocityWindow: IntRange,
        val sourcePatch: LibrationPatch,
        val note: KeyNote,
        /** Multiply the native held capture by this value to reproduce the delivery sample. */
        val sampleGain: Double,
        val diagnostics: LibrationKeys.Diagnostics,
    )

    fun sourcePatch(patch: LibrationPatch, midi: Int, strength: Float): LibrationPatch {
        require(LibrationKeys.supports(patch)) { "LIBRATION touch keys require source model 3" }
        require(strength in TOUCHES) { "LIBRATION touch strength must be one of $TOUCHES" }
        return patch.copy(midi = midi, velocity = patch.velocity * strength,
            macros = patch.macros + ("HOLD" to 1f), heldModel = LibrationKeys.MODEL_VERSION)
    }

    /** Soft first, serially, sharing the source table across touches and roots. */
    fun capture(patch: LibrationPatch, midi: Int = patch.midi,
                session: LibrationKeys.Session = LibrationKeys.Session(),
                cancelled: () -> Boolean = { false }): List<Layer> {
        fun stop() {
            if (cancelled() || Thread.currentThread().isInterrupted)
                throw CancellationException("LIBRATION touch capture cancelled")
        }
        stop()
        val sources = TOUCHES.map { sourcePatch(patch, midi, it) }
        val captures = sources.map { source ->
            stop()
            session.render(source, midi, cancelled)
        }
        val gains = sources.map { source ->
            if (source.velocity == 0f) 1.0 else (patch.velocity.toDouble() / source.velocity).pow(.7)
        }
        var peak = 0.0
        for ((index, rendered) in captures.withIndex()) {
            for ((frame, value) in rendered.note.snip.samples.withIndex()) {
                if (frame % 8192 == 0) stop()
                peak = maxOf(peak, abs(value * gains[index]))
            }
        }
        val commonGain = if (peak > .94) .94 / peak else 1.0
        return captures.mapIndexed { index, rendered ->
            stop()
            val gain = gains[index] * commonGain
            val source = rendered.note.snip
            val samples = FloatArray(source.samples.size) { frame ->
                if (frame % 8192 == 0) stop()
                (source.samples[frame] * gain).toFloat()
            }
            Layer(TOUCHES[index], VELOCITY_WINDOWS[index], sources[index],
                KeyNote(Snip(samples, source.channels, source.sampleRate), rendered.note.loopStartFrame),
                gain, rendered.diagnostics)
        }
    }
}
