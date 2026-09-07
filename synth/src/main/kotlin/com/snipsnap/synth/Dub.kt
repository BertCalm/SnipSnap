package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.roundToInt

/**
 * DUB — generation loss, baked. A cassette dubbed to a sampler dubbed to
 * a cassette, GENERATIONS times: every pass through the Time Machine's
 * `tape` and `mpc60` eras at a fraction of their strength, so the wow drifts a little
 * further, the top folds a little lower, and the word length loses a bit
 * more, the way a twelfth-generation dub of a dub actually sounds. One
 * macro, 0..1 onto 0..[MAX_GENERATIONS] bounces, zero transparent;
 * peak matched to the input at the end so the saturation of a dozen
 * passes never reads as loudness. Deterministic: the eras are.
 */
object Dub {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("GENERATIONS", 0.5f),  // how many dubs of dubs: none .. MAX_GENERATIONS
    )

    const val MAX_GENERATIONS = 12

    /**
     * Each bounce runs the eras at these strengths — a dub is a small loss,
     * many dubs a large one. The tape carries the character (drive, wow,
     * head wear); the sampler stage is kept gentle, because its companding
     * is a distortion that compounds fast: at half strength six passes
     * turned a kick into generic percussion, and a section's default must
     * keep identity like every other section's does.
     */
    const val TAPE_PASS = 0.4f
    const val SAMPLER_PASS = 0.15f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    fun generations(macro: Float): Int = (macro.coerceIn(0f, 1f) * MAX_GENERATIONS).roundToInt()

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val n = generations(m.getValue("GENERATIONS"))
        if (n == 0) return snip
        var s = snip
        repeat(n) {
            s = Eras.process("tape", s, TAPE_PASS)
            s = Eras.process("mpc60", s, SAMPLER_PASS)
        }
        // Character, not loudness: match the input's peak.
        val inPeak = snip.peak()
        val outPeak = s.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val k = inPeak / outPeak
            val out = s.samples
            for (i in out.indices) out[i] *= k
        }
        return s
    }
}
