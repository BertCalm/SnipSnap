package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/** Audible balance and response: sample inequality alone cannot establish either. */
class FlotillaListeningTest {
    @Test
    fun `water remains a dark texture underneath all six voices and dense hold`() {
        for ((voice, macros) in FlotillaVoice.entries.map { it to emptyMap<String, Float>() } +
            listOf(FlotillaVoice.GATHER to mapOf("HOLD" to 1f))) {
            val full = Flotilla.renderInternal(voice, macros, 60, 1f).snip.samples
            val withoutWater = Flotilla.renderInternal(voice, macros, 60, 1f, aquaticSound = false).snip.samples
            val water = residue(full, withoutWater)
            val share = rms(water) / rms(full)
            val upper = spectrum(water).let { bins ->
                bins.indices.filter { it * 44100.0 / FFT_SIZE > 2500 }.sumOf { bins[it] * bins[it] } /
                    bins.sumOf { it * it }.coerceAtLeast(1e-18)
            }
            println("Water $voice $macros: residual RMS share=$share, power above 2.5 kHz=$upper")
            assertTrue(share < 0.16, "$voice $macros water/source share $share")
            assertTrue(share > 0.001, "$voice lost its water texture: $share")
            assertTrue(upper < 0.04, "$voice $macros high-frequency water fraction $upper")
        }
    }

    @Test
    fun `surface strengthens the heard hull and vessel size lowers its spectrum`() {
        fun hull(macros: Map<String, Float>): Pair<Double, Double> {
            val full = Flotilla.renderInternal(FlotillaVoice.RIPPLE, macros, 60, 1f, aquaticSound = false).snip.samples
            val emitter = Flotilla.renderInternal(FlotillaVoice.RIPPLE, macros, 60, 1f, collisionSound = false, aquaticSound = false).snip.samples
            val hull = residue(full, emitter)
            val bins = spectrum(hull)
            val energy = bins.sumOf { it * it }.coerceAtLeast(1e-18)
            val center = bins.indices.sumOf { it * 44100.0 / FFT_SIZE * bins[it] * bins[it] } / energy
            return rms(hull) / rms(emitter) to center
        }
        val quiet = hull(mapOf("SURFACE" to 0f))
        val strong = hull(mapOf("SURFACE" to 1f))
        assertTrue(strong.first > quiet.first * 1.35, "hull response ${quiet.first} -> ${strong.first}")
        val small = hull(mapOf("VESSEL" to 0f, "SURFACE" to 0.6f))
        val large = hull(mapOf("VESSEL" to 1f, "SURFACE" to 0.6f))
        println("Hull SURFACE RMS share ${quiet.first} -> ${strong.first}; VESSEL centroid ${small.second} -> ${large.second} Hz")
        assertTrue(large.second < small.second * 0.85, "hull center ${small.second} -> ${large.second}")
    }

    @Test
    fun `crossing routes and population audibly reshape the ripple at matched gain`() {
        for (macro in listOf("CROSSING", "FLOTILLA")) {
            val low = Flotilla.render(FlotillaVoice.RIPPLE, mapOf(macro to 0f)).samples
            val high = Flotilla.render(FlotillaVoice.RIPPLE, mapOf(macro to 1f)).samples
            val change = rms(residue(high, low)) / rms(high)
            println("RIPPLE $macro gain-aligned RMS change=$change")
            assertTrue(change > 0.18, "$macro barely reshaped the audio: $change")
        }
    }

    @Test
    fun `held sparse and held dense retain the requested pitched emitter`() {
        for (voice in listOf(FlotillaVoice.DRIFT, FlotillaVoice.GATHER)) {
            val held = FlotillaPresets.forVoice(voice).first { it.name.startsWith("Held") }
            // Wood modes are deliberately inharmonic: autocorrelation blends
            // them with the emitter. Measure the strongest spectral line so
            // this gate asks whether C4 remains the heard signal's anchor.
            val size = 65536
            val bins = Fft.magnitudeSpectrum(held.render().samples, size)
            val peak = (1 until bins.lastIndex).maxBy { bins[it] }
            val a = ln(bins[peak - 1].toDouble().coerceAtLeast(1e-12))
            val b = ln(bins[peak].toDouble().coerceAtLeast(1e-12))
            val c = ln(bins[peak + 1].toDouble().coerceAtLeast(1e-12))
            val offset = 0.5 * (a - c) / (a - 2 * b + c)
            val hz = (peak + offset) * 44100.0 / size
            val cents = 1200 * ln(hz / Flotilla.frequencyFor(60)) / ln(2.0)
            println("Held $voice dominant line=$hz Hz, $cents cents from C4")
            assertTrue(abs(cents) < 5, "$voice held strongest line $cents cents off")
        }
    }

    private fun residue(full: FloatArray, reference: FloatArray): FloatArray {
        // Renderer loudness matching chooses a different final gain when a
        // component is muted. Fit that gain before measuring the residue.
        val norm = reference.sumOf { it.toDouble() * it }.coerceAtLeast(1e-18)
        val gain = full.indices.sumOf { full[it].toDouble() * reference[it] } / norm
        return FloatArray(full.size) { (full[it] - gain * reference[it]).toFloat() }
    }

    private fun spectrum(samples: FloatArray): DoubleArray {
        val energy = DoubleArray(FFT_SIZE / 2 + 1)
        for (at in 0..(samples.size - FFT_SIZE).coerceAtLeast(0) step FFT_SIZE / 2) {
            val bins = Fft.magnitudeSpectrum(samples.copyOfRange(at, minOf(at + FFT_SIZE, samples.size)), FFT_SIZE)
            for (i in bins.indices) energy[i] += bins[i].toDouble() * bins[i]
        }
        return DoubleArray(energy.size) { sqrt(energy[it]) }
    }

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    private companion object { const val FFT_SIZE = 8192 }
}
