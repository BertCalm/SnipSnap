package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln

/**
 * ECHO — one delay line and one filter, which is all a tape echo ever was.
 *
 * Each repeat passes through the low-pass again, so the tail darkens as it
 * fades — the era-correct behaviour, and what keeps stacked repeats from
 * turning into hash. The tail is extended until repeats fall below -60 dB,
 * bounded so a scrambled ECHO can't balloon a pad into a phrase.
 *
 * HEAD chooses what feeds the repeats: 0 the whole note (ECHO as it always
 * was, bit for bit), 1 only the note's first [HEAD_SEC], its last third
 * faded, so a held note's attack comes back as a repeat instead of the whole
 * note smearing into itself. It is stretched by 1.5 as WAH's weights are, so
 * the pad sheet's first tap still feeds the head alone, and the window
 * shrinks with a short TIME so a repeat of the head ends before the next
 * lands (docs/superpowers/specs/2026-10-22-delay-head-design.md).
 */
object Echo {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("TIME", 0.4f),    // 60..350 ms between repeats
        MacroSpec("REPEAT", 0.4f),  // feedback: one slapback up to a long trail
        MacroSpec("TONE", 0.5f),    // how dark each repeat gets
        MacroSpec("MIX", 0.4f),     // wet level against the untouched dry
        MacroSpec("HEAD", 0f),      // what feeds the repeats: 0 the whole note, 1 only its first HEAD_SEC
    )

    /** The most tail any setting may add, seconds. The one-shot promise. */
    const val MAX_TAIL_SECONDS = 1.1f

    /** At or below this HEAD is off: the whole note feeds the repeats, through a factor of exactly 1f. */
    const val HEAD_OFF = 0.001f

    /** The head a full HEAD feeds the repeats: the pick and a little of the body, its last third faded. */
    const val HEAD_SEC = 0.12f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** Delay between repeats for a TIME setting, in frames at [sampleRate]. */
    fun delayFrames(time: Float, sampleRate: Int): Int =
        (Dsp.expMap(time, 0.06f, 0.35f) * sampleRate).toInt()

    /**
     * How far HEAD has moved the feed toward the head alone: 0 at or below [HEAD_OFF], then HEAD stretched by 1.5
     * and capped at 1, as WAH's weights are, so the pad sheet's first tap (HEAD 0.7) still feeds the head alone.
     */
    internal fun reach(macros: Map<String, Float>): Float {
        val head = macros["HEAD"] ?: 0f
        return if (head <= HEAD_OFF) 0f else (1.5f * head).coerceIn(0f, 1f)
    }

    /** The head's length in frames: [HEAD_SEC], never past 0.8 of [delay], so a repeat of the head ends before the next lands. */
    internal fun headFrames(delay: Int, sampleRate: Int): Int =
        minOf((HEAD_SEC * sampleRate).toInt(), (0.8f * delay).toInt())

    /** The head window at frame [f]: 1, then a raised-cosine fade over the last third of [head] frames, then 0. */
    internal fun window(f: Int, head: Int): Float {
        val fade = head / 3
        return when {
            f >= head -> 0f
            f < head - fade -> 1f
            else -> (0.5 + 0.5 * cos(PI * (f - (head - fade)) / fade)).toFloat()
        }
    }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val mix = m.getValue("MIX")
        if (mix <= 0.001f) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val delay = delayFrames(m.getValue("TIME"), snip.sampleRate)
        val feedback = Dsp.lin(m.getValue("REPEAT"), 0.15f, 0.75f)
        val toneHz = Dsp.expMap(m.getValue("TONE"), 1_200f, 9_000f)
        val reach = reach(m)
        val head = headFrames(delay, snip.sampleRate)

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
                // HEAD's feed: the whole note at reach 0 (a factor of exactly 1f), the head alone at reach 1.
                val feed = 1f - reach * (1f - window(f, head))
                d[f] = lp.lp(dry * feed + feedback * fed, toneHz)
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
