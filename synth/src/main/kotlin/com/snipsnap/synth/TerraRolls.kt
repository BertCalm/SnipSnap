package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.pow

/** How a TERRA pad is played: one hit, or an authored roll or riser of a stick voice ([TerraRolls]). */
enum class TerraArticulation { HIT, ROLL, RISER }

/**
 * Authored rolls and risers for TERRA's stick voices ([TerraSticks]): real repeated strikes of the
 * voice, never one sample looped. Two hands alternate, every stroke is its own renderer take (fresh
 * noise, a moved landing), and each stroke's tail rings under the next as a real drum's does, where a
 * retriggered sample would cut it. The prototype's builds, which the owner heard in rounds 1 to 6
 * (docs/TERRA_STICKS.md), were made the same way.
 *
 * - **ROLL**: [ROLL_SECONDS] of strokes at the voice's own roll rate, the second hand
 *   [OFF_HAND] as hard as the first.
 * - **RISER**: [RISER_SECONDS] of strokes that speed up from the voice's slow rate to its fast one
 *   on an exponential curve and grow from [RISER_START] of the touch to all of it, so a riser is a
 *   crescendo of strike character (each stroke brighter), not only of level.
 *
 * Tempo-free, as BACKWASH's tambourine roll is: [Terra.render] receives no tempo, so a roll is
 * printed at the voice's rate rather than locked to a kit's BPM.
 *
 * [velocity] is the touch: every stroke's force is scaled by it, so a soft roll is a darker roll.
 * [take] is a different roll: its strokes draw renderer takes from `take * TAKE_STRIDE` and its
 * timing moves by different amounts. Both are pure: the same arguments give the same samples.
 *
 * Cost: one [Terra.render] per distinct (force, stroke take), cached within a roll and rendered in
 * parallel: 16 strokes for a ROLL (two hands times [STROKE_TAKES]), up to 64 for a RISER. That is
 * hundreds of milliseconds to a few seconds, over the house's tens-of-milliseconds rule for a
 * one-shot; a roll is a phrase pad, re-rendered when its recipe changes, and the cost is recorded
 * in docs/TERRA_STICKS.md.
 */
internal object TerraRolls {
    const val ROLL_SECONDS = 2.0
    const val RISER_SECONDS = 3.0

    /** The second hand's force against the first's. Shape. */
    const val OFF_HAND = 0.86f

    /** The riser's first stroke's force against its last's. Shape. */
    const val RISER_START = 0.25f

    /** Distinct renderer takes the strokes of one roll cycle through; a hand's strokes never repeat within this many. */
    const val STROKE_TAKES = 8

    /** How far apart two kit takes' stroke takes start: no stroke take is shared between kit takes 0 to 3. */
    const val TAKE_STRIDE = 16

    /** Force is quantized to this step so a riser's strokes share renders: 21 levels from 0 to 1. */
    private const val FORCE_STEP = 0.05f

    /** A stroke's timing moves by up to this much either way, in seconds: a hand is not a clock. Shape. */
    private const val JITTER_SECONDS = 0.004

    /** A voice's strokes per second: (its steady roll, its riser's start, its riser's end). Shape, from the prototype's builds. */
    fun rates(voice: TerraVoice): Triple<Double, Double, Double> = when (voice) {
        TerraVoice.ODAIKO -> Triple(9.0, 3.0, 12.0)
        TerraVoice.NAGADO -> Triple(11.0, 4.0, 14.0)
        TerraVoice.SHIME -> Triple(15.0, 6.0, 18.0)
        TerraVoice.KA -> Triple(12.0, 5.0, 16.0)
        TerraVoice.KANE -> Triple(12.0, 4.0, 14.0)
        TerraVoice.CHAPPA -> Triple(7.0, 3.0, 10.0)
        // each stroke is itself a 185 ms fall of slats, so the throws come slowly
        TerraVoice.BINZASARA -> Triple(5.0, 2.0, 7.0)
        TerraVoice.COWBELL -> Triple(8.0, 3.0, 10.0)
        TerraVoice.WATER_DRUM -> Triple(10.0, 4.0, 14.0)
        // each stroke is itself a 106 to 123 ms flutter of flaps
        TerraVoice.FAN -> Triple(6.0, 2.0, 8.0)
        else -> error("$voice is not a TERRA stick voice")
    }

    /** One stroke: when it lands (seconds), how hard (0..1, the render's velocity), which renderer take, and its level. */
    class Stroke(val time: Double, val force: Float, val take: Int, val gain: Float)

    /** The strokes of [articulation] on [voice] at [velocity] as kit take [take], in time order. */
    fun strokes(voice: TerraVoice, articulation: TerraArticulation, velocity: Float, take: Int): List<Stroke> {
        require(articulation != TerraArticulation.HIT) { "a hit is one stroke: Terra.render it" }
        require(take >= 0) { "a TERRA take is 0 or more, got $take" }
        val v = StrikeVelocity.settled(velocity)
        val (steady, slow, fast) = rates(voice)
        val jitter = Dsp.Noise(Dsp.seedFor("TERRA ROLL", voice.name, articulation.name, take))
        val out = ArrayList<Stroke>()
        var t = 0.0
        var i = 0
        val length = if (articulation == TerraArticulation.ROLL) ROLL_SECONDS else RISER_SECONDS
        while (t < length) {
            val x = (t / length).toFloat()
            val hand = if (i % 2 == 0) 1f else OFF_HAND
            val swell = if (articulation == TerraArticulation.ROLL) 1f else RISER_START + (1f - RISER_START) * x.toDouble().pow(1.4).toFloat()
            val force = quantize(v * hand * swell)
            val moved = if (i == 0) 0.0 else JITTER_SECONDS * jitter.next()
            out += Stroke(maxOf(0.0, t + moved), force, take * TAKE_STRIDE + i % STROKE_TAKES, force.toDouble().pow(0.8).toFloat())
            val rate = if (articulation == TerraArticulation.ROLL) steady else slow * (fast / slow).pow(x.toDouble())
            t += 1.0 / rate
            i++
        }
        return out
    }

    private fun quantize(f: Float): Float = (Math.round(f.coerceIn(0f, 1f) / FORCE_STEP) * FORCE_STEP).coerceIn(FORCE_STEP, 1f)

    /** [articulation] of [voice] with [macros], at [velocity], as kit take [take]: the strokes summed, levelled and faded. */
    fun render(voice: TerraVoice, macros: Map<String, Float>, articulation: TerraArticulation, velocity: Float, take: Int): Snip {
        val strokes = strokes(voice, articulation, velocity, take)
        val keys = strokes.map { it.force to it.take }.distinct()
        val renders = keys.parallelStream().map { (force, t) -> Terra.render(voice, macros, velocity = force, take = t).samples }.toList()
        val byKey = keys.zip(renders).toMap()
        val starts = strokes.map { (it.time * Dsp.RATE).toInt() }
        val length = strokes.indices.maxOf { starts[it] + byKey.getValue(strokes[it].force to strokes[it].take).size }
        val out = FloatArray(length)
        for ((k, s) in strokes.withIndex()) {
            val x = byKey.getValue(s.force to s.take)
            val at = starts[k]
            for (i in x.indices) out[at + i] += x[i] * s.gain
        }
        Dsp.normalize(out)
        Dsp.limitPeak(out)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = Dsp.RATE)
    }
}
