package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * VOLUME — the pad fades in. A violin or pedal-steel swell with no pick, a choir that
 * arrives instead of starting, a chop that "fades in" in one tap. Nothing else in the rack
 * does this and leaves pitch, tone and length alone: SWELL prepends a stretched, reversed
 * head and keeps the hit; GHOST and SMEAR change the tone.
 *
 * One macro, RISE, 0..1 onto 0..[RISE_MAX_SEC]: the first RISE seconds are multiplied by a
 * raised cosine that climbs from silence to full level (the same smooth shape WAH's sweep
 * uses, so the curve has no corner at either end). The rise never takes more than
 * [RISE_CAP] of the pad, so a short hit keeps most of its body. Zero is a bypass.
 *
 * The peak is restored after the rise, but only up to [MAX_LIFT]. A pad whose loudest moment
 * comes after the rise keeps its peak and nothing is lifted. A pad that is loudest early loses
 * that peak to the fade, and lifting what is left by the full difference would raise the pad's
 * noise floor by 15 to 20 dB, so the lift stops at [MAX_LIFT] and such a pad comes out quieter.
 * Measured on the audition sources (2026-11-01, peak kept relative to the dry pad): the snare
 * keeps 16 percent at every rise heard, because it is loudest in its first few milliseconds; the
 * choir keeps 100, 94 and 53 percent at rises of 240, 448 and 640 ms; JANGLE through its amp keeps
 * 100, 97 and 73 percent. That is why the rack's peak-match test excuses this section
 * (FxTest `contractExcluded`): the drum case is the weak half of this pedal by design. At its gate
 * (2026-11-02) the owner heard it and found a faded-in snare useful on held sounds only, so nothing
 * here tries to rescue a drum.
 *
 * Sits after SPIKE and before EQ, and so before the amp on purpose: a fade into DRIVE should
 * bloom, and WAH's envelope follower should ride the swell.
 */
object Volume {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("RISE", 0.5f),  // how long the pad takes to reach full level: none .. RISE_MAX_SEC
    )

    const val RISE_MAX_SEC = 0.8f

    /** The most of the pad the rise may take: a short hit still has its body left. */
    const val RISE_CAP = 0.7f

    /** The most the peak-restore may lift what the fade left: 12 dB. */
    const val MAX_LIFT = 4f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** How many frames the rise takes for [macro] on a pad of [frameCount] frames. */
    fun riseFrames(macro: Float, sampleRate: Int, frameCount: Int): Int =
        min((macro.coerceIn(0f, 1f) * RISE_MAX_SEC * sampleRate).toInt(), (frameCount * RISE_CAP).toInt())

    /** The rise's gain at [frame] of [rise]: 0 at the first frame, 1 at the last, smooth between. */
    fun gainAt(frame: Int, rise: Int): Float = (0.5 * (1.0 - cos(PI * frame / rise))).toFloat()

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val rise = riseFrames(m.getValue("RISE"), snip.sampleRate, snip.frameCount)
        // A rise too short to be a ramp is a bypass, never a throw.
        if (rise < 2 || snip.frameCount == 0) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val out = snip.samples.copyOf()
        val ch = snip.channels
        for (f in 0 until rise) {
            val g = gainAt(f, rise)
            for (c in 0 until ch) out[f * ch + c] *= g
        }

        // Shape, not loudness: restore the peak the fade took, up to the lift cap.
        val inPeak = snip.peak()
        val outPeak = Snip(out, ch, snip.sampleRate).peak()
        if (inPeak > 0f && outPeak > 0f) {
            val lift = min(inPeak / outPeak, MAX_LIFT)
            if (lift > 1f) for (i in out.indices) out[i] *= lift
        }
        return Snip(out, ch, snip.sampleRate)
    }
}
