package com.snipsnap.synth

import com.snipsnap.json.Json
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * MIRRORBALL's shakers and hand clap: fresh noise in third-octave bands, each band following the time-frequency
 * envelope of CC0 recordings (`scripts/fit_mirrorball_textures.py` writes `resources/mirrorball/textures.json`).
 *
 * The owner heard round 1b's shakers and clap as short of "a pro sound" and asked for "like real recordings"
 * (2026-11-12). A shaker and a clap are noise instruments: what makes them real is how their spectrum moves, a shaker
 * swelling over 50 to 100 ms as the beads are thrown, a clap a hard crack whose hand-cavity resonance dies in tens of
 * milliseconds. So each voice is the recordings' own envelope (23 third-octaves from 100 Hz to 16 kHz, 2.5 ms
 * steps, averaged over every take) driving fresh Gaussian noise through two cascaded RBJ band-passes per band: the
 * residual half of Serra and Smith's sines-plus-noise model. The fit's own check, four renders against the
 * recordings at 20 ms: the shaker within 0.9 dB median, the wooden shaker 1.2, the clap 1.8 (its 100 to 160 Hz bands,
 * 22 to 31 dB under its peak, miss by 6 to 11 dB: a 100 Hz third-octave filter rings longer than a clap lasts).
 *
 * [Voice.TAMBOURINE] is the rattle under the tambourine's fitted jingles (BACKWASH's recordings, within 1.4 dB median).
 *
 * Velocity: the clap and the tambourine blend their soft and hard recordings' envelopes in dB. The shakers were recorded at one
 * dynamic, so [MirrorballKits] softens them as it does every voice without a strike velocity. A take is a fresh
 * draw of noise, the envelope stretched by up to 3 percent either way and each band moved by about 1 dB, as a
 * second shake or clap never lands quite like the first.
 */
internal object MirrorballTexture {

    enum class Voice(val key: String) { SHAKER("shaker"), SHAKER_WOOD("shaker_wood"), CLAP("clap"), TAMBOURINE("tambourine") }

    private class Model(val soft: Array<DoubleArray>, val hard: Array<DoubleArray>, val gain: DoubleArray)

    private val bands: DoubleArray
    private val hopS: Double
    private val models: Map<Voice, Model>

    init {
        val text = checkNotNull(javaClass.getResourceAsStream("/mirrorball/textures.json")) { "Missing MIRRORBALL texture fits" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val root = Json.parse(text).obj()
        bands = root.getValue("bands").arr().map { it.num() }.toDoubleArray()
        hopS = root.getValue("hopS").num()
        val voices = root.getValue("voices").obj()
        models = Voice.entries.associateWith { v ->
            val o = voices.getValue(v.key).obj()
            val env = o.getValue("env").obj()
            fun layer(name: String) = env.getValue(name).arr().map { row -> row.arr().map { it.num() }.toDoubleArray() }.toTypedArray()
            val hard = layer("hard")
            Model(if ("soft" in env) layer("soft") else hard, hard, o.getValue("gain").arr().map { it.num() }.toDoubleArray())
        }
    }

    /** One hit of [voice] at [velocity], take [take], peak 1. Pure: the same arguments give the same samples. */
    fun render(voice: Voice, velocity: Float, take: Int): FloatArray {
        require(velocity.isFinite() && take >= 0)
        val m = models.getValue(voice)
        val w = ((velocity.coerceIn(0f, 1f) - .3) / .7).coerceIn(0.0, 1.0)
        val rate = Dsp.RATE
        val rng = Random(Dsp.seedFor("MIRRORBALL", "TEXTURE", voice.key, take))
        val stretch = if (take == 0) 1.0 else 1.0 + rng.nextDouble(-.03, .03)
        val frames = m.hard[0].size
        val h = hopS * rate * stretch
        val n = (frames * h).toInt()
        val out = DoubleArray(n)
        for ((b, fc) in bands.withIndex()) {
            val lo = fc / 2.0.pow(1.0 / 6)
            val hi = min(fc * 2.0.pow(1.0 / 6), rate * .49)
            val scatter = if (take == 0) 0.0 else gaussian(rng)
            if (lo >= rate * .49) continue
            val centre = sqrt(lo * hi)
            val f1 = Bandpass(centre, centre / (hi - lo), rate)
            val f2 = Bandpass(centre, centre / (hi - lo), rate)
            val gainDb = m.gain[b] + scatter
            val soft = m.soft[b]
            val hard = m.hard[b]
            for (i in 0 until n) {
                val pos = ((i + .5) / h - .5).coerceIn(0.0, frames - 1.0)
                val j = min(pos.toInt(), frames - 2)
                val f = pos - j
                val s = soft[j] + (soft[j + 1] - soft[j]) * f
                val hd = hard[j] + (hard[j + 1] - hard[j]) * f
                val db = (1 - w) * s + w * hd + gainDb
                out[i] += f2.process(f1.process(gaussian(rng))) * 10.0.pow(db / 20)
            }
        }
        var peak = 0.0
        for (v in out) peak = max(peak, abs(v))
        val k = if (peak > 1e-12) 1.0 / peak else 0.0
        return FloatArray(n) { (out[it] * k).toFloat() }
    }

    /** RBJ's constant-peak band-pass (as [Dsp.Biquad.bandpass]), in double precision like the fit's twin. */
    private class Bandpass(f0: Double, q: Double, rate: Int) {
        private val b0: Double; private val a1: Double; private val a2: Double
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        init {
            val w = 2 * PI * f0 / rate
            val al = sin(w) / (2 * q)
            val a0 = 1 + al
            b0 = al / a0; a1 = -2 * cos(w) / a0; a2 = (1 - al) / a0
        }
        fun process(x: Double): Double {
            val y = b0 * x - b0 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    /** A standard normal draw (Box-Muller), as [BreakerCymbal] draws its own. */
    private fun gaussian(rng: Random): Double {
        val u = rng.nextDouble(1e-12, 1.0)
        val v = rng.nextDouble()
        return sqrt(-2 * ln(u)) * cos(2 * PI * v)
    }
}
