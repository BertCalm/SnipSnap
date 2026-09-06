package com.snipsnap.kit

import com.snipsnap.audio.Snip
import kotlin.math.max

/**
 * The pad shape's one reading, shared by everything that has to *hear*
 * or *translate* it before the card: `KitPreview`'s render, the SFZ
 * writer's opcodes, and the phone's HIT audition all go through here, so
 * a tighten sounds the same in every preview and the mapping lives in
 * one place.
 *
 * The hardware renders the real thing from the metadata fields
 * [KitPad.attack] / [KitPad.decay] / [KitPad.cutoff] / [KitPad.resonance];
 * this is honest about being an approximation: attack ramps in over up
 * to [ATTACK_MAX_SEC]; a decay of `d` fades the sound out by `d` × its
 * own length; cutoff maps 0..1 exponentially onto 20 Hz..20 kHz and
 * resonance onto 0..12 dB.
 */
object PadShape {

    /** A full attack ramps in over this long. */
    const val ATTACK_MAX_SEC = 0.4f

    /** Resonance at 1.0, in dB — the SFZ writer's own figure. */
    const val RESONANCE_MAX_DB = 12f

    fun attackSeconds(attack: Float): Float = attack * ATTACK_MAX_SEC

    /** Frames the attack ramp lasts; 0 when the pad has none. */
    fun attackFrames(attack: Float?, sampleRate: Int): Int =
        attack?.let { (attackSeconds(it) * sampleRate).toInt() } ?: 0

    /** The frame the decay reaches silence at; [Int.MAX_VALUE] when the pad has none. */
    fun decayEnd(decay: Float?, frameCount: Int): Int =
        decay?.let { max(1, (it * frameCount).toInt()) } ?: Int.MAX_VALUE

    /** Cutoff 0..1 → 20 Hz..20 kHz, exponentially (three decades). */
    fun cutoffHz(cutoff: Float): Float = 20f * Math.pow(10.0, 3.0 * cutoff).toFloat()

    fun resonanceDb(resonance: Float): Float = resonance * RESONANCE_MAX_DB

    /**
     * The envelope's multiplier at frame [i] of a voice with [attackFrames]
     * and a [decayEnd] from the two helpers above: the attack ramp, then a
     * linear fade that dies at [decayEnd]; 0 from there on.
     */
    fun gainAt(i: Int, attackFrames: Int, decayEnd: Int): Float {
        if (i >= decayEnd) return 0f
        var g = 1f
        if (i < attackFrames) g *= i.toFloat() / attackFrames
        if (decayEnd != Int.MAX_VALUE) g *= 1f - i.toFloat() / decayEnd
        return g
    }

    /**
     * The envelope half of the shape over a whole snip — what a single
     * HIT sounds like before the card. A decay trims the result to the
     * shaped length (the fade has reached silence there anyway); no shape
     * at all hands back the very same object.
     */
    fun envelope(snip: Snip, attack: Float?, decay: Float?): Snip {
        if (attack == null && decay == null) return snip
        val frames = snip.frameCount
        val a = attackFrames(attack, snip.sampleRate)
        val end = decayEnd(decay, frames)
        val kept = minOf(frames, end)
        val out = FloatArray(kept * snip.channels)
        for (i in 0 until kept) {
            val g = gainAt(i, a, end)
            for (ch in 0 until snip.channels) out[i * snip.channels + ch] = snip.samples[i * snip.channels + ch] * g
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }
}
