package com.snipsnap.synth

import com.snipsnap.json.Json
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * MIRRORBALL's open hi-hats: BREAKER's dense modal cymbal synth ([BreakerCymbal], accepted by the owner on 2026-11-11:
 * "these are fine"), copied here unchanged in its rendering and pointed at two fitted hats
 * (`scripts/fit_mirrorball_cymbals.py` writes `resources/mirrorball/cymbals.json` with BREAKER's own fit_voice).
 *
 * The owner heard round 1b's open hats (SKIN's noise hats) as short of "a pro sound" and asked for "like real
 * recordings" (2026-11-12). Each hat here is 400 of a CC0 recording's own resonances (Virtuosity Drums, overhead mic):
 * - [Kind.HAT_HALF], the half-open hat: the disco "tss", about 30 dB down by half a second (Philly A04);
 * - [Kind.HAT_34], the three-quarter-open hat: a longer sizzle (Loft A04).
 * Each mode has a frequency, an amplitude and one decay (the steeper of the two the fit measured, BREAKER's "tight"),
 * plus a 60 ms stick burst in third-octave bands. Velocity follows the soft recording's per-band difference; a take
 * re-strikes every mode with a fresh phase, a hair of detune and a level scatter, and a fresh stick.
 *
 * Copied, not shared: BREAKER's object reads only its own resource, and changing it would touch BREAKER's accepted
 * cymbals. The rendering below is BREAKER's line for line; only the resource, the kinds and the seed's name differ.
 */
internal object MirrorballCymbal {
    enum class Kind(val key: String) { HAT_HALF("hat_half"), HAT_34("hat_34") }

    private class Model(val hz: DoubleArray, val amp: DoubleArray, val slope: DoubleArray, val softDb: DoubleArray,
                        val stick: DoubleArray, val stickSoft: DoubleArray)

    private val bands: DoubleArray
    private val models: Map<Kind, Model>

    init {
        val text = checkNotNull(javaClass.getResourceAsStream("/mirrorball/cymbals.json")) { "Missing MIRRORBALL hat fits" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val root = Json.parse(text).obj()
        bands = root.getValue("bands").arr().map { it.num() }.toDoubleArray()
        val voices = root.getValue("voices").obj()
        models = Kind.entries.associateWith { kind ->
            val v = voices.getValue(kind.key).obj()
            val modes = v.getValue("modes").arr().map { m -> m.arr().map { it.num() } }
            Model(
                hz = DoubleArray(modes.size) { modes[it][0] }, amp = DoubleArray(modes.size) { modes[it][1] },
                slope = DoubleArray(modes.size) { modes[it][2] }, softDb = DoubleArray(modes.size) { modes[it][3] },
                stick = v.getValue("stick").arr().map { it.num() }.toDoubleArray(),
                stickSoft = v.getValue("stickSoft").arr().map { it.num() }.toDoubleArray(),
            )
        }
    }

    fun modeCount(kind: Kind) = models.getValue(kind).hz.size

    /** The stick's share of the hit, against the modes (shape, as auditioned). */
    private const val STICK_MIX = .6

    /**
     * [seconds] of [kind] at [velocity], take [take], its modes scaled by [pitch] (a frequency ratio). Peak 0.9, not
     * levelled: [Breaker] levels it with every other hit.
     */
    fun render(kind: Kind, velocity: Float, take: Int, seconds: Float, pitch: Double = 1.0, rate: Int = Dsp.RATE): FloatArray {
        val m = models.getValue(kind)
        val n = (seconds * rate).toInt()
        val soft = ((1.0 - velocity.coerceIn(0f, 1f)) / .8).coerceIn(0.0, 1.0)
        val rng = Random(Dsp.seedFor("MIRRORBALL", "CYMBAL", kind.key, take))
        val out = DoubleArray(n)
        for (k in m.hz.indices) {
            val hz = m.hz[k] * pitch + if (take > 0) rng.nextDouble(-.3, .3) else 0.0
            if (hz >= rate * .49) { rng.nextDouble(); if (take > 0) gaussian(rng); continue }
            var g = m.amp[k] * 10.0.pow(soft * m.softDb[k] / 20)
            if (take > 0) g *= 10.0.pow(.08 * gaussian(rng))
            val phase = rng.nextDouble(0.0, 2 * PI)
            // A decaying phasor, rotated and shrunk each sample: two multiplies per mode per sample.
            val w = 2 * PI * hz / rate
            val r = 10.0.pow(m.slope[k] / 20 / rate)
            val cr = r * cos(w); val sr = r * sin(w)
            var re = g * cos(phase); var im = g * sin(phase)
            for (i in 0 until n) {
                out[i] += im
                val nr = re * cr - im * sr
                im = re * sr + im * cr
                re = nr
            }
        }
        normaliseRms(out, (.3 * rate).toInt())
        val stick = stick(m, soft, rng, n, rate)
        normaliseRms(stick, (.02 * rate).toInt())
        var peak = 0.0
        for (i in 0 until n) { out[i] += STICK_MIX * stick[i]; peak = max(peak, kotlin.math.abs(out[i])) }
        return FloatArray(n) { (out[it] / peak * .9).toFloat() }
    }

    /** The stick: 60 ms of noise in the fitted third-octave bands (two cascaded band-passes each), between the hard and soft recordings' levels. */
    private fun stick(m: Model, soft: Double, rng: Random, n: Int, rate: Int): DoubleArray {
        val len = min(n, (.06 * rate).toInt())
        val out = DoubleArray(n)
        for ((b, fc) in bands.withIndex()) {
            val lo = fc / 2.0.pow(1.0 / 6); val hi = min(fc * 2.0.pow(1.0 / 6), rate * .49)
            if (lo >= rate * .49) continue
            val centre = sqrt(lo * hi); val q = (centre / (hi - lo)).toFloat()
            val f1 = Dsp.Biquad().apply { bandpass(centre.toFloat(), q, rate) }
            val f2 = Dsp.Biquad().apply { bandpass(centre.toFloat(), q, rate) }
            val level = (1 - soft) * m.stick[b] + soft * m.stickSoft[b]
            for (i in 0 until len) out[i] += level * f2.process(f1.process(gaussian(rng).toFloat()))
        }
        for (i in 0 until len) {
            val t = i.toDouble() / rate
            out[i] *= exp(-t / .008) * min(1.0, t / .0004)
        }
        return out
    }

    private fun normaliseRms(x: DoubleArray, frames: Int) {
        var s = 0.0
        val m = min(frames, x.size)
        for (i in 0 until m) s += x[i] * x[i]
        val rms = sqrt(s / max(1, m))
        if (rms > 0) for (i in x.indices) x[i] /= rms
    }

    /** A standard normal draw (Box-Muller): kotlin.random has none. */
    private fun gaussian(rng: Random): Double {
        val u = rng.nextDouble(1e-12, 1.0); val v = rng.nextDouble()
        return sqrt(-2 * ln(u)) * cos(2 * PI * v)
    }
}
