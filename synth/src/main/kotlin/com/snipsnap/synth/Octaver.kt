package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

/**
 * SUB (saved key `octaver`): a copy of the pad one octave down, mixed under it at the same length and
 * timing. The characteristic gesture is: the hit lands as it is, and under it the same hit, an octave
 * lower and darker, starts on the same sample and ends with the pad.
 *
 * Nothing in the rack makes that: SPEED (PITCH) moves the whole pad, so its octave down is a pad twice as
 * long with no dry hit on top; DIVE falls into a pitch over time; SUB keeps the hit and adds the octave.
 *
 * The copy is the pad read at half speed from its onset (the first frame within 40 dB of its peak), which
 * is an octave down exactly, with no pitch detector and no grains. Offline, a whole-pad read is free, and
 * it is the one shifter that cannot flam or flutter a short hit: the pedal brainstorm's two crossfaded
 * heads over a 50 ms window would double a 30 ms rim into two hits. What a half-speed read changes
 * besides pitch, and what the section does about each:
 *  - the copy decays half as fast. HANG chooses: at 0 the copy is held to the hit's own envelope (it ends
 *    when the hit ends), at 1 it keeps its own, twice as long, cut where the pad ends;
 *  - the copy's top comes down an octave too, so snare wires become a hiss an octave lower. TONE is a
 *    four-pole low-pass on the copy, [TONE_DARK_HZ] to [TONE_OPEN_HZ], which also removes the read's images;
 *  - the copy is twice as long as the pad, so it is faded over the last [END_FADE_SEC] (or a quarter of a
 *    short pad) and cut at the pad's end: the length is kept.
 * The copy is high-passed at [FLOOR_HZ], because a kick's octave (its body is 34 to 62 Hz, so 17 to 31 Hz
 * an octave down) is under what headphones and small monitors play, and inaudible energy would only take
 * headroom from the hit. So a kick gains little: this section is for snares and toms, where the octave
 * lands at 41 to 165 Hz.
 *
 * DEPTH sets the copy's peak against the pad's own (0 off, 1 as loud as the hit), so it means the same on a
 * loud and a quiet pad. The mix is then matched to the pad's peak, both ways: a louder sum pulls the hit
 * down (the brainstorm's "more AMT reads as quieter" hazard, measured in the test as the hit's drop in dB).
 * TONE and HANG are their own neutrals, so AMT, which walks a character toward its neutrals, moves DEPTH alone.
 *
 * Linked across channels: one onset and one envelope for every channel, so the image never moves.
 *
 * Sits after VOLUME and before EQ, where the brainstorm put it: in front of the amp, as an octave pedal is
 * on a board, so DRIVE and VALVE colour the hit and its octave as one sound, and EQ and CONTOUR then shape
 * both. A FADE IN ahead of it fades the copy too, because the copy is made of what VOLUME made.
 *
 * Chip-less until the owner's listening gate.
 */
object Octaver {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("DEPTH", 0.5f),                 // the copy's peak against the pad's: 0 off .. 1 as loud as the hit
        MacroSpec("TONE", 0.5f, neutral = 0.5f),  // the copy's low-pass, TONE_DARK_HZ .. TONE_OPEN_HZ; its own neutral
        MacroSpec("HANG", 0.5f, neutral = 0.5f),  // 0 the copy follows the hit's envelope, 1 its own (twice as long); its own neutral
    )

    /** TONE's ends: the cutoff of the copy's four-pole low-pass. */
    const val TONE_DARK_HZ = 150f
    const val TONE_OPEN_HZ = 2400f

    /** The copy's high-pass: under this an octave is not heard, only spent as headroom. */
    const val FLOOR_HZ = 30f

    /** How far under the pad's peak its onset is found. */
    const val ONSET_DB = 40f

    /** The copy's end fade, or a quarter of the pad if that is shorter. */
    const val END_FADE_SEC = 0.03f

    /** The envelope detector's window, centred on the frame (HUSH's). */
    const val WINDOW_SEC = 0.015f

    /** The follow gain's smoothing time constant. */
    const val SMOOTH_SEC = 0.002f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** The copy's low-pass cutoff for a TONE macro (log scale). */
    fun toneHz(tone: Float): Float = Dsp.expMap(tone, TONE_DARK_HZ, TONE_OPEN_HZ)

    /** The first frame within [ONSET_DB] of the peak of [level], or -1 if the pad is silent. */
    internal fun onset(level: FloatArray): Int {
        val peak = level.maxOrNull() ?: 0f
        if (peak <= 1e-9f) return -1
        val at = peak * 10f.pow(-ONSET_DB / 20f)
        return level.indexOfFirst { it >= at }
    }

    /**
     * The octave copy alone, interleaved like [snip], before DEPTH and the mix: read at half speed from the
     * onset, held toward the hit's envelope by HANG, band-limited by TONE and [FLOOR_HZ], faded and cut at
     * the pad's end, at the pad's own peak. All zeros for a silent pad.
     */
    internal fun copy(snip: Snip, tone: Float, hang: Float): FloatArray {
        val frames = snip.frameCount
        val ch = snip.channels
        val rate = snip.sampleRate
        val x = snip.samples
        val out = FloatArray(x.size)
        val level = FloatArray(frames)
        for (f in 0 until frames) {
            var a = 0f
            for (c in 0 until ch) { val v = abs(x[f * ch + c]); if (v > a) a = v }
            level[f] = a
        }
        val start = onset(level)
        if (start < 0) return out
        val env = Hush.slidingMax(level, (WINDOW_SEC * rate).toInt().coerceAtLeast(1))

        // The read: frame n of the copy is the pad at o + (n - o) / 2.
        for (n in start until frames) {
            val pos = start + (n - start) * 0.5f
            val i0 = pos.toInt()
            val frac = pos - i0
            val i1 = minOf(i0 + 1, frames - 1)
            for (c in 0 until ch) out[n * ch + c] = x[i0 * ch + c] * (1f - frac) + x[i1 * ch + c] * frac
        }

        // HANG: the copy's envelope is env(o + (n - o) / 2); the hit's is env(n). Their ratio, raised to
        // (1 - HANG), holds the copy to the hit at 0 and leaves it alone at 1. Never a lift.
        val follow = 1f - hang.coerceIn(0f, 1f)
        if (follow > 0f) {
            val smooth = 1f - kotlin.math.exp(-1f / (SMOOTH_SEC * rate))
            var g = 1f
            for (n in start until frames) {
                val half = start + (n - start) / 2
                val ratio = if (env[half] > 1e-9f) (env[n] / env[half]).coerceIn(0f, 1f) else 0f
                val target = if (follow >= 1f) ratio else ratio.pow(follow)
                g += (target - g) * smooth
                for (c in 0 until ch) out[n * ch + c] *= g
            }
        }

        // TONE (two 2-pole low-passes, Butterworth damping) and the floor's 2-pole high-pass, per channel.
        val cutoff = toneHz(tone)
        val k = kotlin.math.sqrt(2f)
        for (c in 0 until ch) {
            val lp1 = Dsp.TptSvf(rate)
            val lp2 = Dsp.TptSvf(rate)
            val hp = Dsp.TptSvf(rate)
            for (n in 0 until frames) {
                lp1.process(out[n * ch + c], cutoff, k)
                lp2.process(lp1.low, cutoff, k)
                hp.process(lp2.low, FLOOR_HZ, k)
                out[n * ch + c] = hp.high
            }
        }

        // The end: a raised-cosine fade, so the copy is cut at the pad's end without a click.
        val fade = minOf((END_FADE_SEC * rate).toInt(), frames / 4).coerceAtLeast(1)
        for (j in 0 until fade) {
            val n = frames - fade + j
            val g = 0.5f * (1f + cos(PI * (j + 1) / fade).toFloat())
            for (c in 0 until ch) out[n * ch + c] *= g
        }

        // At the pad's own peak, so DEPTH is relative to the hit.
        var copyPeak = 0f
        for (v in out) { val a = abs(v); if (a > copyPeak) copyPeak = a }
        val inPeak = level.maxOrNull() ?: 0f
        if (copyPeak > 1e-9f) { val s = inPeak / copyPeak; for (i in out.indices) out[i] *= s }
        return out
    }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        // A value that is not a number is left at the default: a NaN would otherwise ride through the clamp into the mix.
        for ((k, v) in macros) if (m.containsKey(k) && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        val depth = m.getValue("DEPTH")
        if (depth <= 0f || snip.frameCount < 2) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val sub = copy(snip, m.getValue("TONE"), m.getValue("HANG"))
        val out = FloatArray(snip.samples.size) { i -> snip.samples[i] + depth * sub[i] }

        // Shape, not loudness: matched both ways, so a louder sum brings the hit down rather than clipping.
        var outPeak = 0f
        for (v in out) { val a = abs(v); if (a > outPeak) outPeak = a }
        val inPeak = snip.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val s = inPeak / outPeak
            for (i in out.indices) out[i] = (out[i] * s).coerceIn(-1f, 1f)
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }
}
