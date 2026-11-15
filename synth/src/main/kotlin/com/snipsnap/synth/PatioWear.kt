package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/**
 * PATIO's first listening model: saturation -> moving transport -> loss -> amp.
 * This is a behavioral tape approximation, not a measured machine or hysteresis solver.
 * No rack order changes: callers explicitly place this processor before VALVE.
 */
object PatioWear {
    data class Settings(val wear: Float = .30f, val wobble: Float = .20f, val drive: Float = .35f) {
        init { require(listOf(wear, wobble, drive).all { it.isFinite() && it in 0f..1f }) }
    }

    /** Seeded control knots, evaluated in seconds so a higher-rate reference uses the same motion. */
    private class Motion(seed: Int, seconds: Double, private val hz: Double) {
        private val noise = Dsp.Noise(seed)
        private val knots = DoubleArray(ceil(seconds * hz).toInt() + 3) { noise.next().toDouble() }
        fun at(t: Double): Double {
            val p = t * hz
            val i = floor(p).toInt().coerceIn(0, knots.lastIndex - 1)
            val blend = .5 - .5 * cos(PI * (p - floor(p)))
            return knots[i] + blend * (knots[i + 1] - knots[i])
        }
    }

    fun tape(input: Snip, settings: Settings, seed: Int): Snip = tapeAt(input, settings, seed, 4)

    /** The 8x path is a numerical reference, not another saved sound. */
    internal fun tapeAt(input: Snip, settings: Settings, seed: Int, factor: Int): Snip {
        require(input.channels == 1 && input.sampleRate == Dsp.RATE) { "PATIO takes mono 44.1 kHz" }
        require(input.samples.all { it.isFinite() })
        require(factor == 4 || factor == 8)
        if (input.frameCount == 0 || input.peak() == 0f) return input.copy(samples = input.samples.copyOf())
        if (settings.wear == 0f && settings.wobble == 0f) return input.copy(samples = input.samples.copyOf())
        val rate = Dsp.RATE * factor
        // A finite guard retains filter/transport release instead of truncating the input's last hit.
        val frames = input.frameCount + (Dsp.RATE * .025).toInt()
        val up = FloatArray(frames * factor)
        for (i in input.samples.indices) up[i * factor] = input.samples[i] * factor
        Tide.bandLimit(up, rate)
        val gain = 1f + 2.5f * settings.wear
        for (i in up.indices) {
            // Bounded static saturation with a small asymmetry; zero input stays exactly zero.
            val x = up[i]
            val saturated = tanh(gain * (x + .04f * settings.wear * x * x)) / gain
            up[i] = x + settings.wear * (saturated - x)
        }
        val seconds = frames.toDouble() / Dsp.RATE
        val wow = Motion(Dsp.seedFor(seed, "wow"), seconds, 2.1)
        val flutter = Motion(Dsp.seedFor(seed, "flutter"), seconds, 31.0)
        val losses = Motion(Dsp.seedFor(seed, "loss"), seconds, 9.0)
        val rng = Dsp.Noise(Dsp.seedFor(seed, "drop"))
        val dropTime = .16 + .36 * (rng.next() + 1.0) * .5
        val cutoff = Dsp.expMap(1f - settings.wear, 3_500f, 17_000f)
        val head = Dsp.FixedOnePole(cutoff, rate)
        val wowStart = wow.at(0.0)
        val flutterStart = flutter.at(0.0)
        val out = FloatArray(up.size)
        for (i in out.indices) {
            val t = i.toDouble() / rate
            // Fade motion into the onset. The transport has no fixed delay and starts at sample zero.
            val arrival = min(1.0, t / .020)
            val flutterNow = flutter.at(t)
            val shift = settings.wobble * arrival * rate *
                (.0012 * (wow.at(t) - wowStart) + .00008 * (flutterNow - flutterStart))
            val pos = i + shift
            val a = floor(pos).toInt()
            val frac = (pos - a).toFloat()
            val x = up.getOrElse(a) { 0f } * (1f - frac) + up.getOrElse(a + 1) { 0f } * frac
            val flutterGain = 1.0 - .09 * settings.wobble * (1.0 + flutterNow) * .5
            // One soft dropout per take at most. The main attack is outside its support.
            val distance = abs(t - dropTime) / .018
            val drop = if (distance < 1.0) .5 + .5 * cos(PI * distance) else 0.0
            val loss = 1.0 - settings.wear.pow(3) * (.65 * drop + .08 * (1.0 + losses.at(t)) * .5)
            out[i] = head.lp((x * flutterGain * loss).toFloat())
        }
        Tide.bandLimit(out, rate)
        // Dsp.decimate is the house fixed 4:1 path. For 8x, first decimate to twice the output rate.
        val base = if (factor == 4) Dsp.decimate(out, Dsp.RATE)
            else com.snipsnap.audio.Resampler.resample(Snip(Dsp.decimate(out, Dsp.RATE * 2), 1, Dsp.RATE * 2), Dsp.RATE).samples
        return finish(Snip(base, 1, Dsp.RATE))
    }

    fun amp(input: Snip, settings: Settings): Snip = finish(Valve.process(input, mapOf(
        "DRIVE" to settings.drive, "SAG" to .25f, "TONE" to .58f, "CAB" to .30f,
    )))

    fun process(input: Snip, settings: Settings, seed: Int): Snip = amp(tape(input, settings, seed), settings)

    internal fun finish(input: Snip): Snip {
        val x = input.samples.copyOf()
        if (x.isEmpty()) return input.copy(samples = x)
        val mean = x.sumOf { it.toDouble() } / x.size
        val low = Dsp.FixedOnePole(20f, input.sampleRate)
        for (i in x.indices) {
            val v = (x[i] - mean).toFloat()
            x[i] = v - low.lp(v)
        }
        // Downward headroom correction only. Do not raise every damaged tail back to full level.
        Dsp.limitPeak(x, .95f)
        Dsp.fadeTail(x, rate = input.sampleRate)
        return input.copy(samples = x)
    }
}
