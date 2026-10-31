package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.max

/**
 * Three independently captured disturbance strengths for held RIBBON. The saved
 * velocity is the hard-touch anchor; softer touches scale the initial SCATTER
 * disturbance as well as the source amplitude. This is sampled articulation,
 * not realtime flock synthesis or aftertouch. At SCATTER zero the three source
 * histories can coincide, as the model's velocity contract requires.
 *
 * Every layer retains [FlockHeld]'s own opening, selected endpoint and blend.
 * Softer sustain levels are matched to the saved hard capture before the player's
 * ordinary linear velocity gain, so a layer boundary does not also switch the
 * renderer's loudness curve. One shared attenuation over all three complete notes
 * protects their attacks; otherwise the hard capture stays byte-identical.
 * The existing single-layer render and its saved capture version stay unchanged.
 */
object FlockTouchKeys {
    const val RENDER_VERSION = 1
    val TOUCHES: List<Float> = listOf(.35f, .65f, 1f)
    val VELOCITY_WINDOWS: List<IntRange> = listOf(1..42, 43..84, 85..127)

    /** [note] carries [levelGain] times [Bank.sharedHeadroomGain].
     * [sourceDiagnostics] describes the original capture before these two gains. */
    data class Layer(
        val touch: Float,
        val velocityWindow: IntRange,
        val excitationVelocity: Float,
        val note: KeyNote,
        val sourceDiagnostics: FlockHeld.Diagnostics,
        val levelGain: Double,
    )

    data class Bank(val layers: List<Layer>, val sharedHeadroomGain: Double)

    /** Soft-first complete samples, ready for the existing velocity-window writer. */
    fun render(
        patch: FlockPatch,
        midi: Int = patch.midi,
        cancelled: () -> Boolean = { false },
    ): List<KeyNote> = probe(patch, midi, cancelled).layers.map { it.note }

    /** Uses the same bounded source cache as a single held note. The returned
     * arrays belong to this call; matching a touch layer never changes that cache. */
    fun probe(
        patch: FlockPatch,
        midi: Int = patch.midi,
        cancelled: () -> Boolean = { false },
    ): Bank {
        require(patch.playback == FlockPlayback.HELD && patch.voice == FlockVoice.RIBBON) {
            "FLOCK touch keys require held RIBBON"
        }
        require(midi in Flock.MIN_MIDI..Flock.MAX_MIDI) {
            "FLOCK touch keys support MIDI ${Flock.MIN_MIDI}..${Flock.MAX_MIDI}"
        }
        fun checkCancelled() {
            if (cancelled() || Thread.currentThread().isInterrupted)
                throw CancellationException("FLOCK touch render no longer wanted")
        }
        fun scale(samples: FloatArray, gain: Double): Double {
            var peak = 0.0
            for (i in samples.indices) {
                if (i % 4096 == 0) checkCancelled()
                samples[i] = (samples[i].toDouble() * gain).toFloat()
                check(samples[i].isFinite()) { "FLOCK touch gain produced non-finite audio" }
                peak = max(peak, abs(samples[i].toDouble()))
            }
            return peak
        }
        checkCancelled()
        val captures = ArrayList<Pair<FlockHeld.Held, Double>>(TOUCHES.size)
        for (touch in TOUCHES) {
            checkCancelled()
            val velocity = patch.velocity * touch
            val held = FlockHeld.render(patch.copy(velocity = velocity), midi, cancelled)
            checkCancelled()
            val samples = held.snip.samples
            val loop = samples.copyOfRange(held.loopStartFrame, samples.size)
            val measured = Loudness.of(loop, channels = 1, sampleRate = Dsp.RATE).toDouble()
            check(measured.isFinite()) { "FLOCK touch sustain level is not finite" }
            captures += held to measured
        }
        // The accepted hard performance supplies the level reference rather than
        // being relevelled to a new target. Double ratios also accept tiny saved
        // velocities. A digitally silent layer is never divided by zero.
        val target = captures.last().second
        val layers = ArrayList<Layer>(TOUCHES.size)
        var bankPeak = 0.0
        for ((index, touch) in TOUCHES.withIndex()) {
            checkCancelled()
            val (held, measured) = captures[index]
            val gain = when {
                index == TOUCHES.lastIndex -> 1.0
                target == 0.0 -> 0.0
                measured > 0.0 -> target / measured
                else -> 1.0
            }
            check(gain.isFinite()) { "FLOCK touch sustain gain is not finite" }
            bankPeak = max(bankPeak, scale(held.snip.samples, gain))
            layers += Layer(touch, VELOCITY_WINDOWS[index], patch.velocity * touch,
                KeyNote(held.snip, held.loopStartFrame.toLong()), held.diagnostics, gain)
        }
        checkCancelled()
        val sharedGain = if (bankPeak > .90) .90 / bankPeak else 1.0
        if (sharedGain != 1.0) for (layer in layers) scale(layer.note.snip.samples, sharedGain)
        checkCancelled()
        return Bank(layers, sharedGain)
    }
}
