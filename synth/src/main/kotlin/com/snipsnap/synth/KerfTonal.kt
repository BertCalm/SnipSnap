package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.pow

/**
 * KERF's pitched notes (the pitched-pad round, 2026-11-21): a struck bar, bell, gong or fork, fitted from a melodic loop
 * and played back at any note. The owner asked for bells, struck bars and the like, from loops they supplied, as
 * pitched pads laid out as a scale in each kit's key.
 *
 * A note is its fitted patch and the root the fit found ([KerfTonalNote.rootMidi], fractional: a bell's tuning in a
 * loop is rarely exactly on a semitone). [render] plays it at a target MIDI note: the whole patch (partials, resonance
 * bank, noise bands) moves by the exact difference, so the length stays the note's own, and the ring runs past the
 * loop's cut ([KerfRender.ring]).
 *
 * Velocity is [KerfVelocity.strike] at the owner's depth, the drums' law: softer is quieter against the wash, darker,
 * with a softer crack. A take re-strikes the bar: every partial's level moves up to [TAKE_LEVEL_DB] and its tuning up to
 * [TAKE_CENTS], and the noise is a fresh draw ([KerfVelocity.takeSeed]). A bell is mostly partials, so the noise alone
 * would leave its takes near copies.
 */
data class KerfTonalNote(val patch: KerfPatchData, val rootMidi: Double)

object KerfTonal {
    /** How far a take moves each partial's level, either way, in dB. Shape, not measured. */
    const val TAKE_LEVEL_DB = 1.0

    /** How far a take moves each partial's tuning, either way, in cents. Shape, not measured. */
    const val TAKE_CENTS = 2.0

    /** How far a note may be retuned from its root, either way, in semitones: an octave. */
    const val MAX_SHIFT = 12.0

    /** [note] played at MIDI [midi], struck at [velocity] (0..1), take [take]. Velocity 1, take 0 is the fitted strike. */
    fun render(note: KerfTonalNote, midi: Double, velocity: Double = 1.0, take: Int = 0, decay: Double = 1.0): Snip {
        val shift = midi - note.rootMidi
        require(kotlin.math.abs(shift) <= MAX_SHIFT + 1e-9) { "a KERF note retunes at most $MAX_SHIFT semitones: $shift" }
        var p = KerfVelocity.strike(note.patch, velocity)
        if (take > 0) p = restrike(p, take)
        return KerfRender.toSnip(KerfRender.ring(p, KerfRender.Controls(tuneSemitones = shift, decay = decay)))
    }

    /** Take [take] of [patch]: each partial's level and tuning nudged, from the patch's own seed, and fresh noise. */
    internal fun restrike(patch: KerfPatchData, take: Int): KerfPatchData {
        val noise = Dsp.Noise(Dsp.seedFor(patch.seed, "tonal-take", take))
        fun unit() = noise.next().toDouble().coerceIn(-1.0, 1.0)
        return patch.copy(
            seed = KerfVelocity.takeSeed(patch.seed, take),
            partials = patch.partials.map {
                it.copy(amp = it.amp * 10.0.pow(TAKE_LEVEL_DB * unit() / 20.0),
                    hzEnd = it.hzEnd * 2.0.pow(TAKE_CENTS * unit() / 1200.0))
            },
        )
    }
}
