package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * VALVE - a tube amp and its speaker on any pad: a snare through a stack, a
 * vocal chop through a combo, a synth pad driven until it breaks up. MAGNET's
 * electric string lands through it by recipe; nothing about it is guitar-only
 * (docs/superpowers/specs/2026-09-29-magnet-valve-design.md).
 *
 * DRIVE is the gain into an asymmetric tube curve (soft on the negative side,
 * so it adds even harmonics as well as odd); SAG is the supply giving way after
 * a loud hit, a level dip on the tube's own output; TONE shapes what comes
 * *out* of the tube (EQ sits before VALVE in the rack and shapes what goes
 * in); CAB is the speaker, none at 0 growing to a dark closed wall at 1.
 *
 * Level-relative: the pad is normalised to peak 1 before the gain law and
 * peak-matched after, so DRIVE means the same on a whisper and a slam. The
 * snip's own peak is used for every channel, so a stereo image survives.
 *
 * The first section that oversamples. At the snip's rate a hot tube folds its
 * harmonics back into the audible band: the Phase-0 spike measured a steady
 * 247 Hz probe at its own DRIVE 1 (gain 35 on its law) with energy between
 * harmonics only 31.9 dB down at 1x against 49.4 dB at 4x (an 8x reference read
 * 50.9; that was the spike's own probe and model of the tube). ValveTest's
 * steady probe reads 28.8 dB at 1x against 51.3 dB at 4x at gain 35 (DRIVE 1 on
 * the V1 law), and on the V1.1 law, where DRIVE 1 is gain 1000, 26.8 dB at 1x
 * against 40.6 dB at 4x (its aliasing test prints both).
 * The round trip is zero-stuffing interpolated by [Tide.bandLimit] on the way
 * up (never the general resampler there, which cost 72 ms per rendered second
 * on its own) and [Dsp.decimate] on the way down, the melodic engines' own -
 * `Resampler`'s 2:1 fast path twice. The round trip is always on: a gate that
 * skips it at low DRIVE would save about 31 ms per rendered second (33.4 to
 * 2.1, measured) but changes the speaker's tone, because the cabinet's
 * filters run at the snip's rate on that path (a snare through CAB 0.5 steps
 * 1.45 dB in its top third-octave; see
 * docs/superpowers/plans/2026-09-30-valve-v1-1-spike.md). The band-limit's
 * 19.5 kHz corner is absolute, so the round trip assumes the rack's 44.1 kHz
 * snips: below about 40 kHz the zero-stuffing images would enter the tube
 * nearly unattenuated, and a 48 kHz snip loses its 19-24 kHz.
 *
 * Every number marked shape below is a listening value: V1's from the
 * specification the owner attached, V1.1's chosen by the owner from candidate
 * clips (docs/superpowers/plans/2026-09-30-valve-v1-1.md).
 */
object Valve {

    val MACROS: List<MacroSpec> = listOf(
        // 0.7 is gain 11.3 on the V1.1 law. The owner chose 0.7 twice: first heard on V1's law at
        // the round-two listen (gain 4.9), then heard on this one at the confirmation listen
        // (gain 11.3) against 0.65 (gain 5.4), which had kept the first sound.
        MacroSpec("DRIVE", 0.7f),
        MacroSpec("SAG", 0.35f),
        // 0.5 is flat, so the pad sheet's AMT fade lands on a flat tone.
        MacroSpec("TONE", 0.5f, neutral = 0.5f),
        MacroSpec("CAB", 0.6f),
    )

    /** The gain law's ends. 0.05 keeps the tube linear to about 0.1 % - the neutral point's near-copy. */
    internal const val GAIN_MIN = 0.05f

    /** V1's top, kept as the law's shape at and below [GAIN_PIVOT]: 0.05 * 700^DRIVE. */
    private const val V1_GAIN_TOP = 35f

    /** The V1.1 top (shape): the owner picked gain 1000 on kick, snare and brass at the round-two listen; the choir's 100 sits at DRIVE about 0.845. */
    internal const val GAIN_MAX = 1_000f

    /** DRIVE at which the law leaves V1's curve for the steeper run to [GAIN_MAX] (shape); everything the owner heard at or below it is unchanged. */
    internal const val GAIN_PIVOT = 0.6f

    /**
     * Supply sag (shape; the owner chose it from three mechanisms at the V1.1
     * listen): a post-tube gain reduction, `y = t / (1 + SAG * K * env)`, where
     * `env` follows the tube's own output level `|t|` with a 5 ms attack and a
     * 120 ms release (time constants, not completion times) and the previous
     * sample's value sets this sample's gain, so there is no algebraic loop.
     *
     * It replaces V1's bias, which moved the tube's rest point and released
     * over 120 ms, slower than the 5 Hz blocker follows, leaving a decaying DC
     * step on hot pads (a kick at gain 35 ended on 3.6 % of its peak). This one
     * has no rest-point shift: SAG 1 adds no end step (the spike's kick at gain 35
     * ended on 0.08 %). The tube's own asymmetry still leaves about 1.7 % on a kick
     * at gain 1000 whether SAG is up or not. The owner heard 1.7 % on a brass as
     * clean before V1.1, but that was DRIVE 1 on the V1 law (the V1 gate's brass at
     * gain 35, SAG 0.35); at gain 1000 the brass reads 0.255 % and 1.72 % is the kick's.
     *
     * K is 3, the top of the range the spike searched (0.8 to 3): the kick's level
     * 60-160 ms after the hit, re its first 20 ms, falls 2.2 dB against SAG 0 at DRIVE
     * 0.6 (gain 2.5) and about 3 dB at gain 35 (the spike, DRIVE 1 on the V1 law); the
     * spike aimed at 4-6 dB, and a slower charge, not more depth, is the likely lever,
     * unmeasured. At gain 1000 (DRIVE 1, CAB 0.6) the same level drops 3.15 dB on the
     * kick and 3.28 dB on the snare at SAG 1, and about 1.6 dB at SAG 0.35, flat from 20
     * to 160 ms: a steady level offset after the hit's first milliseconds, not a dip
     * and recovery, so it did not shrink from gain 35. The owner heard SUPPLY at gain
     * 35 and below before this was measured. At the confirmation listen the owner heard no
     * effect from SAG at DRIVE 1 on the kick and snare (loudness-matched clips level away the
     * steady offset), so SAG is an effect of the lower and middle DRIVE range.
     */
    private const val SUPPLY_K = 3f
    private const val SUPPLY_ATTACK_SECONDS = 0.005f
    private const val SUPPLY_RELEASE_SECONDS = 0.120f

    /**
     * The closed wall (shape; the owner chose 3.2 kHz with two poles at the V1.1
     * listen). CAB at or below [WALL_FROM] is V1's speaker exactly; above it the
     * voice coil's corner runs from V1's 5.02 kHz to [WALL_HZ] at CAB 1 and a
     * second identical pole fades in, so CAB 1 is two poles at 3.2 kHz (-3 dB at
     * about 2.9 kHz on noise, against 5.1 kHz for V1's wall).
     */
    private const val WALL_FROM = 0.6f
    private const val WALL_HZ = 3_200f

    /**
     * The DC blocker after the asymmetric curve. 5 Hz, not the fleet's usual
     * 20: a one-pole high-pass at 20 Hz is already 1 dB down at 40 Hz, which
     * would break the neutral point's promise on a kick.
     */
    internal const val DC_HZ = 5f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: Random): Map<String, Float> = MACROS.associate { it.name to random.nextFloat() }

    /**
     * DRIVE's gain into the tube, on the pad normalised to peak 1: V1's `0.05 * 700^DRIVE`
     * up to [GAIN_PIVOT] (gain 2.55), then log-linear from there to [GAIN_MAX] at DRIVE 1.
     */
    fun gainFor(drive: Float): Float {
        val d = drive.coerceIn(0f, 1f)
        if (d <= GAIN_PIVOT) return Dsp.expMap(d, GAIN_MIN, V1_GAIN_TOP)
        val atPivot = Dsp.expMap(GAIN_PIVOT, GAIN_MIN, V1_GAIN_TOP).toDouble()
        return (atPivot * exp(ln(GAIN_MAX / atPivot) * ((d - GAIN_PIVOT) / (1f - GAIN_PIVOT)))).toFloat()
    }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip = process(snip, macros, oversample = true)

    /**
     * [oversample] false runs the amp at the snip's own rate, with no 4x round trip. It is for callers
     * that need the native-rate path: the aliasing test, which proves its probe sees fold-back, and the
     * audition generators' native-rate clips (the MAGNET audition's P1 clips, which run the amp at the
     * engine's render rate, already oversampled, before the output chain, and the VALVE audition's
     * FOLDBACK `snare_1x`, which plays the fold-back at the snip rate).
     */
    internal fun process(snip: Snip, macros: Map<String, Float>, oversample: Boolean): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val inPeak = snip.peak()
        if (inPeak <= 0f) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val rate = snip.sampleRate
        val channels = snip.channels
        val frames = snip.frameCount
        val workRate = if (oversample) rate * Dsp.OVERSAMPLE else rate
        val outs = Array(channels) { ch ->
            val x = FloatArray(frames) { f -> snip.samples[f * channels + ch] / inPeak }
            val work = if (oversample) upsample(x, rate) else x
            val y = stage(work, m, workRate)
            if (oversample) {
                Tide.bandLimit(y, workRate)
                Dsp.decimate(y, rate)
            } else {
                y
            }
        }

        val out = FloatArray(frames * channels)
        for (f in 0 until frames) {
            for (ch in 0 until channels) out[f * channels + ch] = outs[ch].getOrElse(f) { 0f }
        }
        var outPeak = 0f
        for (v in out) {
            val a = abs(v)
            if (a > outPeak) outPeak = a
        }
        if (outPeak > 0f) {
            val k = inPeak / outPeak
            for (i in out.indices) out[i] *= k
        }
        return Snip(out, channels, rate)
    }

    /** Zero-stuffing by [Dsp.OVERSAMPLE], interpolated by the band-limiter the melodic engines already use before they decimate. */
    internal fun upsample(x: FloatArray, rate: Int): FloatArray {
        val n = Dsp.OVERSAMPLE
        val up = FloatArray(x.size * n)
        for (i in x.indices) up[i * n] = x[i] * n
        Tide.bandLimit(up, rate * n)
        return up
    }

    private fun curve(b: Float): Float = if (b >= 0f) tanh(b) else b / sqrt(1f + b * b)

    /** Gain, the tube, the supply, the DC blocker, then TONE and CAB - one channel at [rate]. */
    private fun stage(x: FloatArray, m: Map<String, Float>, rate: Int): FloatArray {
        val g = gainFor(m.getValue("DRIVE"))
        val sag = m.getValue("SAG")
        val t = FloatArray(x.size) { curve(x[it] * g) }
        val env = supplyEnv(t, rate)
        val dc = Dsp.OnePole(rate)
        val out = FloatArray(x.size)
        for (i in x.indices) {
            val y = t[i] * (1f / (1f + sag * SUPPLY_K * env[i]))
            out[i] = y - dc.lp(y, DC_HZ)
        }
        tone(out, m.getValue("TONE"), rate)
        cabinet(out, m.getValue("CAB"), rate)
        return out
    }

    /**
     * The supply follower's value *before* it sees sample i of [t], the tube's output: the
     * previous sample's value sets this sample's gain. Charges toward a higher `|t|` with a
     * 5 ms time constant and recovers toward a lower one with 120 ms.
     */
    internal fun supplyEnv(t: FloatArray, rate: Int): FloatArray {
        val charge = 1f - exp(-1.0 / (SUPPLY_ATTACK_SECONDS * rate)).toFloat()
        val release = 1f - exp(-1.0 / (SUPPLY_RELEASE_SECONDS * rate)).toFloat()
        var env = 0f
        return FloatArray(t.size) { i ->
            val used = env
            val a = abs(t[i])
            env += (if (a > env) charge else release) * (a - env)
            used
        }
    }

    /**
     * The tone after the tube: 0 a mid scoop (-12 dB around 380 Hz) with the
     * top 6 dB back (a shelf above 3 kHz), 0.5 flat and skipped outright
     * rather than filtered at 0 dB, 1 mids (+6 dB around 650 Hz) and top
     * (+6 dB above 3 kHz) forward. The mid centre slides 380 -> 650 Hz with
     * the knob and every gain is a signed distance from 0.5, the cut side
     * twice as steep as the boost side (shape).
     */
    internal fun tone(buf: FloatArray, tone: Float, rate: Int) {
        val d = tone - 0.5f
        if (d == 0f) return
        val midDb = if (d < 0f) 24f * d else 12f * d
        val mid = Dsp.Biquad().apply { peaking(Dsp.lin(tone, 380f, 650f), midDb, 0.9f, rate) }
        val top = Dsp.Biquad().apply { highShelf(3_000f, 12f * d, rate) }
        for (i in buf.indices) buf[i] = top.process(mid.process(buf[i]))
    }

    /** The voice coil's corner: V1's 5.8 kHz to 4.5 kHz map up to [WALL_FROM], then linearly to [WALL_HZ] at CAB 1. */
    private fun coilCorner(cab: Float): Float =
        if (cab <= WALL_FROM) {
            Dsp.lin(cab, 5_800f, 4_500f)
        } else {
            Dsp.lin((cab - WALL_FROM) / (1f - WALL_FROM), Dsp.lin(WALL_FROM, 5_800f, 4_500f), WALL_HZ)
        }

    /**
     * The speaker: nothing at 0. The network fades in over the first quarter
     * of the knob - every gain and weight scales with cab/0.25 and the voice
     * coil's corner closes from the one-pole's own cap toward 5.8 kHz, so
     * CAB 0+ is transparent - and is fully in at 0.25 as a bright open-back
     * combo: cone thump +6 dB at 102 Hz, the open-back cancellation notch
     * -6.8 dB at 470 Hz, two cone-breakup resonances at 2.6 and 3.75 kHz, the
     * voice coil rolling the top off from 5.5 kHz. The network grows to V1's speaker
     * at 0.6 (coil 5.0 kHz, thump 90.8 Hz, the notch 428 Hz at -3.6 dB), whose thump
     * and notch keep moving to 78 Hz and filled in at CAB 1, while the coil runs to
     * 3.2 kHz with a second identical pole faded in over 0.6-1 ([WALL_FROM],
     * [WALL_HZ]; shape). (The 110 Hz, 500 Hz, -9 dB and 5.8 kHz formula ends are the
     * network's CAB 0 anchors, where it is bypassed.)
     */
    internal fun cabinet(buf: FloatArray, cab: Float, rate: Int) {
        if (cab <= 0f) return
        val w = (cab / 0.25f).coerceAtMost(1f)
        val thump = Dsp.Biquad().apply { peaking(Dsp.lin(cab, 110f, 78f), 6f * w, Dsp.lin(cab, 1.6f, 2.4f), rate) }
        val notch = Dsp.Biquad().apply { peaking(Dsp.lin(1f - cab, 380f, 500f), -9f * (1f - cab) * w, 2f, rate) }
        val breakup1 = Dsp.Biquad().apply { bandpass(2_600f, 3.5f, rate) }
        val breakup2 = Dsp.Biquad().apply { bandpass(3_750f, 4f, rate) }
        // The map's open end is the one-pole's own cap (0.45 x the work rate, ~79 kHz at 4x),
        // so CAB 0+ is transparent (-0.09 dB at 16 kHz): 20 kHz was a real corner at 176.4 kHz,
        // a 2 dB step in the top octave where CAB 0 bypasses - the one discontinuity in the AMT fade.
        val coilHz = Dsp.expMap(1f - w, coilCorner(cab), rate * 0.45f)
        val second = ((cab - WALL_FROM) / (1f - WALL_FROM)).coerceIn(0f, 1f)
        val coil = Dsp.OnePole(rate)
        if (second == 0f) {
            for (i in buf.indices) {
                val s = buf[i]
                val body = notch.process(thump.process(s))
                val breakup = w * (0.35f * breakup1.process(s) + 0.25f * breakup2.process(s))
                buf[i] = coil.lp(body + breakup, coilHz)
            }
        } else {
            val coil2 = Dsp.OnePole(rate)
            for (i in buf.indices) {
                val s = buf[i]
                val body = notch.process(thump.process(s))
                val breakup = w * (0.35f * breakup1.process(s) + 0.25f * breakup2.process(s))
                val one = coil.lp(body + breakup, coilHz)
                buf[i] = one + second * (coil2.lp(one, coilHz) - one)
            }
        }
    }
}
