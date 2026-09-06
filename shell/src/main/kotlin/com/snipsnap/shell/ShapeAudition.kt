package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.PadShape
import com.snipsnap.synth.PadFilter

/**
 * What HIT plays on the PAD SHEET once a pad carries a shape: the WAV on
 * disk is pristine (the hardware renders the shape from metadata), so an
 * honest audition has to approximate it the way `KitPreview` does —
 * [PadShape]'s envelope, then the filter half through [PadFilter].
 * Resonance maps 0..1 onto the filter's damping (2 = none, down to a
 * ring at the top), the same 0..12 dB intent the SFZ writer declares.
 * No shape at all hands back the very same object, so an unshaped pad
 * auditions the bytes on disk.
 */
object ShapeAudition {

    /** Damping at resonance 1.0 — a clear ring, never self-oscillation. */
    const val MIN_DAMPING = 0.25f

    fun damping(resonance: Float): Float = 2f - resonance.coerceIn(0f, 1f) * (2f - MIN_DAMPING)

    fun render(snip: Snip, pad: KitPad): Snip =
        render(snip, pad.attack, pad.decay, pad.cutoff, pad.resonance)

    fun render(snip: Snip, attack: Float?, decay: Float?, cutoff: Float?, resonance: Float?): Snip {
        val shaped = PadShape.envelope(snip, attack, decay)
        if (cutoff == null) return shaped
        return PadFilter.lowpass(shaped, PadShape.cutoffHz(cutoff), damping(resonance ?: 0f))
    }
}
