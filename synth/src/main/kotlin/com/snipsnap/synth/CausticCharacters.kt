package com.snipsnap.synth

import com.snipsnap.audio.Fft
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Native ports of the heard Hollow Mallet and Rubbed Ring Python character study.
 * BODY=.5 and LENGTH=1 retain its source, seed, fractional reception and modal parameters.
 * No sample playback, level matching, limiter or output conditioning runs in this helper.
 * Rubbed Ring is a one-way pressure/noise approximation, not a feedback-friction model.
 *
 * The exported circle paths become short, signed four-tap Lagrange FIRs. Overlap-add FFT
 * convolution shares each source transform across all modes; work does not scale as audio
 * samples times rays. Only the finite contact/pressure drive is convolved. Every modal pole
 * is damped, and its unforced tail continues through the requested capture.
 */
internal object CausticCharacters {
    data class Audio(val internal: FloatArray, val frequencies: DoubleArray)
    private data class Mode(val ratio: Double, val gain: Double, val t60: Double)
    private val hollowModes = arrayOf(
        Mode(1.0, 1.0, .46), Mode(2.0, .16, .24), Mode(3.005, 3.2, .38),
        Mode(4.02, .08, .12), Mode(5.01, 1.2, .24), Mode(7.03, .40, .16),
    )
    private val rubbedModes = arrayOf(
        Mode(1.0, .95, 1.05), Mode(2.0, .08, .30), Mode(3.0, 3.6, .85),
        Mode(4.0, .04, .18), Mode(5.0, 2.6, .55), Mode(7.0, 1.4, .30), Mode(9.0, .60, .18),
    )
    private val hollowHarmonics = intArrayOf(1, 2, 3, 4, 5, 7)
    private val hollowWeights = doubleArrayOf(.90, .08, .55, .04, .26, .10)
    private val rubbedHarmonics = intArrayOf(1, 2, 3, 4, 5, 7, 9)
    private val rubbedWeights = doubleArrayOf(.90, .05, .66, .025, .36, .17, .08)
    private const val TAU = 2.0 * PI
    private const val DELAY_CYCLES = .65
    private const val STUDY_SEED_MODEL = 1
    private const val CACHE_LIMIT = 6

    private data class KernelKey(val voice: CausticVoice, val midi: Int, val rate: Int,
        val geometry: CausticGeometry.Prepared)
    private data class Kernels(val size: Int, val step: Int, val length: Int,
        val re: Array<FloatArray>, val im: Array<FloatArray>)
    private val kernelCache = object : LinkedHashMap<KernelKey, Kernels>(CACHE_LIMIT, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<KernelKey, Kernels>?) = size > CACHE_LIMIT
    }

    /** Same zero-state Double Butterworth-2 as scipy.signal.butter(2, hz, fs=rate) + sosfilt.
     * The 45 Hz roughness filter needs Double state; a Float direct-form denominator loses
     * accuracy close to z=1. House output filtering remains the caller's existing pipeline. */
    private class LowPass(hz: Double, rate: Int) {
        private val k = tan(PI * hz / rate)
        private val normal = 1.0 / (1.0 + sqrt(2.0) * k + k * k)
        private val b0 = k * k * normal
        private val b1 = 2.0 * b0
        private val a1 = 2.0 * (k * k - 1.0) * normal
        private val a2 = (1.0 - sqrt(2.0) * k + k * k) * normal
        private var z1 = 0.0
        private var z2 = 0.0
        fun process(input: Double): Double {
            val out = b0 * input + z1
            z1 = b1 * input - a1 * out + z2
            z2 = b0 * input - a2 * out
            return out
        }
    }

    fun render(voice: CausticVoice, m: Map<String, Float>, midi: Int, velocity: Double,
        frames: Int, oversample: Int, geometry: CausticGeometry.Prepared): Audio {
        stopIfUnwanted()
        require(voice == CausticVoice.HOLLOW_MALLET || voice == CausticVoice.RUBBED_RING)
        require(oversample == 4 || oversample == 16)
        require(frames >= 0 && velocity.isFinite() && velocity in 0.0..1.0)
        val rate = Dsp.RATE * oversample
        val outputFrames = frames * oversample
        val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val modes = if (voice == CausticVoice.HOLLOW_MALLET) hollowModes else rubbedModes
        val frequencies = DoubleArray(modes.size) { modes[it].ratio * hz }
        if (velocity == 0.0 || outputFrames == 0) return Audio(FloatArray(outputFrames), frequencies)
        val body = m.getValue("BODY").toDouble()
        val deviation = 2.0 * body - 1.0
        val lengthScale = (.5 + 7.5 * m.getValue("LENGTH")) / 8.0
        val source = if (voice == CausticVoice.HOLLOW_MALLET)
            hollowSource(hz, midi, velocity, deviation, lengthScale, rate)
        else rubbedSource(hz, midi, velocity, deviation, lengthScale, rate)
        val kernels = kernels(voice, midi, hz, rate, geometry, modes)
        val forces = convolve(source.first, kernels, outputFrames)
        val out = DoubleArray(outputFrames)
        val fixedGain = if (voice == CausticVoice.HOLLOW_MALLET) .45 else .16
        val force = if (voice == CausticVoice.HOLLOW_MALLET) 1_000.0 else 120.0
        val bodyDamping = 2.0.pow(deviation * if (voice == CausticVoice.HOLLOW_MALLET) .30 else .20)
        for (i in modes.indices) {
            stopIfUnwanted()
            if (frequencies[i] >= 20_000.0) continue
            val scaledDecay = modes[i].t60 * lengthScale * bodyDamping
            // A very short C2 contact still needs enough cycles to remain a pitched mallet.
            // Twelve passed the measured short-C2 pitch check; six and eight did not.
            // Preserve the material's relative high-frequency loss and the heard LENGTH1 center.
            val t60 = if (voice == CausticVoice.HOLLOW_MALLET)
                max(scaledDecay, 12.0 / hz * (modes[i].t60 / hollowModes[0].t60)) else scaledDecay
            ringAdd(out, forces[i], frequencies[i], t60, force, modes[i].gain * fixedGain, rate)
        }
        if (source.second != null) {
            // The study adds pressure/contact radiation AFTER the .16 modal output gain.
            // Both branches keep the same geometry budget; neither is leveled independently.
            val radiation = convolve(source.second!!, kernels, outputFrames, single = 0)[0]
            for (i in radiation.indices) out[i] += .25 * radiation[i]
        }
        val raw = FloatArray(outputFrames)
        for (i in raw.indices) {
            if ((i and 8191) == 0) stopIfUnwanted()
            check(out[i].isFinite() && absValue(out[i]) < 8) { "CAUSTIC character raw state exceeded its bound at $i: ${out[i]}" }
            raw[i] = out[i].toFloat()
        }
        return Audio(raw, frequencies)
    }

    private fun harmonic(time: Double, hz: Double, harmonics: IntArray, weights: DoubleArray,
        tilt: Double): Double {
        var value = 0.0
        for (i in harmonics.indices) {
            val frequency = harmonics[i] * hz
            if (frequency >= 20_000.0) continue
            val rolloff = if (frequency < 16_000.0) 1.0 else .5 + .5 * cos(PI * (frequency - 16_000.0) / 4_000.0)
            value += weights[i] * harmonics[i].toDouble().pow(tilt) * rolloff * sin(TAU * frequency * time)
        }
        return value
    }

    private fun hollowSource(hz: Double, midi: Int, velocity: Double, deviation: Double,
        scale: Double, rate: Int): Pair<DoubleArray, DoubleArray?> {
        val width = (.025 + (.015 - .025) * velocity) * scale * 2.0.pow(-.65 * deviation)
        val count = ceil(width * rate).toInt() + 1
        val source = DoubleArray(count)
        val noise = Dsp.Noise(Dsp.seedFor("CAUSTIC_CHARACTER", "HOLLOW", midi, STUDY_SEED_MODEL))
        val lowpass = LowPass(2_400.0, rate)
        val effort = velocity.pow(.9)
        val texture = .055 * 2.0.pow(.4 * deviation)
        for (i in source.indices) {
            if ((i and 8191) == 0) stopIfUnwanted()
            val t = i.toDouble() / rate
            val contact = if (t < width) .5 - .5 * cos(TAU * t / width) else 0.0
            val grain = lowpass.process(noise.next().toDouble())
            source[i] = contact * (harmonic(t, hz, hollowHarmonics, hollowWeights, .35 * deviation) + texture * grain) * effort
        }
        return source to null
    }

    private fun rubbedSource(hz: Double, midi: Int, velocity: Double, deviation: Double,
        scale: Double, rate: Int): Pair<DoubleArray, DoubleArray?> {
        val rise = .34 * scale * 2.0.pow(-.7 * deviation)
        val hold = 1.18 * scale
        val release = .28 * scale
        val end = hold + release
        // The Python study allocates another 50 ms of exact zeros. Zero extension is identical.
        val count = ceil(end * rate).toInt() + 1
        val source = DoubleArray(count)
        val radiation = DoubleArray(count)
        val noise = Dsp.Noise(Dsp.seedFor("CAUSTIC_CHARACTER", "RUBBED", midi, STUDY_SEED_MODEL))
        val slowFilter = LowPass(45.0, rate)
        val roughFilter = LowPass(5_000.0, rate)
        val effort = velocity.pow(.85)
        val rough = .65 * 2.0.pow(.8 * deviation)
        val turbulence = .26 * 2.0.pow(.6 * deviation)
        for (i in source.indices) {
            if ((i and 8191) == 0) stopIfUnwanted()
            val t = i.toDouble() / rate
            var pressure = if (t < rise) .5 - .5 * cos(PI * t / rise) else 1.0
            val releasePhase = ((t - hold) / release).coerceIn(0.0, 1.0)
            pressure *= .5 + .5 * cos(PI * releasePhase)
            val n = noise.next().toDouble()
            val slow = slowFilter.process(n) * 6.0
            val grain = roughFilter.process(n)
            val tone = harmonic(t, hz, rubbedHarmonics, rubbedWeights, .30 * deviation)
            source[i] = pressure * (tone * (1.0 + rough * slow) + turbulence * grain) * effort
            radiation[i] = pressure * grain * velocity
        }
        return source to radiation
    }

    private fun kernels(voice: CausticVoice, midi: Int, hz: Double, rate: Int,
        geometry: CausticGeometry.Prepared, modes: Array<Mode>): Kernels {
        val key = KernelKey(voice, midi, rate, geometry)
        synchronized(kernelCache) { kernelCache[key]?.let { return it } }
        val maximum = geometry.paths.maxOfOrNull { it.delayR * DELAY_CYCLES / hz * rate } ?: 0.0
        val length = floor(maximum).toInt() + 4
        var size = 64
        while (size < length * 2) size *= 2
        val real = Array(modes.size) { FloatArray(size) }
        val imag = Array(modes.size) { FloatArray(size) }
        for (i in modes.indices) {
            stopIfUnwanted()
            val impulse = DoubleArray(length)
            for (path in geometry.paths) {
                val y = (path.pickup - 4) / 4.0
                val participation = if (i == 0) .92 + .08 * cos(PI * y)
                else if (voice == CausticVoice.HOLLOW_MALLET)
                    .28 + .72 * cos(PI * (.30 + .16 * (i % 4)) * (y + .12 * sin(i.toDouble())))
                else .22 + .78 * cos(PI * (.35 + .19 * (i % 4)) * (y + .20 * cos(i.toDouble())))
                val loss = 1.0 / (1.0 + (modes[i].ratio * (.035 + .085 * path.pickup / 8.0)).pow(1.6))
                val delay = path.delayR * DELAY_CYCLES / hz * rate
                val integer = floor(delay).toInt()
                val fraction = delay - integer
                for (tap in 0..3) {
                    var coefficient = 1.0
                    for (j in 0..3) if (tap != j) coefficient *= (fraction - j) / (tap - j)
                    impulse[integer + tap] += path.weight * participation * loss * coefficient
                }
            }
            for (j in impulse.indices) real[i][j] = impulse[j].toFloat()
            Fft.forward(real[i], imag[i])
        }
        val result = Kernels(size, size - length + 1, length, real, imag)
        synchronized(kernelCache) { kernelCache[key] = result }
        return result
    }

    /** Block overlap-add with one source FFT shared by all modal reception kernels. */
    private fun convolve(source: DoubleArray, kernels: Kernels, frames: Int, single: Int? = null): Array<DoubleArray> {
        val channels = if (single == null) kernels.re.size else 1
        val length = min(frames, source.size + kernels.length - 1)
        val result = Array(channels) { DoubleArray(length) }
        val sr = FloatArray(kernels.size); val si = FloatArray(kernels.size)
        val pr = FloatArray(kernels.size); val pi = FloatArray(kernels.size)
        var start = 0
        while (start < source.size && start < frames) {
            stopIfUnwanted()
            sr.fill(0f); si.fill(0f)
            val count = min(kernels.step, source.size - start)
            for (i in 0 until count) sr[i] = source[start + i].toFloat()
            Fft.forward(sr, si)
            for (channel in 0 until channels) {
                val kernel = single ?: channel
                val kr = kernels.re[kernel]; val ki = kernels.im[kernel]
                for (i in sr.indices) {
                    pr[i] = sr[i] * kr[i] - si[i] * ki[i]
                    pi[i] = sr[i] * ki[i] + si[i] * kr[i]
                }
                Fft.inverse(pr, pi)
                val end = min(count + kernels.length - 1, length - start)
                for (i in 0 until end) result[channel][start + i] += pr[i].toDouble()
            }
            start += count
        }
        return result
    }

    /** Double zero-state transfer of Modes.Bank's rotation followed by its velocity kick. */
    private fun ringAdd(out: DoubleArray, drive: DoubleArray, frequency: Double, t60: Double,
        force: Double, gain: Double, rate: Int) {
        val radius = exp(-ln(1000.0) / (t60 * rate))
        val cr = radius * cos(TAU * frequency / rate)
        val a1 = -2.0 * cr
        val a2 = radius * radius
        val b0 = 1.0 / rate
        val b1 = -cr / rate
        check(radius > 0.0 && radius < 1.0)
        var z1 = 0.0
        var z2 = 0.0
        for (i in out.indices) {
            if ((i and 8191) == 0) stopIfUnwanted()
            val x = if (i < drive.size) drive[i] * force else 0.0
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = -a2 * y
            check(y.isFinite() && absValue(y) < 8) { "CAUSTIC character mode exceeded state bound: $y" }
            out[i] += gain * y
        }
    }

    private fun absValue(x: Double) = if (x < 0.0) -x else x
    private fun stopIfUnwanted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("CAUSTIC character render no longer wanted")
    }
}
