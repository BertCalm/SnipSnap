package com.snipsnap.synth

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * MAGNET Phase 0, part B: a THROWAWAY spike, never shipped. An electric guitar
 * built from the shared string toolkit ([Strings.pluck] into a Jaffe-Smith
 * pickup comb, a pickup resonance, then CHUG's amp law), rendered three ways to
 * find where the amp stage (the future VALVE rack section) should live.
 *
 * Every constant below marked "shape" is a listening guess, not physics, used
 * as the brief gave it: the voice table, the 0.0278 coil-spacing ratio, the
 * neck (0.42) and bridge (0.12) pickup positions, the humbucker's 0.707, the
 * tone stack's corners and gains, the cabinet's two bandpass taps and the amp's
 * sag constants.
 */
internal object CoilSpike {

    /** The render rate for the in-render placement: 176 400 Hz. */
    val R: Int = Dsp.RATE * Dsp.OVERSAMPLE

    /** Neck and bridge pickup positions as a fraction of the open string (shape). */
    const val NECK = 0.42f
    const val BRIDGE = 0.12f

    /** Both voices' string position comb at the exciter (shape). */
    const val PICK_POSITION = 0.10f

    /** Humbucker coil spacing over an open string: 18 mm / 648 mm (shape). */
    const val COIL_SPACING = 0.0278f

    /** Every value here is shape: root, coils, string body, pick corner, pickup resonance, BLEND default. */
    enum class Voice(
        val rootHz: Float,
        val humbucker: Boolean,
        val bodyLoopHz: Float,
        val pickHz: Float,
        val resHz: Float,
        val resK: Float,
        val blendDefault: Float,
        val muteDefault: Float,
        val pickDefault: Float,
    ) {
        JANGLE(82.41f, false, 7000f, 9000f, 4500f, 0.40f, 0.5f, 0.15f, 0.6f),
        CHUG(61.74f, true, 5500f, 6000f, 2800f, 0.55f, 1.0f, 0.35f, 0.55f),
    }

    /** The eight macros, 0..1: four for the engine, four for the amp. */
    data class Macros(
        val tune: Float = 0.5f,
        val mute: Float = 0.15f,
        val pick: Float = 0.6f,
        val blend: Float = 0.5f,
        val drive: Float = 0.45f,
        val sag: Float = 0.35f,
        val tone: Float = 0.5f,
        val cab: Float = 0.6f,
    )

    val MACRO_NAMES = listOf("TUNE", "MUTE", "PICK", "BLEND", "DRIVE", "SAG", "TONE", "CAB")

    fun defaults(v: Voice) = Macros(mute = v.muteDefault, pick = v.pickDefault, blend = v.blendDefault)

    fun set(m: Macros, name: String, value: Float): Macros = when (name) {
        "TUNE" -> m.copy(tune = value)
        "MUTE" -> m.copy(mute = value)
        "PICK" -> m.copy(pick = value)
        "BLEND" -> m.copy(blend = value)
        "DRIVE" -> m.copy(drive = value)
        "SAG" -> m.copy(sag = value)
        "TONE" -> m.copy(tone = value)
        "CAB" -> m.copy(cab = value)
        else -> error("unknown macro $name")
    }

    /** TUNE quantises to semitones over two octaves above the root: `root * 2^(round(tune*24)/12)`. */
    fun f0(v: Voice, tune: Float): Float =
        (v.rootHz * 2.0.pow(Math.round(tune * 24f) / 12.0)).toFloat()

    /** The exciter's low-pass corner: PICK scales it, and at the default it is close to `pickHz`. */
    fun pickCornerHz(v: Voice, pick: Float): Float = v.pickHz * Dsp.expMap(pick, 0.35f, 1.6f) / 0.95f

    // ------------------------------------------------------------------ string

    fun string(v: Voice, m: Macros): FloatArray {
        val f0 = f0(v, m.tune)
        val raw = Strings.pluck(
            f0, 4.0f, Strings.damping(m.mute, v.bodyLoopHz), pickCornerHz(v, m.pick),
            Dsp.seedFor("COILSPIKE", v.name), R, position = PICK_POSITION,
        )
        return Strings.trimToDecay(raw, R, 0.25f, 4.0f)
    }

    // ------------------------------------------------------------------ pickup

    /** The comb's delay in samples: `round(p * rate / f0)`, at least 1. */
    fun combDelay(p: Float, f0: Float, rate: Int): Int = (p * rate / f0).roundToInt().coerceAtLeast(1)

    /**
     * Jaffe-Smith position comb, `c(y, p)[n] = y[n] - y[n - D]`, `D = round(p * rate / f0)`:
     * the string's physical period `rate / f0`, not the loop length. [lead] delays
     * both taps by that many samples (0 for the brief's comb as written).
     */
    fun comb(y: FloatArray, p: Float, f0: Float, rate: Int, lead: Int = 0): FloatArray {
        val d = combDelay(p, f0, rate)
        val out = FloatArray(y.size)
        for (n in y.indices) {
            val a = n - lead
            val b = n - lead - d
            out[n] = (if (a >= 0) y[a] else 0f) - (if (b >= 0) y[b] else 0f)
        }
        return out
    }

    /**
     * One pickup at position [p]: a single coil is the comb, a humbucker is
     * `0.707 * (c(y, p) + c(y, p + dp))`; then the pickup's resonance, a
     * [Dsp.TptSvf] low output at [resHz] and [k].
     *
     * [aligned] (default off, the brief's model as written) matters only to a
     * humbucker: each comb `1 - z^-D` carries a linear-phase term `z^(-D/2)`,
     * and two combs of different D summed as written do not have the same
     * phase, so their sum is not `sin(pi k p1) + sin(pi k p2)` and has no
     * coil-spacing notch. Aligned, each comb's taps are delayed so that its
     * centre sits at the longer comb's centre (a lead of `(Dmax - D) / 2`).
     */
    internal fun pickup(y: FloatArray, p: Float, humbucker: Boolean, dp: Float, resHz: Float, k: Float, f0: Float, rate: Int, aligned: Boolean = false): FloatArray {
        val combed = if (humbucker) {
            val d1 = combDelay(p, f0, rate)
            val d2 = combDelay(p + dp, f0, rate)
            val dMax = maxOf(d1, d2)
            val c1 = comb(y, p, f0, rate, if (aligned) (dMax - d1) / 2 else 0)
            val c2 = comb(y, p + dp, f0, rate, if (aligned) (dMax - d2) / 2 else 0)
            FloatArray(y.size) { 0.707f * (c1[it] + c2[it]) }
        } else comb(y, p, f0, rate)
        val svf = Dsp.TptSvf(rate)
        val out = FloatArray(y.size)
        for (i in combed.indices) {
            svf.process(combed[i], resHz, k)
            out[i] = svf.low
        }
        return out
    }

    /**
     * BLEND between the neck and bridge pickups: 0 is neck alone, 1 is bridge
     * alone, otherwise `(1 - BLEND) * neck + BLEND * bridge`. Each pickup is
     * a full [pickup] (comb then resonance); the resonance is linear, so
     * this is the same as summing the combs and resonating once.
     */
    fun voicePickup(y: FloatArray, v: Voice, m: Macros, f0: Float, resHz: Float = v.resHz, k: Float = v.resK): FloatArray {
        val dp = COIL_SPACING * (f0 / v.rootHz)
        val b = m.blend
        return when {
            b <= 0f -> pickup(y, NECK, v.humbucker, dp, resHz, k, f0, R)
            b >= 1f -> pickup(y, BRIDGE, v.humbucker, dp, resHz, k, f0, R)
            else -> {
                val neck = pickup(y, NECK, v.humbucker, dp, resHz, k, f0, R)
                val bridge = pickup(y, BRIDGE, v.humbucker, dp, resHz, k, f0, R)
                FloatArray(y.size) { (1f - b) * neck[it] + b * bridge[it] }
            }
        }
    }

    // ------------------------------------------------------------------ amp

    /**
     * CHUG's gain law for both voices (`g = expMap(DRIVE, 2, 35)`), at any
     * [rate]: gain, grid sag, asymmetric transfer, DC blocker, tone stack,
     * cabinet. The two cabinet bandpass taps read the tone stack's output
     * and are summed with the thump/notch cascade before the final
     * one-pole low-pass (the brief's "in parallel" read that way).
     */
    fun amp(buf: FloatArray, drive: Float, sag: Float, tone: Float, cab: Float, rate: Int): FloatArray {
        val g = Dsp.expMap(drive, 2f, 35f)
        val chargeA = 1.0 - exp(-1.0 / (0.005 * rate))
        val dischargeA = 1.0 - exp(-1.0 / (0.120 * rate))
        var vSag = 0f

        val dc = Dsp.OnePole(rate)
        val lowShelf = Dsp.Biquad().apply { lowShelf(120f, 3f, rate) }
        val mid = Dsp.Biquad().apply { peaking(Dsp.lin(tone, 380f, 650f), Dsp.lin(1f - tone, -12f, 2f), 1.4f, rate) }
        val highShelf = Dsp.Biquad().apply { highShelf(Dsp.expMap(tone, 2500f, 6000f), (tone - 0.5f) * 8f, rate) }
        val thump = Dsp.Biquad().apply { peaking(Dsp.lin(cab, 110f, 78f), 6f, Dsp.lin(cab, 1.6f, 2.4f), rate) }
        val notch = Dsp.Biquad().apply { peaking(Dsp.lin(1f - cab, 380f, 500f), (1f - cab) * -9f, 2.0f, rate) }
        val bp1 = Dsp.Biquad().apply { bandpass(2600f, 3.5f, rate) }
        val bp2 = Dsp.Biquad().apply { bandpass(3750f, 4.0f, rate) }
        val cabLp = Dsp.OnePole(rate)
        val cabHz = Dsp.expMap(1f - cab * 0.3f, 4500f, 5800f)

        val out = FloatArray(buf.size)
        for (i in buf.indices) {
            val x = buf[i] * g
            val ax = abs(x)
            vSag += if (ax > 1f) ((ax - 1f - vSag) * chargeA).toFloat() else ((0f - vSag) * dischargeA).toFloat()
            val b = x - vSag * sag * 0.45f
            val y0 = if (b >= 0f) tanh(b.toDouble()).toFloat() else b / sqrt(1f + b * b)
            val y = y0 - dc.lp(y0, 20f)
            val t = highShelf.process(mid.process(lowShelf.process(y)))
            val cascade = notch.process(thump.process(t))
            val sum = cascade + bp1.process(t) * 0.35f + bp2.process(t) * 0.25f
            out[i] = cabLp.lp(sum, cabHz)
        }
        return out
    }

    // ------------------------------------------------------------------ output chain

    /** The melodic fleet's chain, from a buffer at [R]: band-limit, decimate, mean, 20 Hz high-pass, level, tail fade. */
    fun outputChain(rBuf: FloatArray): Snip {
        val b = rBuf.copyOf()
        Tide.bandLimit(b, R)
        val out = Dsp.decimate(b, Dsp.RATE)
        var mean = 0.0
        for (v in out) mean += v
        mean /= out.size.coerceAtLeast(1)
        for (i in out.indices) out[i] = (out[i] - mean).toFloat()
        val hp = Dsp.OnePole(Dsp.RATE)
        for (i in out.indices) {
            val x = out[i]
            out[i] = x - hp.lp(x, 20f)
        }
        Dsp.levelTo(out, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return Snip(out, 1, Dsp.RATE)
    }

    private fun peakOf(a: FloatArray): Float {
        var p = 0f
        for (v in a) { val x = abs(v); if (x > p) p = x }
        return p
    }

    private fun peakMatch(out: FloatArray, target: Float) {
        val p = peakOf(out)
        if (p > 0f && target > 0f) {
            val k = target / p
            for (i in out.indices) out[i] *= k
        }
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Double> {
        val t0 = System.nanoTime()
        val r = block()
        return r to (System.nanoTime() - t0) / 1e6
    }

    // ------------------------------------------------------------------ renders and placements

    /** The string and pickup, at [R] ([buf]) and as the melodic chain's 44.1 kHz [snip]. */
    class Dry(val buf: FloatArray, val snip: Snip, val stringMs: Double, val pickupMs: Double) {
        val seconds: Double get() = buf.size.toDouble() / R
    }

    /** A placement's result: the final [out], and the amp's cost (amp alone, and the whole path from the dry side). */
    class Render(val out: Snip, val ampMs: Double, val pathMs: Double)

    fun dry(v: Voice, m: Macros): Dry {
        val (s, sMs) = timed { string(v, m) }
        val f0 = f0(v, m.tune)
        val (p, pMs) = timed { voicePickup(s, v, m, f0) }
        return Dry(p, outputChain(p), sMs, pMs)
    }

    /** P1, in-render: the amp runs at [R] on the raw string+pickup buffer, then the output chain. [scaleToPeak] pre-scales the buffer's peak (the levelled variant). */
    fun p1(d: Dry, m: Macros, scaleToPeak: Float? = null): Render {
        val src = if (scaleToPeak != null) d.buf.copyOf().also { peakMatch(it, scaleToPeak) } else d.buf
        val (a, aMs) = timed { amp(src, m.drive, m.sag, m.tone, m.cab, R) }
        val (s, cMs) = timed { outputChain(a) }
        return Render(s, aMs, aMs + cMs)
    }

    /** The rack pass at the snip's own rate. */
    fun rackP2(dry: Snip, m: Macros): Render {
        val (out, ms) = timed {
            val a = amp(dry.samples, m.drive, m.sag, m.tone, m.cab, dry.sampleRate)
            peakMatch(a, dry.peak())
            a
        }
        return Render(Snip(out, dry.channels, dry.sampleRate), ms, ms)
    }

    /** The rack pass with internal 4x oversampling. */
    fun rackP3(dry: Snip, m: Macros): Render {
        val up = dry.sampleRate * Dsp.OVERSAMPLE
        val up1 = Resampler.resample(dry, up)
        val (a, aMs) = timed { amp(up1.samples, m.drive, m.sag, m.tone, m.cab, up) }
        val (out, restMs) = timed {
            Tide.bandLimit(a, up)
            val d = Dsp.decimate(a, dry.sampleRate)
            peakMatch(d, dry.peak())
            d
        }
        // The up-sampling is timed too: timed{} above starts after it, so redo it once for its cost.
        val (_, upMs) = timed { Resampler.resample(dry, up) }
        return Render(Snip(out, dry.channels, dry.sampleRate), aMs, aMs + restMs + upMs)
    }
}
