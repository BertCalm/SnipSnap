package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.exp
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
 * so it adds even harmonics as well as odd); SAG is the supply giving way on
 * loud passages, biasing the tube further toward cutoff; TONE shapes what
 * comes *out* of the tube (EQ sits before VALVE in the rack and shapes what
 * goes in); CAB is the speaker, none at 0 growing to a dark closed wall at 1.
 *
 * Level-relative: the pad is normalised to peak 1 before the gain law and
 * peak-matched after, so DRIVE means the same on a whisper and a slam. The
 * snip's own peak is used for every channel, so a stereo image survives.
 *
 * The first section that oversamples. At the snip's rate a hot tube folds its
 * harmonics back into the audible band: the Phase-0 spike measured a steady
 * 247 Hz probe at DRIVE 1 with energy between harmonics only 31.9 dB down at
 * 1x against 49.4 dB at 4x (an 8x reference read 50.9). The round trip is
 * zero-stuffing interpolated by [Tide.bandLimit], never the general resampler,
 * which cost 72 ms per rendered second on its own.
 *
 * Every number marked shape below is a listening value from the specification
 * the owner attached, kept until the V1 listen moves it.
 */
object Valve {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("DRIVE", 0.45f),
        MacroSpec("SAG", 0.35f),
        // 0.5 is flat, so the pad sheet's AMT fade lands on a flat tone.
        MacroSpec("TONE", 0.5f, neutral = 0.5f),
        MacroSpec("CAB", 0.6f),
    )

    /** The gain law's ends. 0.05 keeps the tube linear to about 0.1 % - the neutral point's near-copy. */
    internal const val GAIN_MIN = 0.05f
    internal const val GAIN_MAX = 35f

    /**
     * Supply sag (shape): a follower whose target is how far the signal goes
     * over the rail (|v| - 1, or 0 under it). It charges toward a higher target
     * with a 5 ms time constant and recovers toward a lower one with a 120 ms
     * time constant (time constants, not completion times), the same 120 ms
     * whether the lower target is 0 or an overshoot that is only smaller.
     */
    private const val SAG_ATTACK_SECONDS = 0.005f
    private const val SAG_RELEASE_SECONDS = 0.120f
    private const val SAG_DEPTH = 0.45f

    /**
     * The DC blocker after the asymmetric curve. 5 Hz, not the fleet's usual
     * 20: a one-pole high-pass at 20 Hz is already 1 dB down at 40 Hz, which
     * would break the neutral point's promise on a kick.
     */
    internal const val DC_HZ = 5f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: Random): Map<String, Float> = MACROS.associate { it.name to random.nextFloat() }

    /** DRIVE's gain into the tube, on the pad normalised to peak 1. */
    fun gainFor(drive: Float): Float = Dsp.expMap(drive.coerceIn(0f, 1f), GAIN_MIN, GAIN_MAX)

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip = process(snip, macros, oversample = true)

    /** [oversample] false exists only so the aliasing test can prove the probe sees fold-back. */
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

    /** Gain, sag, the tube, the DC blocker, then TONE and CAB - one channel at [rate]. */
    private fun stage(x: FloatArray, m: Map<String, Float>, rate: Int): FloatArray {
        val g = gainFor(m.getValue("DRIVE"))
        val sag = m.getValue("SAG")
        val v = FloatArray(x.size) { x[it] * g }
        val vSag = sagTrack(v, rate)
        val dc = Dsp.OnePole(rate)
        val out = FloatArray(x.size)
        for (i in x.indices) {
            val b = v[i] - vSag[i] * sag * SAG_DEPTH
            val t = if (b >= 0f) tanh(b) else b / sqrt(1f + b * b)
            out[i] = t - dc.lp(t, DC_HZ)
        }
        tone(out, m.getValue("TONE"), rate)
        cabinet(out, m.getValue("CAB"), rate)
        return out
    }

    /** The supply follower's value at every sample of [v], the signal after the gain. */
    internal fun sagTrack(v: FloatArray, rate: Int): FloatArray {
        val charge = 1f - exp(-1.0 / (SAG_ATTACK_SECONDS * rate)).toFloat()
        val release = 1f - exp(-1.0 / (SAG_RELEASE_SECONDS * rate)).toFloat()
        var vSag = 0f
        return FloatArray(v.size) { i ->
            val a = abs(v[i])
            val target = if (a > 1f) a - 1f else 0f
            vSag += (if (target > vSag) charge else release) * (target - vSag)
            vSag
        }
    }

    /**
     * The tone after the tube: 0 a mid scoop (-12 dB, around 380 Hz), 0.5
     * flat, 1 mids (+6 dB, around 650 Hz) and top (+6 dB above 3 kHz)
     * forward. Every gain is a signed distance from 0.5, so 0.5 is skipped
     * outright rather than filtered at 0 dB (shape).
     */
    internal fun tone(buf: FloatArray, tone: Float, rate: Int) {
        val d = tone - 0.5f
        if (d == 0f) return
        val midDb = if (d < 0f) 24f * d else 12f * d
        val mid = Dsp.Biquad().apply { peaking(Dsp.lin(tone, 380f, 650f), midDb, 0.9f, rate) }
        val top = Dsp.Biquad().apply { highShelf(3_000f, 12f * d, rate) }
        for (i in buf.indices) buf[i] = top.process(mid.process(buf[i]))
    }

    /**
     * The speaker: nothing at 0. The network fades in over the first quarter
     * of the knob - every gain and weight scales with cab/0.25 and the voice
     * coil's corner closes from the one-pole's own cap toward 5.8 kHz, so
     * CAB 0+ is transparent - and is fully in at 0.25 as a bright open-back
     * combo: cone thump +6 dB at 102 Hz, the open-back cancellation notch
     * -6.8 dB at 470 Hz, two cone-breakup resonances at 2.6 and 3.75 kHz, the
     * voice coil rolling the top off from 5.5 kHz. It grows to a dark closed
     * wall at 1: thump at 78 Hz, the notch filled in as the back closes, the
     * coil at 4.5 kHz. (The 110 Hz, 500 Hz, -9 dB and 5.8 kHz formula ends
     * are the network's CAB 0 anchors, where it is bypassed; shape.)
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
        val coilHz = Dsp.expMap(1f - w, Dsp.lin(cab, 5_800f, 4_500f), rate * 0.45f)
        val coil = Dsp.OnePole(rate)
        for (i in buf.indices) {
            val s = buf[i]
            val body = notch.process(thump.process(s))
            val breakup = w * (0.35f * breakup1.process(s) + 0.25f * breakup2.process(s))
            buf[i] = coil.lp(body + breakup, coilHz)
        }
    }
}
