package com.snipsnap.synth

import com.snipsnap.json.Json
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * MIRRORBALL's tambourine, synthesized: jingle modes struck by a train of collisions, fitted to BACKWASH's CC0
 * tambourine recordings (`scripts/fit_mirrorball_tambourine.py` writes `resources/mirrorball/tambourine.json`).
 *
 * The owner asked on 2026-11-12 for the tambourine to be recreated with synthesis. A tambourine does not ring as
 * one body: its jingles knock together dozens of times in the few hundred milliseconds after the hand hits the
 * frame, and that rattle is the instrument (the recordings measure 57 to 69 collisions, 3 to 5 ms apart). So:
 *
 * - **The jingles** are 78 resonances measured from the recordings (up to six per third-octave, 1 to 16 kHz),
 *   each a two-pole resonator ringing for the fitted ring time (60 ms).
 * - **The collisions** come about every 4 ms, each striking a random third of the jingles with a random sign, so
 *   no two collisions sound alike. Their level follows the recordings' own 4 to 11 kHz envelope: a hard hit is
 *   loudest at once, a soft one builds for about 50 ms as the jingles get going. Velocity blends the two.
 * - **The hand** on the frame is a 3 ms click at the front.
 * - Every jingle's level was matched to the recordings per third-octave, per layer (the fit's own check: within
 *   1.5 dB from 1 to 16 kHz, measured as MirrorballTest measures, by summed FFT bins).
 *
 * A take re-seeds the collisions ([Dsp.Noise]), so every take is a new strike, not one of two recordings. The
 * recordings themselves are not read here.
 */
internal object MirrorballTambourine {

    private class Layer(val amp: DoubleArray, val envelopeDb: DoubleArray, val click: Double)

    private val hz: DoubleArray
    private val soft: Layer
    private val hard: Layer
    private val ringS: Double
    private val collisionS: Double
    private val hopS: Double
    private val highpassHz: Double

    init {
        val text = checkNotNull(javaClass.getResourceAsStream("/mirrorball/tambourine.json")) { "Missing MIRRORBALL tambourine fit" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val root = Json.parse(text).obj()
        hz = root.getValue("hz").arr().map { it.num() }.toDoubleArray()
        fun layer(name: String) = Layer(
            root.getValue("amp").obj().getValue(name).arr().map { it.num() }.toDoubleArray(),
            root.getValue("envelope").obj().getValue(name).arr().map { it.num() }.toDoubleArray(),
            root.getValue("click").obj().getValue(name).num(),
        )
        soft = layer("soft")
        hard = layer("hard")
        ringS = root.getValue("ringS").num()
        collisionS = root.getValue("collisionMs").num() / 1000
        hopS = root.getValue("hopS").num()
        highpassHz = root.getValue("highpassHz").num()
        require(hz.size == soft.amp.size && hz.size == hard.amp.size && hz.all { it in 500.0..20_000.0 })
    }

    val modeCount: Int get() = hz.size

    /** How far [velocity] sits from the soft recordings (0.3 and under) toward the hard ones (1). */
    fun hardness(velocity: Float): Double = ((velocity.coerceIn(0f, 1f) - .3) / .7).coerceIn(0.0, 1.0)

    /** One hit, [SECONDS] long at [Dsp.RATE], peak 1. Pure: the same velocity and take give the same samples. */
    fun render(velocity: Float, take: Int): FloatArray {
        require(velocity.isFinite() && take >= 0)
        val w = hardness(velocity)
        val rate = Dsp.RATE
        val n = (SECONDS * rate).toInt()
        val noise = Dsp.Noise(Dsp.seedFor("MIRRORBALL", "TAMBOURINE", take))
        fun uniform() = (noise.next() + 1.0) / 2.0
        val env = DoubleArray(soft.envelopeDb.size) { (1 - w) * soft.envelopeDb[it] + w * hard.envelopeDb[it] }
        val amp = DoubleArray(hz.size) { (1 - w) * soft.amp[it] + w * hard.amp[it] }
        // The collisions, as events: when, how loud, and the signed share each jingle takes (0 if not struck).
        val times = ArrayList<Int>()
        val strikes = ArrayList<DoubleArray>()
        var t = 0.0
        val span = env.size * hopS
        while (t < span) {
            val i = (t * rate).toInt()
            if (i >= n) break
            val pos = t / hopS
            val j = min(pos.toInt(), env.size - 2)
            val db = env[j] + (env[j + 1] - env[j]) * (pos - j)
            // A level scatter of about 3 dB (three uniforms make a near-normal spread), as hand and jingles vary.
            val scatter = 10.0.pow((uniform() + uniform() + uniform() - 1.5) * 2.0 * 3.0 / 20)
            val level = 10.0.pow(db / 20) * scatter
            times += i
            strikes += DoubleArray(hz.size) {
                val struck = uniform() < 1.0 / 3
                val sign = if (uniform() < .5) -1.0 else 1.0
                if (struck) sign * level else 0.0
            }
            t += collisionS * (.6 + .8 * uniform())
        }
        val out = DoubleArray(n)
        val r = exp(-6.907755 / (ringS * rate))
        for (k in hz.indices) {
            val wk = 2 * PI * hz[k] / rate
            val a1 = 2 * r * cos(wk)
            val a2 = -r * r
            val g = sin(wk) * amp[k]
            var y1 = 0.0
            var y2 = 0.0
            var next = 0
            for (i in 0 until n) {
                var e = 0.0
                while (next < times.size && times[next] == i) { e += strikes[next][k]; next++ }
                val y = g * e + a1 * y1 + a2 * y2
                y2 = y1; y1 = y
                out[i] += y
            }
        }
        // The hand on the frame: a 3 ms click, high-passed.
        val m = (.003 * rate).toInt()
        val click = (1 - w) * soft.click + w * hard.click
        val hc = Hp(2_500.0, rate)
        for (i in 0 until m) {
            val x = (noise.next().toDouble()) * exp(-i / (.0008 * rate))
            out[i] += click * hc.process(x)
        }
        // The recordings hold almost nothing under 2 kHz; the jingles' skirts and the click leak there, so a
        // fourth-order high-pass (two second-order passes), as the fit renders it.
        val hp1 = Hp(highpassHz, rate)
        val hp2 = Hp(highpassHz, rate)
        var peak = 0.0
        for (i in 0 until n) { out[i] = hp2.process(hp1.process(out[i])); peak = max(peak, abs(out[i])) }
        val k = if (peak > 1e-12) 1.0 / peak else 0.0
        return FloatArray(n) { (out[it] * k).toFloat() }
    }

    /** A second-order Butterworth high-pass (RBJ, Q = 1/sqrt 2), in double precision. */
    private class Hp(f0: Double, rate: Int) {
        private val b0: Double; private val b1: Double; private val b2: Double; private val a1: Double; private val a2: Double
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        init {
            val w = 2 * PI * f0 / rate; val c = cos(w); val al = sin(w) / (2 * (1 / sqrt(2.0))); val a0 = 1 + al
            b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = (1 + c) / 2 / a0; a1 = -2 * c / a0; a2 = (1 - al) / a0
        }
        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    /** How long one hit runs: the recordings' envelopes are 50 dB down by 400 ms. */
    const val SECONDS = .8f
}
