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
 * DRIVE is the owner's pick from the thicker round (2026-11-08: "Fuzz and grit are good"). A clean octave
 * of a tom or a kick lands mostly under 60 Hz, which phones and earbuds hardly play, so it read as thin.
 * DRIVE puts the copy through a soft clipper, whose harmonics land where a drum's thickness is heard:
 * 0 is the clean copy, [GRIT] is the round's Grit (a symmetric clipper at gain 10, odd harmonics only) and
 * 1 its Fuzz (gain 25 and lopsided, so even harmonics too, the octave's second being the hit's own pitch).
 * Past Grit the clipper also leans, its top is closed toward [FUZZ_TOP_HZ], and it follows the clean copy's
 * envelope, because a hard clipper holds a decaying tail at full level and the copy would stop decaying.
 *
 * DEPTH sets the copy's peak against the pad's own, 0 off up to [MAX_LEVEL] times the hit, so it means the
 * same on a loud and a quiet pad. The mix is then squashed into the pad's peak (a tanh, the round's Glue)
 * rather than scaled down to it: scaling a louder sum pulls the whole hit down (the brainstorm's "more AMT
 * reads as quieter" hazard), while the squash keeps the hit's level and brings the octave's body up under
 * it. The squash's hardness grows with DEPTH from linear at 0, so a little copy never squashes the hit.
 * TONE, HANG and DRIVE are their own neutrals, so AMT, which walks a character toward its neutrals, moves
 * DEPTH alone: AMT is how much octave, the other three are which octave.
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
        MacroSpec("DEPTH", 0.5f),                   // the copy's peak against the pad's: 0 off .. MAX_LEVEL times the hit
        MacroSpec("TONE", 0.5f, neutral = 0.5f),    // the copy's low-pass, TONE_DARK_HZ .. TONE_OPEN_HZ; its own neutral
        MacroSpec("HANG", 1f, neutral = 1f),        // 0 the copy follows the hit's envelope, 1 its own (twice as long); its own neutral
        MacroSpec("DRIVE", GRIT, neutral = GRIT),   // 0 clean, GRIT the owner's Grit, 1 the owner's Fuzz; its own neutral
    )

    /** DEPTH 1: the copy's peak against the hit's. The thicker round's loudest Grit and Fuzz were 1.4. */
    const val MAX_LEVEL = 1.5f

    /** Where DRIVE gives the round's Grit (clipper gain 10), and so the section's default. */
    const val GRIT = 0.375f

    /** The clipper's gain at DRIVE 1 (the round's Fuzz); gain is 1 + (FUZZ_GAIN - 1) * DRIVE. */
    const val FUZZ_GAIN = 25f

    /** The clipper's lean at DRIVE 1, as an input offset; it grows from 0 at GRIT. */
    const val FUZZ_BIAS = 0.35f

    /** The low-pass after the clipper at DRIVE 1; at GRIT it is open ([GRIT_TOP_HZ]). */
    const val FUZZ_TOP_HZ = 1800f
    const val GRIT_TOP_HZ = 12000f

    /** The mix squash's tanh hardness at DEPTH 1; it scales with the copy's level, so DEPTH 0 is linear. */
    const val GLUE = 4f

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
     * onset, held toward the hit's envelope by HANG, band-limited by TONE and [FLOOR_HZ], driven by DRIVE,
     * faded and cut at the pad's end, at the pad's own peak. All zeros for a silent pad.
     */
    internal fun copy(snip: Snip, tone: Float, hang: Float, drive: Float = 0f): FloatArray {
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

        drive(out, ch, rate, drive.coerceIn(0f, 1f))

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

    /**
     * DRIVE, in place on the band-limited copy: a tanh clipper on the copy normalised to its peak, blended in
     * over the first tenth of the knob so DRIVE 0 is the clean copy exactly. Past [GRIT] the clipper leans,
     * closes its top and follows the clean copy's envelope; the floor high-pass runs again after it, because a
     * leaning clipper makes DC. Linked: each frame's clipper gain comes from its loudest channel and is
     * applied to every channel, so the clipper never moves the image.
     */
    private fun drive(out: FloatArray, ch: Int, rate: Int, drive: Float) {
        if (drive <= 0f) return
        var p = 0f
        for (v in out) { val a = abs(v); if (a > p) p = a }
        if (p <= 1e-9f) return
        val frames = out.size / ch
        val gain = 1f + (FUZZ_GAIN - 1f) * drive
        val fuzz = ((drive - GRIT) / (1f - GRIT)).coerceIn(0f, 1f)
        val bias = FUZZ_BIAS * fuzz
        val wet = (drive / 0.1f).coerceAtMost(1f)
        val top = Dsp.expMap(fuzz, GRIT_TOP_HZ, FUZZ_TOP_HZ)
        val env = if (fuzz > 0f) {
            val level = FloatArray(frames) { f -> var a = 0f; for (c in 0 until ch) a = maxOf(a, abs(out[f * ch + c])); a / p }
            Hush.slidingMax(level, (WINDOW_SEC * rate).toInt().coerceAtLeast(1))
        } else null
        val k = kotlin.math.sqrt(2f)
        val tb = kotlin.math.tanh(bias)
        val norm = kotlin.math.tanh(gain + bias) - tb
        val slope0 = gain * (1f - tb * tb) / norm
        val clip = FloatArray(frames) { f ->
            var v = 0f
            for (c in 0 until ch) { val x = out[f * ch + c] / p; if (abs(x) > abs(v)) v = x }
            if (abs(v) < 1e-7f) slope0 else (kotlin.math.tanh(gain * v + bias) - tb) / norm / v
        }
        for (c in 0 until ch) {
            val lp = Dsp.TptSvf(rate)
            val hp = Dsp.TptSvf(rate)
            for (n in 0 until frames) {
                val i = n * ch + c
                val x = out[i] / p
                var y = x * clip[n]
                lp.process(y, top, k)
                hp.process(lp.low, FLOOR_HZ, k)
                y = hp.high
                if (env != null) y *= 1f - fuzz + fuzz * env[n]
                out[i] = p * ((1f - wet) * x + wet * y)
            }
        }
    }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        // A value that is not a number is left at the default: a NaN would otherwise ride through the clamp into the mix.
        for ((k, v) in macros) if (m.containsKey(k) && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        val depth = m.getValue("DEPTH")
        if (depth <= 0f || snip.frameCount < 2) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val level = depth * MAX_LEVEL
        val sub = copy(snip, m.getValue("TONE"), m.getValue("HANG"), m.getValue("DRIVE"))
        val out = FloatArray(snip.samples.size) { i -> snip.samples[i] + level * sub[i] }

        // Shape, not loudness: the sum is squashed into the pad's peak, so the hit keeps its level and the
        // octave's body comes up under it. Hardness grows with the copy's level; near 0 the squash is linear.
        // Linked like the clipper: one gain per frame, from its loudest channel.
        var outPeak = 0f
        for (v in out) { val a = abs(v); if (a > outPeak) outPeak = a }
        val inPeak = snip.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val hard = (GLUE * depth).coerceAtLeast(1e-3f)
            val scale = inPeak / kotlin.math.tanh(hard)
            val ch = snip.channels
            for (f in 0 until snip.frameCount) {
                var m = 0f
                for (c in 0 until ch) m = maxOf(m, abs(out[f * ch + c]))
                val g = if (m < 1e-9f) scale * hard / outPeak else scale * kotlin.math.tanh(hard * m / outPeak) / m
                for (c in 0 until ch) out[f * ch + c] = (out[f * ch + c] * g).coerceIn(-1f, 1f)
            }
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }
}
