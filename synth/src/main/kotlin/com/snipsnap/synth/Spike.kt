package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs

/**
 * SPIKE — the attack exaggerated, the inverse of the sections either side
 * of it. SMEAR takes the attack out and GHOST takes the tone out with it;
 * SPIKE leans the other way, so the rack can shape a hit's anatomy in both
 * directions rather than only downward.
 *
 * Two envelope followers at different speeds, and their *difference* is
 * the transient: where the signal rises faster than the slow follower can
 * track, the gap is the attack. ATTACK gains that gap; SUSTAIN gains what
 * is left, bipolar so 0.5 is untouched, below tightens and above swells.
 * Peak matched: shape, never loudness.
 *
 * Sits after GHOST and before EQ — anatomy before tone, like its
 * neighbours.
 */
object Spike {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("ATTACK", 0.4f),                    // how far the transient is pushed above the body
        MacroSpec("SUSTAIN", 0.5f, neutral = 0.5f),   // the body: 0.5 untouched, below drier, above fuller
    )

    /** The followers' time constants: fast enough to ride the attack, slow enough to miss it. */
    const val FAST_HZ = 700f
    const val SLOW_HZ = 18f

    /** The most either half may be multiplied by at the ends of its knob. */
    const val MAX_GAIN = 3f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val attack = m.getValue("ATTACK")
        val sustain = m.getValue("SUSTAIN")
        if (attack <= 0f && abs(sustain - 0.5f) < 1e-6f) {
            return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)
        }

        val transientGain = 1f + attack * (MAX_GAIN - 1f)
        val bodyGain = if (sustain >= 0.5f) {
            Dsp.lin((sustain - 0.5f) * 2f, 1f, MAX_GAIN)
        } else {
            Dsp.lin(sustain * 2f, 1f / MAX_GAIN, 1f)
        }

        val out = FloatArray(snip.samples.size)
        for (ch in 0 until snip.channels) {
            val fast = Dsp.OnePole(snip.sampleRate)
            val slow = Dsp.OnePole(snip.sampleRate)
            var i = ch
            while (i < out.size) {
                val x = snip.samples[i]
                val a = abs(x)
                val f = fast.lp(a, FAST_HZ)
                val s = slow.lp(a, SLOW_HZ)
                // The gap between a quick reading and a slow one IS the attack.
                val transient = (f - s).coerceAtLeast(0f)
                val total = f.coerceAtLeast(1e-9f)
                val share = (transient / total).coerceIn(0f, 1f)
                out[i] = x * (share * transientGain + (1f - share) * bodyGain)
                i += snip.channels
            }
        }

        // Shape, not loudness: match the input's peak.
        var outPeak = 0f
        for (v in out) { val a = abs(v); if (a > outPeak) outPeak = a }
        val inPeak = snip.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val k = inPeak / outPeak
            for (i in out.indices) out[i] = (out[i] * k).coerceIn(-1f, 1f)
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }
}
