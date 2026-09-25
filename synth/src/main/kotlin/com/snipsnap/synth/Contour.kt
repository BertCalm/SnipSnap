package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.pow

/**
 * CONTOUR — RESIN's ladder filter on a pad that was never a synth: a
 * ripped snare or a vocal chop swept through [Dsp.Ladder] from its
 * onset. SWEEP is the filter contour's amount and speed together (the
 * same one-knob device as the engine's own CONTOUR macro), CUTOFF the
 * floor it falls to, CREAM the feedback.
 *
 * Not `PadFilter`: that previews the shape the MPC renders from
 * metadata, and a ladder there would make the phone sound different
 * from the hardware. The rack bakes into the WAV, which is where a sound
 * the hardware cannot make has to live.
 *
 * Runs at the snip's own rate, not oversampled (like RING and WOBBLE),
 * so CREAM stops at 4.0: at 44.1 kHz the loop does not reliably
 * self-oscillate above ~1 kHz, and a section that cannot promise singing
 * should not have a knob that claims to. Peak matched, no seed.
 */
object Contour {

    val MACROS: List<MacroSpec> = listOf(
        // Fully open is where it does nothing, so AMT fades toward 1.
        MacroSpec("CUTOFF", 0.35f, neutral = 1f),
        MacroSpec("CREAM", 0.5f),
        MacroSpec("SWEEP", 0.6f),
    )

    const val LOW_HZ = 80f
    const val HIGH_HZ = 16_000f

    /** Below the ladder's own 4.3: no singing promised at native rate. */
    const val MAX_RESONANCE = 4f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** The floor for a CUTOFF setting at [rate], Hz. */
    fun floorHz(macro: Float, rate: Int): Float =
        minOf(Dsp.expMap(macro.coerceIn(0f, 1f), LOW_HZ, HIGH_HZ), rate * 0.4f)

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val rate = snip.sampleRate
        val floor = floorHz(m.getValue("CUTOFF"), rate)
        val ceiling = rate * 0.4f
        val resonance = Dsp.lin(m.getValue("CREAM"), 0f, MAX_RESONANCE)
        val sweep = m.getValue("SWEEP")
        // A captured pad is already cut to its attack by Cleanup, so the
        // onset is frame 0 and the contour starts there.
        val octaves = Dsp.lin(sweep, 0f, 4f)
        val sweepT60 = Dsp.lin(sweep, 0.25f, 0.06f)

        val channels = snip.channels
        val out = FloatArray(snip.samples.size)
        val ladders = Array(channels) { Dsp.Ladder(rate) }
        for (f in 0 until snip.frameCount) {
            // One contour across the frame, so a stereo pair sweeps together.
            val t = f.toFloat() / rate
            val fc = (floor * 2f.pow(octaves * Dsp.envAt(t, sweepT60))).coerceAtMost(ceiling)
            for (ch in 0 until channels) {
                val i = f * channels + ch
                out[i] = ladders[ch].process(snip.samples[i], fc, resonance)
            }
        }

        var outPeak = 0f
        for (v in out) { val a = abs(v); if (a > outPeak) outPeak = a }
        val inPeak = snip.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val k = inPeak / outPeak
            for (i in out.indices) out[i] *= k
        }
        return Snip(out, channels, rate)
    }
}
