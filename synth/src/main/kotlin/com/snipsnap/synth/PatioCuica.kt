package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/** Cuica-inspired friction gesture, not a measured physical replica. A bounded
 * harmonic slip source follows hand pressure; fixed shell bands keep a woody
 * vowel while pitch bends. Seeded contact roughness prevents a clean synth siren. */
object PatioCuica {
    const val VERSION = 1

    fun render(velocity: Float, hold: Float, take: Int): Snip {
        require(velocity.isFinite() && velocity in 0f..1f)
        require(hold.isFinite() && hold in 0f..1f && take in 0..3)
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val active = .20 + .60 * hold
        val out = FloatArray(((active + .12) * rate).toInt())
        val noise = Dsp.Noise(Dsp.seedFor("PATIO_CUICA", VERSION, take))
        val drift = Dsp.FixedOnePole(17f, rate)
        val rasp = Dsp.Biquad().apply { bandpass(1800f, .8f, rate) }
        val body = Dsp.Biquad().apply { bandpass(720f, 1.2f, rate) }
        val mouth = Dsp.Biquad().apply { bandpass(1280f, 1.6f, rate) }
        val contact = floatArrayOf(0f, -.7f, .45f, .9f)[take]
        val turn = .30 + contact * .035
        var phase = 0.0
        fun smooth(x: Double) = x * x * (3 - 2 * x)
        for (i in out.indices) {
            val t = i.toDouble() / rate
            val u = (t / active).coerceIn(0.0, 1.0)
            // A hand-pressure rise followed by a slower release, not vibrato.
            val pressure = if (u < turn) smooth(u / turn) else 1 - smooth((u - turn) / (1 - turn))
            val irregular = drift.lp(noise.next())
            val hz = (155 + (220 + 80 * velocity) * pressure) * (1 + irregular * .10)
            phase += hz / rate
            phase -= floor(phase)
            var slip = 0.0
            for (h in 1..10) {
                // Finite harmonic series: no discontinuous saw before decimation.
                val weight = exp(-h * (.22 + .20 * (1 - velocity) + contact * .015)) / h
                slip += weight * sin(2 * PI * h * phase)
            }
            val rough = rasp.process(noise.next()) * (.025f + .075f * velocity) * (1f + .3f * sin(2 * PI * phase).toFloat())
            val excitation = slip.toFloat() * (1 + irregular * .8f) + rough
            val woody = .52f * excitation + .75f * body.process(excitation) + .32f * mouth.process(excitation)
            val envelope = (1 - exp(-t / .009)) * (if (t < active) 1.0 else exp(-(t - active) / .019))
            // Release finger pressure at the end, then let the short shell tail go.
            val release = if (u < .86) 1.0 else .25 + .75 * (1 - smooth((u - .86) / .14))
            out[i] = (woody * envelope * release * (.45 + .45 * velocity)).toFloat()
        }
        Tide.bandLimit(out, rate)
        val result = PatioWear.finish(Snip(Dsp.decimate(out, Dsp.RATE), 1, Dsp.RATE))
        Dsp.levelTo(result.samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET * velocity.pow(.8f), ceiling = .85f)
        Dsp.fadeTail(result.samples)
        return result
    }
}
