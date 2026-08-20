package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.ceil
import kotlin.math.ln

/**
 * ECHO — one delay line and one filter, which is all a tape echo ever was.
 *
 * Each repeat passes through the low-pass again, so the tail darkens as it
 * fades — the era-correct behaviour, and what keeps stacked repeats from
 * turning into hash. The tail is extended until repeats fall below -60 dB,
 * bounded so a scrambled ECHO can't balloon a pad into a phrase.
 */
object Echo {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("TIME", 0.4f),    // 60..350 ms between repeats
        MacroSpec("REPEAT", 0.4f),  // feedback: one slapback up to a long trail
        MacroSpec("TONE", 0.5f),    // how dark each repeat gets
        MacroSpec("MIX", 0.4f),     // wet level against the untouched dry
    )

    /** The most tail any setting may add, seconds. The one-shot promise. */
    const val MAX_TAIL_SECONDS = 1.1f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** Delay between repeats for a TIME setting, in frames at [sampleRate]. */
    fun delayFrames(time: Float, sampleRate: Int): Int =
        (Dsp.expMap(time, 0.06f, 0.35f) * sampleRate).toInt()

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val mix = m.getValue("MIX")
        if (mix <= 0.001f) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val delay = delayFrames(m.getValue("TIME"), snip.sampleRate)
        val feedback = Dsp.lin(m.getValue("REPEAT"), 0.15f, 0.75f)
        val toneHz = Dsp.expMap(m.getValue("TONE"), 1_200f, 9_000f)

        // Repeats below -60 dB are silence; cap the tail regardless.
        val taps = ceil(-6.9078 / ln(feedback.toDouble())).toInt().coerceAtLeast(1)
        val tail = (taps * delay).coerceAtMost((MAX_TAIL_SECONDS * snip.sampleRate).toInt())
        val outFrames = snip.frameCount + tail

        var inPeak = 0f
        for (v in snip.samples) { val a = if (v < 0) -v else v; if (a > inPeak) inPeak = a }

        val out = FloatArray(outFrames * snip.channels)
        for (ch in 0 until snip.channels) {
            // d[] is the echo bus: everything in it has passed the low-pass
            // at least once, so repeat N is N filter passes dark.
            val d = FloatArray(outFrames)
            val lp = Dsp.OnePole(snip.sampleRate)
            for (f in 0 until outFrames) {
                val dry = if (f < snip.frameCount) snip.samples[f * snip.channels + ch] else 0f
                val fed = if (f >= delay) d[f - delay] else 0f
                d[f] = lp.lp(dry + feedback * fed, toneHz)
                val wet = if (f >= delay) d[f - delay] else 0f
                out[f * snip.channels + ch] = dry + mix * wet
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
