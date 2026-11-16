package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.*

/**
 * Opt-in sampler articulation, not a migration of the accepted finite recipes.
 * The opening slide and filter bite settle; the amplitude stops decaying at the
 * finite gate time. A half-second whole-period loop then holds that steady tone.
 * The sampler, rather than this source waveform, supplies the note-off release.
 */
object BristleHeld {
    const val MODEL_VERSION = 1
    const val LOOP_START_FRAME = Dsp.RATE
    data class Render(val note: KeyNote, val actualHz: Double, val seamError: Double)

    fun render(patch: BristlePatch, cancelled: () -> Boolean = { false }): Render {
        stop(cancelled)
        require(patch.velocity > 0f) { "Held bass needs a nonzero source" }
        val midi = Bristle.rootMidi(patch)
        val target = Bristle.frequencyFor(midi)
        val periods = (target * .5).roundToInt().coerceAtLeast(1)
        val frames = (periods * Dsp.RATE / target).roundToInt()
        val hz = periods * Dsp.RATE.toDouble() / frames
        // One second clears the longest finite gate (.72 s), bite and pitch slide.
        // The guard prevents decimator end padding from contaminating the loop.
        val start = LOOP_START_FRAME
        val end = start + frames
        val recipe = Bristle.Recipe(patch.voice, midi, patch.velocity, patch.macros, patch.model, patch.effects)
        val raw = Bristle.synthesizeHeldTake(recipe, (end + 2048) * Dsp.OVERSAMPLE, hz, patch.take, cancelled)
        val conditioned = Bristle.finish(raw, patch.velocity, fade = false, effects = patch.effects)
        val samples = conditioned.samples.copyOf(end)
        stop(cancelled)
        val seam = Keys.seamError(samples, start)
        require(seam.isFinite() && seam < Keys.MAX_SEAM_ERROR) { "Bristle sustain does not close: $seam" }
        return Render(KeyNote(Snip(samples, 1, Dsp.RATE), start.toLong()), hz, seam)
    }

    private fun stop(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Bristle sustain cancelled")
    }
}
