package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * PITCH — the transport, not an effect. In a sampler pitch *is* speed, and
 * the length comes along with it: that is the mechanism, and pretending
 * otherwise would need a third stretch engine in a rack whose discipline is
 * one idea per knob (SWELL already owns paulstretch, GRAINS the granular
 * cloud).
 *
 * MOTION is the capstan letting go at the end of a hit; this is the pitch
 * knob before the sound ever leaves the machine. It runs first, so SWELL's
 * stretched head, REVERSE's flip and the whole rack all see the pitched
 * sound — which is what a sampler plays.
 *
 * One macro, snapped to semitones, **0.5 = native**: the macro's neutral,
 * so AMT fades a pitched treatment back toward the original note rather
 * than toward a full octave down. No length cap of its own — capping a
 * varispeed truncates the note, which is worse than a long sample.
 *
 * The Kotlin object is `Speed` because `com.snipsnap.audio.Pitch` is
 * already the pitch *detector*; the user-facing word stays PITCH.
 */
object Speed {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("SEMITONES", 0.5f, neutral = 0.5f),  // -RANGE..+RANGE semitones, 0.5 native
    )

    /** How far the knob reaches either way. */
    const val SEMITONE_RANGE = 12

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** The snapped interval a macro names, -[SEMITONE_RANGE]..+[SEMITONE_RANGE]. */
    fun semitones(macro: Float): Int =
        ((macro.coerceIn(0f, 1f) - 0.5f) * 2f * SEMITONE_RANGE).roundToInt()

    /** How fast the head reads: 2 an octave up, 0.5 an octave down. */
    fun ratio(macro: Float): Float = 2f.pow(semitones(macro) / 12f)

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val steps = semitones(m.getValue("SEMITONES"))
        if (steps == 0) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val speed = ratio(m.getValue("SEMITONES"))
        val frames = (snip.frameCount / speed).toInt().coerceAtLeast(1)
        val out = Dsp.readAt(snip, frames) { k -> (k * speed).toDouble() to 1f }

        // Speed is not loudness. Matched both ways, like every other section:
        // interpolation can overshoot, and a pitch-up that steps over the peak
        // sample can undershoot — neither is a musical level change.
        var outPeak = 0f
        for (v in out.samples) { val a = abs(v); if (a > outPeak) outPeak = a }
        val inPeak = snip.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val k = inPeak / outPeak
            for (i in out.samples.indices) out.samples[i] *= k
        }
        return out
    }
}
