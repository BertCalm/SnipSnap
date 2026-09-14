package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.sin

/**
 * RING — the sound multiplied by a sine, which is the oldest way to make a
 * sampler sound like it is lying. Every partial splits into a sum and a
 * difference with the modulator, and neither is in the harmonic series any
 * more: metal, bells, radio.
 *
 * CRUNCH and DUB are the converter's own damage, quantized and honest.
 * RING is damage of a different species — inharmonic rather than coarse —
 * and sits between them for that reason.
 *
 * Two macros, MIX 0 transparent, peak matched, no tail, no seed: a sine is
 * deterministic. **A ringed hit is deliberately no longer the hit it was**,
 * the same exemption GHOST carries.
 */
object Ring {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("FREQ", 0.35f),  // the modulator: LOW_HZ..HIGH_HZ
        MacroSpec("MIX", 0.4f),    // wet against the untouched dry
    )

    /** Low enough to read as tremolo, high enough to read as metal. */
    const val LOW_HZ = 30f
    const val HIGH_HZ = 3_000f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** The modulator's frequency for a FREQ setting, Hz. */
    fun freqHz(macro: Float): Float = Dsp.expMap(macro.coerceIn(0f, 1f), LOW_HZ, HIGH_HZ)

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val mix = m.getValue("MIX")
        if (mix <= 0.001f) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val step = 2.0 * Math.PI * freqHz(m.getValue("FREQ")) / snip.sampleRate
        val out = FloatArray(snip.samples.size)
        for (f in 0 until snip.frameCount) {
            // One modulator across the frame, so a stereo pair rings together.
            val mod = sin(step * f).toFloat()
            for (ch in 0 until snip.channels) {
                val i = f * snip.channels + ch
                val x = snip.samples[i]
                out[i] = x * (1f - mix) + x * mod * mix
            }
        }

        var outPeak = 0f
        for (v in out) { val a = abs(v); if (a > outPeak) outPeak = a }
        val inPeak = snip.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val k = inPeak / outPeak
            for (i in out.indices) out[i] *= k
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }
}
