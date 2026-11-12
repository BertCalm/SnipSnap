package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * MIRRORBALL: a disco drum printed through an FET compressor, a multi-head tape echo, then an amp.
 *
 * The characteristic gesture is **dead, clicky strike → FET squeeze → tape repeats in rhythm → the amp pulls
 * the repeats up behind the hit**.
 *
 * This is a treatment stage for the MIRRORBALL kits ([MirrorballKits]), not a Patch engine, in the way
 * [Gatecrash] is for its kits. It takes one dry mono hit (or a dry phrase) at [Dsp.RATE] and returns it
 * printed through the owner's chain, in the owner's order:
 *
 * 1. **PRESS**, an FET limiting amplifier. INPUT drives into a fixed threshold, so a softer hit is squeezed
 *    less: the velocity character comes from the circuit, not from a law. Attack 20 µs to 800 µs, release
 *    50 ms to 1.1 s with a slow second stage after a long squeeze (program dependence). At fast settings the
 *    gain follows a kick's waveform, which is distortion; nothing adds it, it falls out of the detector. The
 *    gain cell bends a little more the harder it works, and [Ratio.ALL] ("all buttons in") pins the ratio,
 *    widens the knee, overshoots on each attack and doubles that bend. Runs at 4x.
 * 2. **REEL**, a tape echo with three playback heads on one moving tape, at one, three and four sixteenths
 *    (the reference's tap subdivisions: 1/16, dotted 1/8, 1/4). One or two heads read at once, so the
 *    repeats are a rhythm. Wow, flutter and a seeded drift move every head together. Each pass goes back
 *    through the record head (a tanh: the bias), loses top (tape age) and bottom (the low-end contour). The
 *    loop gain is at most [MAX_LOOP_GAIN], so the loop cannot run away and no limiter is needed.
 * 3. **COMBO**, a small clean tube amp. The asymmetric tube curve ([Valve]'s), a mid scoop and a bright
 *    shelf, supply sag, a soft power stage, then an open-backed speaker. Runs at 4x. Because it comes last,
 *    the echoes go through it too: the sag squeezes hit and repeats together, so the repeats come up behind
 *    the hit. The FX rack cannot make this order, because [FxChain] runs VALVE before TAPE and ECHO.
 *
 * Ported from the round-0 prototype (`scripts/mirrorball_r0.py`); every constant is a shape value from that
 * round, authored unheard. The design, the references and the measurements are in
 * `docs/superpowers/specs/2026-11-12-mirrorball-disco-kit-design.md`.
 */
object Mirrorball {

    /** Bump when a change alters rendered audio: saved kits name the version they were printed with. */
    const val VERSION = 1

    /** The most the tape loop may return of itself per pass, whatever is set. */
    const val MAX_LOOP_GAIN = .92

    /** Head positions on the tape, in sixteenth notes from the record head. */
    val HEAD_SIXTEENTHS = mapOf(1 to 1.0, 2 to 3.0, 3 to 4.0)

    /** The ratio buttons. [ALL] is a law, not a circuit: ratio 20, a wide knee, an overshoot and twice the bend. */
    enum class Ratio(val ratio: Double, val kneeDb: Double, val bend: Double, val overshootDb: Double) {
        FOUR(4.0, 4.0, .05, 0.0), EIGHT(8.0, 4.0, .05, 0.0), TWELVE(12.0, 4.0, .05, 0.0),
        TWENTY(20.0, 4.0, .05, 0.0), ALL(20.0, 12.0, .10, 3.0),
    }

    /** PRESS: [inputDb] of drive into the fixed threshold; [attack] and [release] 0 slowest to 1 fastest. */
    data class Press(val inputDb: Float = 12f, val attack: Float = .6f, val release: Float = .6f, val ratio: Ratio = Ratio.FOUR) {
        init {
            require(inputDb.isFinite() && inputDb in 0f..30f) { "input $inputDb" }
            require(attack.isFinite() && attack in 0f..1f && release.isFinite() && release in 0f..1f)
        }
    }

    /** REEL's send for one pad: which heads read (none, one or two of 1..3), how much is sent, how long it repeats. */
    data class Reel(val heads: List<Int> = emptyList(), val send: Float = 0f, val repeats: Float = 0f) {
        init {
            require(heads.size <= 2 && heads.all { it in HEAD_SIXTEENTHS } && heads.toSet().size == heads.size) { "heads $heads" }
            require(send.isFinite() && send in 0f..1f && repeats.isFinite() && repeats in 0f..1f)
        }
        val on: Boolean get() = heads.isNotEmpty() && send > 0f
    }

    /** The tape itself, shared by a kit: [age] darkens the loop, [wow] moves the transport, [drive] is the record bias. */
    data class Tape(val age: Float = .45f, val wow: Float = .5f, val drive: Float = .4f, val contourHz: Float = 180f) {
        init {
            require(age.isFinite() && age in 0f..1f && wow.isFinite() && wow in 0f..1f && drive.isFinite() && drive in 0f..1f)
            require(contourHz.isFinite() && contourHz in 20f..600f)
        }
    }

    /** COMBO: preamp gain (1.6 clean, 5 pushed), how far the supply gives way, and where the speaker rolls off. */
    data class Combo(val drive: Float = 1.6f, val sag: Float = .35f, val cabHz: Float = 5_200f) {
        init {
            require(drive.isFinite() && drive in .5f..12f && sag.isFinite() && sag in 0f..1f)
            require(cabHz.isFinite() && cabHz in 2_000f..14_000f)
        }
    }

    /** One pad's whole chain. */
    data class Setting(val press: Press, val reel: Reel, val tape: Tape, val combo: Combo)

    /** The owner's order, or the FX rack's (amp before echo), kept for the audition's comparison. */
    enum class Order { OWNER, RACK }

    /** Every stage of one print, for the tests that measure the gesture. */
    class Stages(val dry: FloatArray, val pressed: FloatArray, val out: FloatArray)

    /**
     * Prints [dry] through [s] at [bpm]. [length] is the most frames the print may run (the echo's tail lands
     * inside it); the result is at least as long as [dry]. [seed] seeds the tape's drift.
     */
    fun process(dry: FloatArray, s: Setting, bpm: Int, seed: Int, length: Int = dry.size, order: Order = Order.OWNER): Stages {
        require(bpm in 60..200) { "bpm $bpm" }
        require(dry.all { it.isFinite() })
        val n = max(dry.size, length)
        val pressed = press(dry, s.press)
        val out = when (order) {
            Order.OWNER -> combo(reel(pressed, s.reel, s.tape, bpm, seed, n), s.combo)
            Order.RACK -> reel(combo(pressed, s.combo), s.reel, s.tape, bpm, seed, n)
        }
        return Stages(dry, pressed, out)
    }

    // ------------------------------------------------------------------ PRESS

    /** The fixed threshold, dBFS: a full-strength hit at 0 dB INPUT just reaches it. */
    private const val THRESHOLD_DB = -6.0

    fun attackSeconds(attack: Float): Double = 800e-6 * (20e-6 / 800e-6).pow(attack.toDouble())
    fun releaseSeconds(release: Float): Double = 1.1 * (0.05 / 1.1).pow(release.toDouble())

    /** The static curve's gain reduction, dB, at a level [overDb] above the threshold. */
    private fun reduction(overDb: Double, r: Ratio): Double {
        val k = r.kneeDb
        return when {
            2 * overDb < -k -> 0.0
            2 * abs(overDb) <= k -> (1 - 1 / r.ratio) * (overDb + k / 2).pow(2) / (2 * k)
            else -> (1 - 1 / r.ratio) * overDb
        }
    }

    internal fun press(x: FloatArray, p: Press): FloatArray {
        if (x.isEmpty()) return x.copyOf()
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val gIn = 10.0.pow(p.inputDb / 20.0)
        val u = Valve.upsample(x, Dsp.RATE)
        val r = p.ratio
        val aAtt = exp(-1 / (attackSeconds(p.attack) * rate))
        val relS = releaseSeconds(p.release)
        val aFast = exp(-1 / (relS * rate))
        val aSlow = exp(-1 / (relS * 5 * rate))
        val aBusy = exp(-1 / (.15 * rate))
        // Make-up: a full-scale level held at this INPUT comes out at full scale. A softer hit is squeezed less
        // and so comes out relatively louder against its own peak, which is what a compressor does to a touch.
        val makeup = 10.0.pow((reduction(p.inputDb.toDouble() - THRESHOLD_DB, r) - p.inputDb) / 20.0)
        var gr = 0.0
        var busy = 0.0
        var prevTarget = 0.0
        val out = FloatArray(u.size)
        for (i in u.indices) {
            val v = u[i] * gIn
            val target = reduction(20 * log10(abs(v) + 1e-12) - THRESHOLD_DB, r)
            if (target > gr) {
                gr = aAtt * gr + (1 - aAtt) * target
                // ALL: the squeeze overshoots on a fresh attack, then the detector catches up.
                if (r.overshootDb > 0 && target > prevTarget + .5) gr = min(gr * 10.0.pow(r.overshootDb / 20), target + r.overshootDb)
            } else {
                val a = aFast * (1 - busy) + aSlow * busy
                gr = a * gr + (1 - a) * target
            }
            prevTarget = target
            busy = aBusy * busy + (1 - aBusy) * (if (gr > 3) 1.0 else 0.0)
            val y = v * 10.0.pow(-gr / 20) * makeup
            // The FET's own bend, an even-and-odd curve that grows with how hard the cell is working.
            val work = min(gr / 20, 1.0)
            val bent = y + r.bend * work * (.5 * y * y - .3 * y * y * y)
            // The output transformer and class-A line amp: barely there, it rounds the very top.
            out[i] = (tanh(bent * 1.2) / 1.2).toFloat()
        }
        return down(out, x.size)
    }

    // ------------------------------------------------------------------ REEL

    /** Wow and flutter, in seconds of delay modulation at WOW 1. */
    private const val WOW_HZ = .7
    private const val WOW_S = .0018
    private const val FLUTTER_HZ = 6.5
    private const val FLUTTER_S = .00025
    private const val DRIFT_S = .0006

    fun sixteenthFrames(bpm: Int): Double = Dsp.RATE * 60.0 / bpm / 4

    /** The loop's top end: 7 kHz on new tape down to 1.8 kHz on worn. */
    fun ageHz(age: Float): Double = 7000.0 * (1800.0 / 7000.0).pow(age.toDouble())

    internal fun reel(x: FloatArray, r: Reel, t: Tape, bpm: Int, seed: Int, length: Int): FloatArray {
        val n = max(x.size, length)
        val out = x.copyOf(n)
        if (!r.on) return out
        val delays = r.heads.map { HEAD_SIXTEENTHS.getValue(it) * sixteenthFrames(bpm) }
        val buf = DoubleArray(n + 2)
        val noise = Dsp.Noise(seed)
        val ph1 = 2 * PI * (noise.next() + 1) / 2
        val ph2 = 2 * PI * (noise.next() + 1) / 2
        // The drift: noise smoothed to a slow wander, then scaled to its own peak so WOW means the same depth
        // for every seed.
        val drift = DoubleArray(n)
        var d = 0.0
        val aDrift = exp(-2 * PI * 1.5 / Dsp.RATE)
        for (i in 0 until n) { d = aDrift * d + (1 - aDrift) * noise.next(); drift[i] = d }
        val dPeak = drift.maxOf { abs(it) }.coerceAtLeast(1e-9)
        val w = t.wow.toDouble()
        val aLp = exp(-2 * PI * ageHz(t.age) / Dsp.RATE)
        val aHp = exp(-2 * PI * t.contourHz / Dsp.RATE)
        val g = r.repeats * MAX_LOOP_GAIN / r.heads.size
        val k = 1 + 4 * t.drive
        var lp = 0.0
        var hpX = 0.0
        var hpY = 0.0
        var wetLp = 0.0
        // Once the input has ended and nothing audible is left on the tape for longer than the longest head,
        // nothing more can come back: the print stops there, so the amp is not run over silence.
        val reach = delays.max().toInt() + 64
        val silent = max(x.maxOfOrNull { abs(it) } ?: 0f, 1e-9f) * SILENT
        var quiet = 0
        var end = n
        for (i in 0 until n) {
            val ti = i.toDouble() / Dsp.RATE
            val mod = w * Dsp.RATE * (WOW_S * sin(2 * PI * WOW_HZ * ti + ph1) + FLUTTER_S * sin(2 * PI * FLUTTER_HZ * ti + ph2) +
                DRIFT_S * drift[i] / dPeak)
            var s = 0.0
            for (dl in delays) {
                val pos = i - dl - mod
                val j = floor(pos).toInt()
                if (j >= 0 && j + 1 < buf.size) { val f = pos - j; s += buf[j] * (1 - f) + buf[j + 1] * f }
            }
            // What comes off the heads has been through the record head and the worn tape once.
            wetLp = (1 - aLp) * s + aLp * wetLp
            out[i] += (r.send * wetLp).toFloat()
            // The loop: worn top, contoured bottom, back to the record head, which saturates every pass.
            lp = (1 - aLp) * s + aLp * lp
            hpY = aHp * (hpY + lp - hpX); hpX = lp
            val rec = (if (i < x.size) x[i].toDouble() else 0.0) + g * hpY
            buf[i] = tanh(k * rec) / k
            quiet = if (abs(buf[i]) < silent && abs(r.send * wetLp) < silent) quiet + 1 else 0
            if (i >= x.size && quiet > reach) { end = i + 1; break }
        }
        return if (end < n) out.copyOf(end) else out
    }

    /** Under this, against the hit's own peak, the tape holds nothing audible: 90 dB down (the kits cut at 80). */
    private const val SILENT = 3e-5

    // ------------------------------------------------------------------ COMBO

    private fun curve(b: Double): Double = if (b >= 0) tanh(b) else b / sqrt(1 + b * b)

    internal fun combo(x: FloatArray, c: Combo): FloatArray {
        if (x.isEmpty()) return x.copyOf()
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val u = Valve.upsample(x, Dsp.RATE)
        val scoop = Biquad.peak(550.0, .6, -5.0, rate)
        val shelf = Biquad.highShelf(2800.0, .7, 2.5, rate)
        val aUp = exp(-1 / (.004 * rate))
        val aDn = exp(-1 / (.12 * rate))
        var env = 0.0
        val out = FloatArray(u.size)
        for (i in u.indices) {
            val pre = shelf.process(scoop.process(curve(u[i] * c.drive.toDouble())))
            val sq = pre * pre
            val a = if (sq > env) aUp else aDn
            env = a * env + (1 - a) * sq
            val g = 1 / (1 + c.sag * 3 * sqrt(env))
            out[i] = (tanh(1.4 * pre * g) / 1.4).toFloat()
        }
        val y = down(out, x.size)
        // The speaker, at the output rate: linear, nothing to fold.
        val hp = Biquad.highpass(75.0, .8, Dsp.RATE)
        val bump = Biquad.peak(110.0, 1.4, 3.0, Dsp.RATE)
        val lp1 = Biquad.lowpass(c.cabHz.toDouble(), .8, Dsp.RATE)
        val lp2 = Biquad.lowpass(max(9000.0, c.cabHz * 1.6), .6, Dsp.RATE)
        for (i in y.indices) y[i] = lp2.process(lp1.process(bump.process(hp.process(y[i].toDouble())))).toFloat()
        return y
    }

    /** Band-limit at 4x and decimate, as the house output stage and [Valve] do; trimmed to [frames]. */
    private fun down(up: FloatArray, frames: Int): FloatArray {
        Tide.bandLimit(up, Dsp.RATE * Dsp.OVERSAMPLE)
        val d = Dsp.decimate(up, Dsp.RATE)
        return d.copyOf(frames)
    }

    /** A second-order section, RBJ's cookbook, in double precision. */
    private class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }

        companion object {
            private fun of(b: DoubleArray, a: DoubleArray) = Biquad(b[0] / a[0], b[1] / a[0], b[2] / a[0], a[1] / a[0], a[2] / a[0])

            fun lowpass(f0: Double, q: Double, rate: Int): Biquad {
                val w = 2 * PI * min(f0, rate * .45) / rate; val c = cos(w); val al = sin(w) / (2 * q)
                return of(doubleArrayOf((1 - c) / 2, 1 - c, (1 - c) / 2), doubleArrayOf(1 + al, -2 * c, 1 - al))
            }

            fun highpass(f0: Double, q: Double, rate: Int): Biquad {
                val w = 2 * PI * f0 / rate; val c = cos(w); val al = sin(w) / (2 * q)
                return of(doubleArrayOf((1 + c) / 2, -(1 + c), (1 + c) / 2), doubleArrayOf(1 + al, -2 * c, 1 - al))
            }

            fun peak(f0: Double, q: Double, gainDb: Double, rate: Int): Biquad {
                val w = 2 * PI * f0 / rate; val c = cos(w); val al = sin(w) / (2 * q); val a = 10.0.pow(gainDb / 40)
                return of(doubleArrayOf(1 + al * a, -2 * c, 1 - al * a), doubleArrayOf(1 + al / a, -2 * c, 1 - al / a))
            }

            fun highShelf(f0: Double, q: Double, gainDb: Double, rate: Int): Biquad {
                val w = 2 * PI * f0 / rate; val c = cos(w); val al = sin(w) / (2 * q); val a = 10.0.pow(gainDb / 40)
                val sq = 2 * sqrt(a) * al
                return of(
                    doubleArrayOf(a * ((a + 1) + (a - 1) * c + sq), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sq)),
                    doubleArrayOf((a + 1) - (a - 1) * c + sq, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sq))
            }
        }
    }
}
