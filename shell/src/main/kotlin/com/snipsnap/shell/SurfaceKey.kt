package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Scale
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Tuner

/**
 * The kit's key, in the shape SURFACE's GRAIN mode hands the engine: a
 * root pitch class, the scale as twelve bits, and the loaded pad's own
 * note. The snapping itself - which note a pitch lands on - lives once, in
 * `app/src/main/cpp/Grain.h`, where the host harness can hold it to
 * `Scales.nearestInKey`'s answers; this object only decides what to *tell*
 * it, which is the part with three cases worth naming:
 *
 *  - a key and a pad whose pitch was found: the snap is to real notes in
 *    the key, and a pad a little off one is pulled onto it;
 *  - a key but no confident pitch (a drum, a texture): the scale's degrees
 *    become intervals from the pad itself - root 0, source 0 - so a slide
 *    up the pad still steps through the key's *shape*;
 *  - no key at all: chromatic, so the pitch axis is a semitone ladder,
 *    around the pad's own note when that is known.
 */
object SurfaceKey {

    /** Every pitch class allowed: what the engine reads "no key" as. */
    const val CHROMATIC_MASK = 0xFFF

    data class Snap(val rootSemitone: Int, val scaleMask: Int, val sourceMidi: Float) {
        init {
            require(rootSemitone in 0..11) { "root is 0..11 semitones above C, got $rootSemitone" }
            require(scaleMask in 1..CHROMATIC_MASK) { "the scale mask is twelve bits with at least one set, got $scaleMask" }
            require(sourceMidi.isFinite()) { "the source note is a number, got $sourceMidi" }
        }

        companion object {
            /** No key, no known note: a semitone ladder around the pad as recorded. */
            val CHROMATIC = Snap(0, CHROMATIC_MASK, 0f)
        }
    }

    /** [scale]'s degrees as bits: bit d set means d semitones above the root is in the scale. */
    fun mask(scale: Scale): Int = scale.intervals.fold(0) { acc, d -> acc or (1 shl d) }

    /** The three cases above, decided. */
    fun of(key: KeySpec?, sourceMidi: Float?): Snap = when {
        key == null -> Snap(0, CHROMATIC_MASK, sourceMidi ?: 0f)
        sourceMidi == null -> Snap(0, mask(key.scale), 0f)
        else -> Snap(key.rootSemitone, mask(key.scale), sourceMidi)
    }

    /**
     * The pad's own note as a (fractional) MIDI number, or null when the
     * detector is not confident - the same bar [Tuner.inKey] sets, because
     * a guessed note snapped into key is a wrong note played with
     * conviction.
     */
    fun sourceMidi(snip: Snip): Float? {
        val estimate = Pitch.detect(snip) ?: return null
        if (estimate.confidence < Tuner.MIN_CONFIDENCE) return null
        return Scales.hzToMidi(estimate.hz)
    }
}
