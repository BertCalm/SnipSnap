package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.sin

/**
 * PHASE — four allpasses in a row, their corner swept by one slow sine,
 * the result summed back with the dry. Where the rotated copy and the
 * original disagree they cancel, and the notches that makes slide up and
 * down the spectrum: the sound of a pedal that was on every record between
 * 1968 and 1979.
 *
 * Sits after TAPE and before ECHO. The sweep happens to the finished tone,
 * and ECHO's repeats then carry it at earlier points in the sweep, which is
 * what a phaser into a delay has always sounded like.
 *
 * The LFO starts at phase zero every time, so PHASE is deterministic
 * without a seed. DEPTH 0 is transparent; no tail.
 */
object Phase {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("RATE", 0.35f),      // sweep speed: SLOW_HZ..FAST_HZ
        MacroSpec("DEPTH", 0.6f),      // how far the notches travel
        MacroSpec("FEEDBACK", 0.3f),   // resonance: how sharp the notches get
    )

    const val SLOW_HZ = 0.15f
    const val FAST_HZ = 4f

    /** The sweep's corner travels between these. */
    const val LOW_HZ = 250f
    const val HIGH_HZ = 4_000f

    /** Four stages: two notches, the classic voicing. */
    const val STAGES = 4

    /** Resonance ceiling — past this the feedback path rings rather than colours. */
    const val MAX_FEEDBACK = 0.7f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val depth = m.getValue("DEPTH")
        if (depth <= 0.001f) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val lfoHz = Dsp.expMap(m.getValue("RATE"), SLOW_HZ, FAST_HZ)
        val feedback = m.getValue("FEEDBACK") * MAX_FEEDBACK
        val step = 2.0 * Math.PI * lfoHz / snip.sampleRate

        val out = FloatArray(snip.samples.size)
        val stages = Array(snip.channels) { Array(STAGES) { Dsp.Biquad() } }
        val last = FloatArray(snip.channels)
        for (f in 0 until snip.frameCount) {
            // One LFO across the frame, so a stereo pair sweeps together.
            val lfo = (sin(step * f).toFloat() + 1f) * 0.5f
            val corner = Dsp.expMap(lfo * depth, LOW_HZ, HIGH_HZ)
            for (ch in 0 until snip.channels) {
                val i = f * snip.channels + ch
                val x = snip.samples[i]
                var v = x + last[ch] * feedback
                for (stage in stages[ch]) {
                    stage.allpass(corner, 0.7f, snip.sampleRate)
                    v = stage.process(v)
                }
                last[ch] = v
                out[i] = (x + v) * 0.5f
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
