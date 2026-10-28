package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.pow

/** Three sampled contact strengths using CREASE's existing model-3 pressure and memory.
 * This changes which independently rendered timbre a key touch selects. It does not add
 * realtime synthesis, aftertouch, or a separately simulated note-off trajectory.
 *
 * Every layer retains its own real onset, settling prefix and verified sustain loop.
 * Sustain loudness is matched before the player's ordinary velocity gain: otherwise the
 * renderer's velocity loudness curve would cause an extra level step at each layer boundary.
 * One bank-wide attenuation protects all attacks without separately flattening their levels.
 * The ordinary single-layer CreaseKeys path and all historical renders remain unchanged.
 */
object CreaseTouchKeys {
    const val RENDER_VERSION = 1
    /** Below the smallest normal Float, contact-strength scaling can round conditioned
     * audio to exact zero while the inherited key renderer still expects a nonzero seam.
     * Keep exact zero as intentional silence, and refuse positive subnormal anchors here. */
    const val MIN_POSITIVE_ANCHOR = java.lang.Float.MIN_NORMAL
    val TOUCHES: List<Float> = listOf(.35f, .65f, 1f)
    val VELOCITY_WINDOWS: List<IntRange> = listOf(1..42, 43..84, 85..127)

    /** [probe]'s note and independent preceding cycle already carry both gains.
     * The complete conditioned [CreaseKeyProbe.prelevel], state and trace remain unscaled.
     * The final uniform audio gain is [levelGain] times [Bank.sharedHeadroomGain]. */
    data class Layer(
        val touch: Float,
        val velocityWindow: IntRange,
        val excitationVelocity: Float,
        val probe: CreaseKeyProbe,
        val levelGain: Float,
    )

    data class Bank(val layers: List<Layer>, val sharedHeadroomGain: Float)

    fun render(
        patch: CreasePatch,
        midi: Int = patch.midi,
        cancelled: () -> Boolean = { false },
    ): List<KeyNote> = generate(patch, midi, 4, false, cancelled).first.map { it.note }

    fun probe(
        patch: CreasePatch,
        midi: Int = patch.midi,
        oversample: Int = 4,
        cancelled: () -> Boolean = { false },
    ): Bank {
        val (prepared, sharedGain) = generate(patch, midi, oversample, true, cancelled)
        return Bank(prepared.map { layer ->
            Layer(layer.touch, layer.window, layer.velocity, checkNotNull(layer.probe), layer.levelGain)
        }, sharedGain)
    }

    private data class Prepared(
        val touch: Float, val window: IntRange, val velocity: Float,
        val note: KeyNote, var probe: CreaseKeyProbe?, val levelGain: Float,
    )

    /** The render route releases each source's diagnostic/prelevel arrays before starting
     * the next layer. Only probe callers retain all three complete diagnostic histories. */
    private fun generate(patch: CreasePatch, midi: Int, oversample: Int, diagnostics: Boolean,
        cancelled: () -> Boolean): Pair<List<Prepared>, Float> {
        require(CreaseKeys.supports(patch)) { "CREASE touch keys support model ${CreaseKeys.SUPPORTED_MODEL} only" }
        require(midi in Crease.MIDI_MIN..Crease.MIDI_MAX) {
            "CREASE touch keys support MIDI ${Crease.MIDI_MIN}..${Crease.MIDI_MAX}"
        }
        require(oversample == 4 || oversample == 16) { "CREASE touch diagnostic oversample must be 4 or 16" }
        require(patch.velocity == 0f || patch.velocity >= MIN_POSITIVE_ANCHOR) {
            "CREASE touch velocity must be zero or at least $MIN_POSITIVE_ANCHOR; positive subnormal anchors are unsupported"
        }
        fun checkCancelled() {
            if (cancelled() || Thread.currentThread().isInterrupted)
                throw CancellationException("CREASE touch render no longer wanted")
        }
        fun scale(samples: FloatArray, gain: Float) {
            for (i in samples.indices) {
                if (i % 8192 == 0) checkCancelled()
                samples[i] *= gain
            }
        }
        checkCancelled()
        val target = Dsp.MELODIC_LOUDNESS_TARGET * patch.velocity.pow(.4f)
        val layers = ArrayList<Prepared>(TOUCHES.size)
        var bankPeak = 0f
        for ((index, touch) in TOUCHES.withIndex()) {
            checkCancelled()
            val velocity = patch.velocity * touch
            val source = CreaseKeys.probe(patch.copy(velocity = velocity), midi,
                oversample = oversample, normalize = false, cancelled = cancelled)
            checkCancelled()
            val note = source.note
            val samples = note.snip.samples
            val loop = samples.copyOfRange(note.loopStartFrame.toInt(), samples.size)
            val measured = Loudness.of(loop, note.snip.channels, note.snip.sampleRate)
            // No per-layer peak limiting. All layers first receive the same sustain
            // target; headroom is then a common scalar chosen over their entire notes.
            val gain = if (measured > 0f && target > 0f) target / measured else 1f
            check(gain.isFinite()) { "CREASE touch sustain gain is not finite" }
            scale(samples, gain)
            if (diagnostics) scale(source.previousCycle, gain)
            bankPeak = max(bankPeak, Dsp.finitePeak(samples))
            layers.add(Prepared(touch, VELOCITY_WINDOWS[index], velocity, note,
                if (diagnostics) source else null, gain))
        }
        checkCancelled()
        val sharedGain = if (bankPeak > .99f) .99f / bankPeak else 1f
        for (layer in layers) {
            checkCancelled()
            if (sharedGain != 1f) {
                scale(layer.note.snip.samples, sharedGain)
                layer.probe?.let { scale(it.previousCycle, sharedGain) }
            }
            layer.probe?.let { p ->
                val seam = if (patch.velocity == 0f) 0.0 else Keys.seamError(
                    layer.note.snip.samples, layer.note.loopStartFrame.toInt())
                check(seam.isFinite() && seam < Keys.MAX_SEAM_ERROR) { "CREASE touch audio did not recur: $seam" }
                layer.probe = p.copy(diagnostics = p.diagnostics.copy(seamError = seam))
            }
        }
        checkCancelled()
        return layers to sharedGain
    }
}
