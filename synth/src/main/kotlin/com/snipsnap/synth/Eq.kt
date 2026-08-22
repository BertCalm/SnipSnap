package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs

/**
 * EQ — three bands, playability-first.
 *
 * Not a console strip: BASS is a low shelf at 100 Hz, MID a bell at 900 Hz,
 * AIR a high shelf at 8 kHz — each knob 0..1 with **0.5 = flat** and ±12 dB
 * at the ends, so the center detent is silence and every move from it does
 * one plain-word thing. Coefficients are the RBJ Audio EQ Cookbook shelves
 * and bell (see [Dsp.Biquad]).
 *
 * Sits first in the rack (after REVERSE): carve the mud out *before* the
 * compressor reacts to it. Peak-matched like every pass — tone, never
 * loudness.
 */
object Eq {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("BASS", 0.5f),
        MacroSpec("MID", 0.5f),
        MacroSpec("AIR", 0.5f),
    )

    private const val BASS_HZ = 100f
    private const val MID_HZ = 900f
    private const val MID_Q = 0.9f
    private const val AIR_HZ = 8_000f
    private const val RANGE_DB = 12f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** Macro 0..1 (0.5 flat) to gain in dB. */
    private fun dB(macro: Float): Float = (macro - 0.5f) * 2f * RANGE_DB

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val bassDb = dB(m.getValue("BASS"))
        val midDb = dB(m.getValue("MID"))
        val airDb = dB(m.getValue("AIR"))
        if (abs(bassDb) < 0.05f && abs(midDb) < 0.05f && abs(airDb) < 0.05f) {
            return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)
        }

        var inPeak = 0f
        for (v in snip.samples) { val a = if (v < 0) -v else v; if (a > inPeak) inPeak = a }

        val out = FloatArray(snip.samples.size)
        for (ch in 0 until snip.channels) {
            val bass = Dsp.Biquad().apply { lowShelf(BASS_HZ, bassDb, snip.sampleRate) }
            val mid = Dsp.Biquad().apply { peaking(MID_HZ, midDb, MID_Q, snip.sampleRate) }
            val air = Dsp.Biquad().apply { highShelf(AIR_HZ, airDb, snip.sampleRate) }
            var i = ch
            while (i < snip.samples.size) {
                out[i] = air.process(mid.process(bass.process(snip.samples[i])))
                i += snip.channels
            }
        }

        var outPeak = 0f
        for (v in out) { val a = if (v < 0) -v else v; if (a > outPeak) outPeak = a }
        if (outPeak > 1e-9f && inPeak > 1e-9f) {
            val g = inPeak / outPeak
            for (i in out.indices) out[i] = (out[i] * g).coerceIn(-1f, 1f)
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }
}
