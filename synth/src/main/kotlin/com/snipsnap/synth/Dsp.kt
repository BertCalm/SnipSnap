package com.snipsnap.synth

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/** Small DSP toolbox for offline voice rendering. Nothing here is real-time. */
internal object Dsp {

    const val RATE = 44_100

    /**
     * U6 (docs/SYNTH_UPGRADE.md): an engine's per-voice synthesis runs at
     * `RATE * OVERSAMPLE` internally (see [decimate]) so naive oscillators'
     * own aliasing folds down above audible range instead of into it.
     * [Thump] is the first engine wired to this contract; the rest still
     * render at plain `RATE` until they're migrated the same way.
     */
    const val OVERSAMPLE = 4

    /** Linear macro map: macro 0..1 onto [lo, hi]. */
    fun lin(macro: Float, lo: Float, hi: Float): Float = lo + (hi - lo) * macro.coerceIn(0f, 1f)

    /**
     * Exponential macro map — the right curve for anything the ear judges
     * (frequency, time): equal macro steps sound like equal steps.
     */
    fun expMap(macro: Float, lo: Float, hi: Float): Float =
        (lo * exp(ln((hi / lo).toDouble()) * macro.coerceIn(0f, 1f))).toFloat()

    /**
     * Raises a too-slow beat toward audibility - it does not complete one.
     * Two oscillators a ratio r apart beat at baseHz*(r-1); below this
     * floor a "FAT" macro is a static comb tint rather than movement.
     * [cycles] stays a small fraction on purpose: forcing a *full* beat
     * cycle inside a short note (the original ask) takes far more detune
     * than any macro should grant - tens of cents at typical bass notes -
     * and that reads as an out-of-tune interval, not width. This function
     * only raises the floor; it has no idea what the caller's macro is
     * allowed to ask for, so callers must still clamp the result to their
     * own ceiling (see [Velvet.detuneFor]).
     */
    fun minBeatDetune(baseHz: Float, seconds: Float, cycles: Float = 0.25f): Float {
        if (baseHz <= 0f || seconds <= 0f) return 1f
        return 1f + (cycles / seconds) / baseHz
    }

    /**
     * SCRAMBLE near a seed: perturb each of [seed]'s macros by a gaussian
     * scaled by [temperature], clamped back to 0..1. `temperature = 0`
     * returns [seed] untouched; `temperature = 1` discards it and rolls
     * every macro flat-uniform, exactly the pre-U2 SCRAMBLE — so that
     * behaviour stays reachable at the extreme (docs/SYNTH_UPGRADE.md, U2).
     */
    fun scrambleNear(seed: Map<String, Float>, temperature: Float, random: Random): Map<String, Float> {
        val t = temperature.coerceIn(0f, 1f)
        return when {
            t <= 0f -> seed
            t >= 1f -> seed.mapValues { random.nextFloat() }
            else -> seed.mapValues { (_, v) -> (v + gaussian(random) * t).coerceIn(0f, 1f) }
        }
    }

    /**
     * A stable seed for a render. Derived from the patch's own identity, so
     * two different voices decorrelate while one voice stays reproducible —
     * golden files and `--undo` byte-identity need the second half.
     */
    fun seedFor(vararg parts: Any): Int {
        var h = 17
        for (p in parts) h = h * 31 + p.toString().hashCode()
        return h
    }

    /**
     * [count] start phases in [0, 1), spread from [seed]. Every oscillator in
     * every engine used to start at exactly 0.0, so detuned pairs began locked
     * and each attack was the same coherent transient.
     */
    fun phases(count: Int, seed: Int): DoubleArray {
        val random = Random(seed)
        return DoubleArray(count) { random.nextDouble() }
    }

    /** Standard-normal sample via Box-Muller; `kotlin.random.Random` has no `nextGaussian()`. */
    private fun gaussian(random: Random): Float {
        val u1 = 1f - random.nextFloat() // (0, 1], never 0, so ln() stays finite
        val u2 = random.nextFloat()
        return sqrt(-2f * ln(u1)) * cos(2f * PI.toFloat() * u2)
    }

    /** Deterministic noise; same seed, same grains, stable tests. */
    class Noise(seed: Int) {
        private var state = if (seed == 0) 1 else seed
        fun next(): Float {
            state = (state * 1103515245 + 12345) and 0x7fffffff
            return (state.toFloat() / 0x3fffffff) - 1f
        }
    }

    /** Naive square — aliasing and all. Lo-fi is on-brand, and these are percussive. */
    fun square(phase: Double): Float = if (sin(2.0 * PI * phase) >= 0.0) 1f else -1f

    /**
     * Chamberlin state-variable filter. One instance per voice per pass;
     * process() advances one sample and exposes all three outputs.
     */
    class Svf(private val rate: Int = RATE) {
        var low = 0f; var band = 0f; var high = 0f
        fun process(input: Float, freqHz: Float, damp: Float) {
            val f = (2.0 * sin(PI * min(freqHz, rate * 0.22f) / rate)).toFloat()
            low += f * band
            high = input - low - damp * band
            band += f * high
        }
        fun reset() { low = 0f; band = 0f; high = 0f }
    }

    /**
     * Topology-preserving (trapezoidal-integrated) state-variable filter,
     * after Andy Simper's Cytomic papers — the "linear trap" design the
     * modern open synths use.
     *
     * The Chamberlin [Svf] above goes unstable as its frequency coefficient
     * nears 1 (≈7 kHz here), which is why VELVET's cutoff used to be capped
     * at 5.2 kHz. This one is stable to Nyquist and clean under fast
     * modulation, so filters can finally open all the way. [k] is damping:
     * 2 = no resonance, small = ringing (keep ≥ ~0.1).
     */
    class TptSvf(private val rate: Int = RATE) {
        var low = 0f; var band = 0f; var high = 0f
        private var ic1 = 0f
        private var ic2 = 0f
        /**
         * [saturate] self-limits the feedback state instead of letting it
         * ring cleanly into the final normalise (docs/SYNTH_UPGRADE.md, U5)
         * - plain tanh, near-identity below ~0.5, only compressing as a
         * state genuinely runs toward and past unity (self-oscillation
         * territory). Defaults to off: every existing caller keeps today's
         * exact linear filter. It's opt-in per call, not a blanket switch,
         * because it isn't free even at typical settings - FATHOM's states
         * run hot enough even at its fixed, moderate damping that turning
         * it on there shifted DEEP's own factory default off KICK entirely
         * and drifted several GRIND presets toward TOM. Enable it only for
         * a filter whose caller has actually checked its own tests stay
         * green with it on.
         */
        fun process(input: Float, freqHz: Float, k: Float, saturate: Boolean = false) {
            val g = kotlin.math.tan(PI * (freqHz.coerceIn(10f, rate * 0.49f)) / rate).toFloat()
            val kk = k.coerceAtLeast(0.1f)
            val a1 = 1f / (1f + g * (g + kk))
            val a2 = g * a1
            val a3 = g * a2
            val v3 = input - ic2
            val v1 = a1 * ic1 + a2 * v3
            val v2 = ic2 + a2 * ic1 + a3 * v3
            val nextIc1 = 2f * v1 - ic1
            val nextIc2 = 2f * v2 - ic2
            ic1 = if (saturate) tanh(nextIc1.toDouble()).toFloat() else nextIc1
            ic2 = if (saturate) tanh(nextIc2.toDouble()).toFloat() else nextIc2
            low = v2
            band = v1
            high = input - kk * v1 - v2
        }
        fun reset() { ic1 = 0f; ic2 = 0f; low = 0f; band = 0f; high = 0f }
    }

    /**
     * Biquad section with coefficients from the RBJ Audio EQ Cookbook —
     * the community-standard shelf and bell formulas (public-domain math,
     * indexed by every DSP resource list worth reading). Direct form I.
     */
    class Biquad {
        private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
        private var a1 = 0f; private var a2 = 0f
        private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }

        private fun set(b0: Float, b1: Float, b2: Float, a0: Float, a1: Float, a2: Float) {
            this.b0 = b0 / a0; this.b1 = b1 / a0; this.b2 = b2 / a0
            this.a1 = a1 / a0; this.a2 = a2 / a0
        }

        fun lowShelf(f0: Float, gainDb: Float, rate: Int = RATE) {
            val a = Math.pow(10.0, gainDb / 40.0).toFloat()
            val w0 = (2.0 * PI * f0 / rate)
            val cw = kotlin.math.cos(w0).toFloat()
            val sw = kotlin.math.sin(w0).toFloat()
            val alpha = sw / 2f * kotlin.math.sqrt(2f) // shelf slope S = 1
            val sqA = kotlin.math.sqrt(a)
            set(
                a * ((a + 1) - (a - 1) * cw + 2 * sqA * alpha),
                2 * a * ((a - 1) - (a + 1) * cw),
                a * ((a + 1) - (a - 1) * cw - 2 * sqA * alpha),
                (a + 1) + (a - 1) * cw + 2 * sqA * alpha,
                -2 * ((a - 1) + (a + 1) * cw),
                (a + 1) + (a - 1) * cw - 2 * sqA * alpha,
            )
        }

        fun highShelf(f0: Float, gainDb: Float, rate: Int = RATE) {
            val a = Math.pow(10.0, gainDb / 40.0).toFloat()
            val w0 = (2.0 * PI * f0 / rate)
            val cw = kotlin.math.cos(w0).toFloat()
            val sw = kotlin.math.sin(w0).toFloat()
            val alpha = sw / 2f * kotlin.math.sqrt(2f)
            val sqA = kotlin.math.sqrt(a)
            set(
                a * ((a + 1) + (a - 1) * cw + 2 * sqA * alpha),
                -2 * a * ((a - 1) + (a + 1) * cw),
                a * ((a + 1) + (a - 1) * cw - 2 * sqA * alpha),
                (a + 1) - (a - 1) * cw + 2 * sqA * alpha,
                2 * ((a - 1) - (a + 1) * cw),
                (a + 1) - (a - 1) * cw - 2 * sqA * alpha,
            )
        }

        /** Constant-peak-gain bandpass — the RBJ cookbook's formant workhorse. */
        fun bandpass(f0: Float, q: Float, rate: Int = RATE) {
            val w0 = (2.0 * PI * f0 / rate)
            val cw = kotlin.math.cos(w0).toFloat()
            val sw = kotlin.math.sin(w0).toFloat()
            val alpha = sw / (2f * q)
            set(alpha, 0f, -alpha, 1 + alpha, -2 * cw, 1 - alpha)
        }

        /**
         * An allpass: every magnitude passes through untouched, the phase
         * rotated through 180 degrees around [f0]. The RBJ cookbook's
         * form — the numerator is the denominator reversed, which is what
         * makes the magnitude flat at every frequency.
         */
        fun allpass(f0: Float, q: Float, rate: Int = RATE) {
            val w0 = (2.0 * PI * f0 / rate)
            val cw = kotlin.math.cos(w0).toFloat()
            val sw = kotlin.math.sin(w0).toFloat()
            val alpha = sw / (2f * q)
            set(1 - alpha, -2 * cw, 1 + alpha, 1 + alpha, -2 * cw, 1 - alpha)
        }

        fun peaking(f0: Float, gainDb: Float, q: Float, rate: Int = RATE) {
            val a = Math.pow(10.0, gainDb / 40.0).toFloat()
            val w0 = (2.0 * PI * f0 / rate)
            val cw = kotlin.math.cos(w0).toFloat()
            val sw = kotlin.math.sin(w0).toFloat()
            val alpha = sw / (2f * q)
            set(
                1 + alpha * a,
                -2 * cw,
                1 - alpha * a,
                1 + alpha / a,
                -2 * cw,
                1 - alpha / a,
            )
        }
    }

    /** One-pole low-pass; subtract from input for a high-pass. */
    class OnePole(private val rate: Int = RATE) {
        private var state = 0f
        fun lp(input: Float, cutoffHz: Float): Float {
            val a = (1.0 - exp(-2.0 * PI * min(cutoffHz, rate * 0.45f) / rate)).toFloat()
            state += a * (input - state)
            return state
        }
    }

    /** Soft saturation with unity make-up so DRIVE changes tone, not loudness. */
    fun drive(x: Float, amount: Float): Float {
        if (amount <= 0f) return x
        val g = 1f + 6f * amount
        return (tanh((x * g).toDouble()) / tanh(g.toDouble())).toFloat()
    }

    /** Exponential decay envelope reaching -60 dB at [t60] seconds. */
    fun envAt(t: Float, t60: Float): Float = exp((-6.9078 * t / t60).toDouble()).toFloat()

    /**
     * Shared envelope primitive (docs/SYNTH_UPGRADE.md, U5) — a linear
     * attack ramp times a decay curve, both measured from t=0 the way every
     * engine's own hand-rolled `attack * envAt(t, t60)` already worked, so
     * adopting this changes nothing on its own. The decay can be a single
     * [envAt] stage (the default: [decay1Seconds] = 0, decay starts at full
     * level under the ramp) or two-stage — a fast linear drop to
     * [decay1Level] over [decay1Seconds] (the "thwack"), then an [envAt]
     * tail from there at [decay2T60] (the "body") — plus an optional
     * [holdSeconds] at full level between the ramp and the decay, for a
     * sustaining/gated voice.
     */
    class Env(
        private val attackSeconds: Float,
        private val decay2T60: Float,
        private val holdSeconds: Float = 0f,
        private val decay1Seconds: Float = 0f,
        private val decay1Level: Float = 1f,
    ) {
        fun at(t: Float): Float {
            val attack = if (attackSeconds <= 0f) 1f else (t / attackSeconds).coerceAtMost(1f)
            return attack * decayAt(t)
        }

        private fun decayAt(t: Float): Float {
            if (t <= holdSeconds) return 1f
            val afterHold = t - holdSeconds
            if (decay1Seconds <= 0f) return Dsp.envAt(afterHold, decay2T60)
            if (afterHold < decay1Seconds) return Dsp.lin(afterHold / decay1Seconds, 1f, decay1Level)
            return decay1Level * Dsp.envAt(afterHold - decay1Seconds, decay2T60)
        }
    }

    /** Peak-normalize in place to [target]; silence is left alone. */
    fun normalize(buf: FloatArray, target: Float = 0.95f) {
        var peak = 0f
        for (v in buf) { val a = if (v < 0) -v else v; if (a > peak) peak = a }
        if (peak <= 1e-9f) return
        val g = target / peak
        for (i in buf.indices) buf[i] *= g
    }

    /**
     * Scales down only if [buf]'s peak exceeds [ceiling] - a clipping safety
     * net, not a level target (unlike [normalize], which always rescales).
     * A stage like [Punch] that deliberately sets its own final level (U3,
     * docs/SYNTH_UPGRADE.md - "swap the peak target for a perceived-level
     * target") needs exactly this after it: something that only intervenes
     * when the shaping pushed a sample past what's safe, rather than
     * unconditionally overwriting the level that stage just chose.
     */
    fun limitPeak(buf: FloatArray, ceiling: Float = 1f) {
        var peak = 0f
        for (v in buf) { val a = if (v < 0) -v else v; if (a > peak) peak = a }
        if (peak <= ceiling || peak <= 1e-9f) return
        val g = ceiling / peak
        for (i in buf.indices) buf[i] *= g
    }

    /**
     * Decimates [buf] (rendered at `rate * OVERSAMPLE`) back down to [rate],
     * via [Resampler] - the same windowed-sinc kernel already used for
     * device-rate conversion at bake time. Two cascaded 2x steps, not one
     * 4x step: `Resampler`'s own doc comment scopes its well-rejected range
     * to roughly 0.5x-2x of unity, and a straight 4:1 pass measured only
     * -10.9 dB rejection just above the new Nyquist versus -17.9 dB
     * cascaded (25 kHz probe, 176.4 kHz source) - each stage this way stays
     * inside the kernel's own documented comfort zone.
     */
    fun decimate(buf: FloatArray, rate: Int): FloatArray {
        val oversampled = Snip(buf, channels = 1, sampleRate = rate * OVERSAMPLE)
        val half = Resampler.resample(oversampled, rate * OVERSAMPLE / 2)
        return Resampler.resample(half, rate).samples
    }

    /** Short linear fade-out so a truncated tail never clicks. */
    fun fadeTail(buf: FloatArray, ms: Float = 4f) {
        val n = min(buf.size, (ms / 1000f * RATE).toInt())
        for (i in 0 until n) {
            buf[buf.size - 1 - i] *= i.toFloat() / n
        }
    }

    /**
     * A variable-speed head: for each output frame, [headAt] says where to
     * read (fractional) and how loud. Linearly interpolated between
     * neighbours; a position at or past the last source frame, or a zero
     * gain, leaves that output frame silent — a stop that outruns its
     * material simply runs out.
     *
     * The rack's two varispeed stages read through here — MOTION's capstan,
     * where the position is the integral of a speed ramp, and SPEED's pitch,
     * where it is a straight line — so they cannot drift apart in quality.
     */
    inline fun readAt(snip: Snip, outFrames: Int, headAt: (Int) -> Pair<Double, Float>): Snip {
        val ch = snip.channels
        val src = snip.samples
        val last = snip.frameCount - 1
        val out = FloatArray(outFrames * ch)
        for (k in 0 until outFrames) {
            val (pos, gain) = headAt(k)
            if (pos >= last || gain <= 0f) continue
            val i = pos.toInt()
            val frac = (pos - i).toFloat()
            for (c in 0 until ch) {
                val a = src[i * ch + c]
                val b = src[(i + 1) * ch + c]
                out[k * ch + c] = (a + (b - a) * frac) * gain
            }
        }
        return Snip(out, ch, snip.sampleRate)
    }
}
