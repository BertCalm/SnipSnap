package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

/** Small DSP toolbox for offline voice rendering. Nothing here is real-time. */
internal object Dsp {

    const val RATE = 44_100

    /** Linear macro map: macro 0..1 onto [lo, hi]. */
    fun lin(macro: Float, lo: Float, hi: Float): Float = lo + (hi - lo) * macro.coerceIn(0f, 1f)

    /**
     * Exponential macro map — the right curve for anything the ear judges
     * (frequency, time): equal macro steps sound like equal steps.
     */
    fun expMap(macro: Float, lo: Float, hi: Float): Float =
        (lo * exp(ln((hi / lo).toDouble()) * macro.coerceIn(0f, 1f))).toFloat()

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
        fun process(input: Float, freqHz: Float, k: Float) {
            val g = kotlin.math.tan(PI * (freqHz.coerceIn(10f, rate * 0.49f)) / rate).toFloat()
            val kk = k.coerceAtLeast(0.1f)
            val a1 = 1f / (1f + g * (g + kk))
            val a2 = g * a1
            val a3 = g * a2
            val v3 = input - ic2
            val v1 = a1 * ic1 + a2 * v3
            val v2 = ic2 + a2 * ic1 + a3 * v3
            ic1 = 2f * v1 - ic1
            ic2 = 2f * v2 - ic2
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

    /** Peak-normalize in place to [target]; silence is left alone. */
    fun normalize(buf: FloatArray, target: Float = 0.95f) {
        var peak = 0f
        for (v in buf) { val a = if (v < 0) -v else v; if (a > peak) peak = a }
        if (peak <= 1e-9f) return
        val g = target / peak
        for (i in buf.indices) buf[i] *= g
    }

    /** Short linear fade-out so a truncated tail never clicks. */
    fun fadeTail(buf: FloatArray, ms: Float = 4f) {
        val n = min(buf.size, (ms / 1000f * RATE).toInt())
        for (i in 0 until n) {
            buf[buf.size - 1 - i] *= i.toFloat() / n
        }
    }
}
