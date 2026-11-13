package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * BREAKER's processing after the source (see [Breaker]): saturation, the crush, the spring tank and
 * the slap. Ported from the round-0 prototype the owner heard (2026-11-09); every constant is a
 * shape from that round. Mono, at the output rate except the saturation, which runs at 4x.
 */
internal object BreakerChain {

    /** The tube: tanh above zero, a softer algebraic curve below, so it adds even harmonics (VALVE's curve). */
    private fun curve(b: Double): Double = if (b >= 0) tanh(b) else b / sqrt(1 + b * b)

    private fun peak(x: DoubleArray): Double { var p = 0.0; for (s in x) p = maxOf(p, abs(s)); return p }

    private fun onePoleLp(x: DoubleArray, hz: Double, rate: Int): DoubleArray {
        val a = 1 - exp(-2 * PI * minOf(hz, rate * .45) / rate)
        var s = 0.0
        return DoubleArray(x.size) { s += a * (x[it] - s); s }
    }

    /** A second-order section, RBJ's cookbook (the bilinear transform pre-warped at f0), in double precision. */
    private class Section(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }

        companion object {
            fun lowpass(f0: Double, q: Double, rate: Int): Section {
                val w = 2 * PI * f0 / rate; val c = cos(w); val al = sin(w) / (2 * q); val a0 = 1 + al
                return Section((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
            }

            fun highpass(f0: Double, q: Double, rate: Int): Section {
                val w = 2 * PI * f0 / rate; val c = cos(w); val al = sin(w) / (2 * q); val a0 = 1 + al
                return Section((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
            }
        }
    }

    private const val BUTTERWORTH_Q = 0.7071067811865476

    // ------------------------------------------------------------------ saturation

    /** The processor's saturation (shape): +6 dB into the tube on the hit normalised to peak 1. */
    private const val SAT_DB = 6.0

    /** The tube at 4x, band-limited back down, its DC blocked at 5 Hz as VALVE's is, peak-matched. */
    fun saturate(x: DoubleArray, rate: Int): DoubleArray {
        val p = peak(x)
        if (p <= 0) return x
        val up = Valve.upsample(FloatArray(x.size) { (x[it] / p).toFloat() }, rate)
        val g = 10.0.pow(SAT_DB / 20)
        for (i in up.indices) up[i] = curve(up[i] * g).toFloat()
        Tide.bandLimit(up, rate * Dsp.OVERSAMPLE)
        val down = Dsp.decimate(up, rate)
        val y = DoubleArray(x.size) { down.getOrElse(it) { 0f }.toDouble() }
        val dc = onePoleLp(y, 5.0, rate)
        for (i in y.indices) y[i] -= dc[i]
        val q = peak(y)
        if (q > 0) for (i in y.indices) y[i] *= p / q
        return y
    }

    // ------------------------------------------------------------------ CRUSH

    /**
     * An FET-style compressor (shape). Level-relative: the threshold is in dB under the hit's own peak,
     * -6 dB at [amount] 0 to -30 dB at 1, at 4:1 to 20:1, with a 6 dB soft knee. The gain is smoothed
     * in the log domain, a 0.4 ms attack while it deepens and a 120 ms release while it recovers (the
     * smooth decoupled detector of Giannoulis, Massberg and Reiss). The FET's grit: the held-down
     * signal through the tube curve, harder the deeper it is held. Peak-matched after, so the hit
     * keeps its peak and what lies under it comes up: after the spring, the drip swells back.
     */
    fun crush(x: DoubleArray, amount: Double, rate: Int): DoubleArray {
        if (amount <= 0) return x
        val p = peak(x)
        if (p <= 0) return x
        val thr = -6.0 - 24.0 * amount
        val ratio = 4.0 + 16.0 * amount
        val knee = 6.0
        val aa = exp(-1 / (0.0004 * rate))
        val ar = exp(-1 / (0.12 * rate))
        var s = 0.0
        val y = DoubleArray(x.size)
        for (i in x.indices) {
            val over = 20 * log10(abs(x[i]) / p + 1e-9) - thr
            val gc = when {
                over <= -knee / 2 -> 0.0
                over >= knee / 2 -> -over * (1 - 1 / ratio)
                else -> -(1 - 1 / ratio) * (over + knee / 2).pow(2) / (2 * knee)
            }
            s = if (gc < s) aa * s + (1 - aa) * gc else ar * s + (1 - ar) * gc
            val depth = (-s / 20.0).coerceIn(0.0, 1.0)
            val drive = 1 + 2.0 * depth * amount
            y[i] = curve(x[i] * 10.0.pow(s / 20) / p * drive) / drive * p
        }
        val q = peak(y)
        if (q > 0) for (i in y.indices) y[i] *= p / q
        return y
    }

    // ------------------------------------------------------------------ DRIP: the spring tank

    /** (transit s, pre-delay s, weight): two springs of slightly different lengths, interleaving. */
    internal val SPRINGS = listOf(Triple(.037, 0.0, 1.0), Triple(.043, .004, .8))

    /**
     * A tank's chirp (shape): [fc] is the transition frequency the chirps live under, [k] the
     * allpasses' stretch (round(rate / 2 fc) at 44.1 kHz), [ap] each stage's coefficient (positive,
     * so each stage delays the highs more than the lows) and [stages] how many there are.
     *
     * SOOT's tighter coil reuses this model, so a tank also carries its springs ((transit s,
     * pre-delay s, weight)), its loop decay ([t60Base] + [t60Span] x DRIP seconds), the send's
     * high-pass and the return's tone. The defaults are BREAKER's, written exactly as before, so
     * BREAKER's tanks render byte for byte as they did.
     */
    class Tank(
        val fc: Double, val k: Int, val ap: Double, val stages: Int,
        val springs: List<Triple<Double, Double, Double>> = SPRINGS,
        val t60Base: Double = 1.0, val t60Span: Double = 1.6,
        val sendHp: Double = 250.0, val tone: Double = 3500.0,
    )

    /** Tank A from round 0: a lighter, brighter bend (fc 4.3 kHz). The kick the owner picked rang through it. */
    val TANK_A = Tank(fc = 4300.0, k = 5, ap = 0.6, stages = 60)

    /** Tank B from round 0: the bend lower and deeper, a twang near 3 kHz. The owner's surf spring. */
    val TANK_B = Tank(fc = 3000.0, k = 7, ap = 0.7, stages = 100)


    /**
     * The tank's return for [x]. The send is high-passed at 250 Hz (a driver transformer passes
     * little bass, so a kick does not turn to mud) and driven into the tank by the dwell. In each
     * spring an echo is the last one through the chirp chain and a 4th-order low-pass at the
     * tank's fc again, a transit later and g quieter, so the dispersion grows with every bounce. The
     * chain is allpass and g < 1, so the loop's gain is under 1 at every frequency by construction:
     * no limiter.
     */
    fun spring(x: DoubleArray, drip: Double, frames: Int, rate: Int, tank: Tank = TANK_B, decay: Double = 1.0, sendHp: Double = tank.sendHp): DoubleArray {
        val hp = Section.highpass(sendHp, BUTTERWORTH_Q, rate)
        val send = DoubleArray(frames) { hp.process(x.getOrElse(it) { 0.0 }) }
        val p = peak(send)
        val out = DoubleArray(frames)
        if (p <= 0) return out
        val dwell = 1.0 + 7.0 * drip
        val norm = tanh(dwell)
        for (i in send.indices) send[i] = tanh(dwell * send[i] / p) / norm
        val t60 = (tank.t60Base + tank.t60Span * drip) * decay
        for ((transit, pre, weight) in tank.springs) {
            val lp1 = Section.lowpass(tank.fc, 0.5411961001461970, rate)
            val lp2 = Section.lowpass(tank.fc, 1.3065629648763766, rate)
            val g = 10.0.pow(-3 * transit / t60)
            val length = (transit * rate).roundToInt()
            val delay = DoubleArray(length)
            val preFrames = (pre * rate).roundToInt()
            val k = tank.k
            val ap = tank.ap
            val xs = DoubleArray(tank.stages * k)
            val ys = DoubleArray(tank.stages * k)
            for (t in 0 until frames) {
                val e = delay[t % length]
                var s = send[t] + g * e
                val idx = t % k
                for (st in 0 until tank.stages) {
                    val o = st * k + idx
                    val y = ap * s + xs[o] - ap * ys[o]
                    xs[o] = s; ys[o] = y; s = y
                }
                delay[t % length] = lp2.process(lp1.process(s))
                if (t + preFrames < frames) out[t + preFrames] += weight * e
            }
        }
        return out
    }

    /** The dry hit plus the tank's return, darkened by the tank's tone (BREAKER's: 3.5 kHz), at 0.9 x DRIP of the dry peak. */
    fun tankMix(x: DoubleArray, drip: Double, frames: Int, rate: Int, tank: Tank = TANK_B): DoubleArray {
        val xs = DoubleArray(frames) { x.getOrElse(it) { 0.0 } }
        if (drip <= 0) return xs
        val ret = spring(xs, drip, frames, rate, tank)
        val tone = Section.lowpass(tank.tone, BUTTERWORTH_Q, rate)
        for (i in ret.indices) ret[i] = tone.process(ret[i])
        val pr = peak(ret)
        if (pr <= 0) return xs
        val k = .9 * drip * peak(xs) / pr
        return DoubleArray(frames) { xs[it] + k * ret[it] }
    }

    // ------------------------------------------------------------------ SLAP

    /** The slapback the owner heard at round 0: 95 ms. Model 1 (round 1) used 90 ms. */
    const val SLAP_SECONDS = .095

    /**
     * One echo line whose repeats darken (a 3 kHz one-pole per pass). Its time (shape): a [base]
     * slapback up to SLAP 0.5, then growing to a 375 ms echo at 1. The engine gets no tempo, so this
     * is milliseconds, not a note value. The time is not cosmetic on a kick: the first repeat lands
     * on the body still ringing, and 5 ms decides whether its lows add or partly cancel.
     */
    fun slap(x: DoubleArray, amount: Double, rate: Int, base: Double = SLAP_SECONDS): DoubleArray {
        if (amount <= 0) return x
        val time = if (amount <= .5) base else base + (.375 - base) * (amount - .5) / .5
        val fb = .45 * amount
        var gain = .55 * amount
        val d = (time * rate).toInt()
        val y = x.copyOf()
        var rep = x
        var k = 1
        while (gain > 1e-3 && k * d < x.size) {
            rep = onePoleLp(rep, 3000.0, rate)
            for (i in 0 until x.size - k * d) y[k * d + i] += gain * rep[i]
            gain *= fb
            k++
        }
        return y
    }
}
