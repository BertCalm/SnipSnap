package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.*
import kotlin.test.*

/** Edge and numerical-reference probes, separate from listening acceptance. */
class ApsisMechanismTest {
    private val intense = Apsis.defaults(ApsisVoice.PASSAGE) + mapOf(
        "STRETCH" to 1f, "DISTANCE" to 0f, "BODIES" to 1f,
        "ALIGN" to .8f, "COUPLE" to 1f, "HOLD" to 0f)
    private val finiteOptions = Apsis.ProbeOptions(normalize = false, seconds = .8f,
        recordStems = false, recordTraces = false)

    @Test
    fun `silent exported hold converges without inventing energy or an invalid seam`() {
        val macros = intense + mapOf("BODIES" to 0f, "HOLD" to 1f)
        val probe = Apsis.probe(ApsisVoice.PASSAGE, macros, 60, 0f,
            options = Apsis.ProbeOptions(normalize = true, recordStems = false, recordTraces = false))
        assertTrue(probe.loopFrames > 0)
        assertEquals(probe.loopFrames, probe.samples.size)
        assertEquals(probe.loopFrames, probe.previousCycle.size)
        assertTrue(probe.samples.all { it == 0f })
        assertTrue(probe.previousCycle.all { it == 0f })
        assertEquals(0.0, probe.diagnostics.maxStateNorm)
        assertEquals(0.0, probe.diagnostics.finalEnergy)
        assertEquals(0.0, probe.seamError)
        assertTrue(probe.stateError.isFinite())
        assertTrue(probe.diagnostics.converged)
        println("APSIS zero-energy HOLD frames=${probe.loopFrames} preroll=${probe.diagnostics.prerollCycles} seam=${probe.seamError}")
    }

    @Test
    fun `held diagnostic replays every scheduled encounter through more than two complete loops`() {
        val seconds = 10f
        val probe = Apsis.probe(ApsisVoice.PASSAGE, intense + ("HOLD" to 1f), 60, .8f,
            options = finiteOptions.copy(seconds = seconds))
        assertEquals((seconds * Dsp.RATE).roundToInt(), probe.samples.size)
        assertTrue(probe.samples.all { it.isFinite() })
        assertTrue(probe.samples.any { it != 0f })
        val loop = probe.diagnostics.periods.last()
        assertTrue(seconds > 2 * loop)
        val lastSample = (probe.samples.size * Dsp.OVERSAMPLE - 1).toDouble() /
            (Dsp.RATE * Dsp.OVERSAMPLE)
        val expected = IntArray(probe.diagnostics.periods.size)
        for (body in expected.indices) {
            val eachLoop = probe.events.filter { it.body == body }
            val expectedPerLoop = (loop / probe.diagnostics.periods[body]).roundToInt()
            assertEquals(expectedPerLoop, eachLoop.size, "body $body did not schedule one encounter per orbit")
            for (event in eachLoop) {
                var cycle = 0
                while (event.timeSeconds + cycle * loop <= lastSample) {
                    expected[body]++
                    cycle++
                }
            }
        }
        assertContentEquals(expected, probe.diagnostics.transientCounts,
            "integer held clocks lost or doubled a crossing at a loop boundary")
        println("APSIS diagnostic HOLD loop=$loop seconds=$seconds expected=${expected.toList()} actual=${probe.diagnostics.transientCounts.toList()}")
    }

    @Test
    fun `bright fast high note agrees spectrally with a sixteen times reference`() {
        val standard = Apsis.probe(ApsisVoice.PASSAGE, intense, 84, 1f,
            options = finiteOptions.copy(oversample = 4))
        val reference = Apsis.probe(ApsisVoice.PASSAGE, intense, 84, 1f,
            options = finiteOptions.copy(oversample = 16))
        assertEquals((.8 * Dsp.RATE).roundToInt(), standard.samples.size)
        assertEquals(standard.samples.size, reference.samples.size)
        for (samples in listOf(standard.samples, reference.samples)) {
            assertTrue(samples.all { it.isFinite() })
            assertTrue(rms(samples, 0, samples.size) > 1e-5, "a silent reference cannot validate the renderer")
        }
        for (start in listOf(.20, .38)) {
            val native = spectrum(standard.samples, start)
            val finer = spectrum(reference.samples, start)
            val distance = native.indices.sumOf { abs(native[it] - finer[it]) } * .5
            val nativeWidth = bandwidth(native, .999)
            val referenceWidth = bandwidth(finer, .999)
            val highStart = (14_000.0 * FFT_SIZE / Dsp.RATE).ceilToInt()
            val highNative = native.drop(highStart).sum()
            val highReference = finer.drop(highStart).sum()
            println("APSIS rate reference t=$start powerDistance=$distance bandwidth99.9=$nativeWidth/$referenceWidth Hz above14kPower=$highNative/$highReference")
            // A coarse agreement guard, not a formal alias-floor measurement. Normalized
            // power avoids mistaking conditioning phase changes for folded spectral power.
            assertTrue(distance < .05, "4x/16x harmonic/sideband power moved substantially: $distance at $start")
            assertTrue(nativeWidth < 19_500 && referenceWidth < 19_500,
                "the bright fixture accumulated substantial near-Nyquist power")
        }
    }

    @Test
    fun `sixteen output frame controls approach the finer reference around periapsis`() {
        val standard = Apsis.probe(ApsisVoice.PASSAGE, intense, 84, 1f,
            options = finiteOptions.copy(controlStride = 64))
        val finer = Apsis.probe(ApsisVoice.PASSAGE, intense, 84, 1f,
            options = finiteOptions.copy(controlStride = 32))
        assertEquals(standard.samples.size, finer.samples.size)
        val start = (.25 * Dsp.RATE).roundToInt()
        val end = (.50 * Dsp.RATE).roundToInt()
        assertTrue(rms(finer.samples, start, end) > 1e-5)
        var error = 0.0
        var energy = 0.0
        for (frame in start until end) {
            val delta = standard.samples[frame].toDouble() - finer.samples[frame]
            error += delta * delta
            energy += finer.samples[frame].toDouble().pow(2)
        }
        val relative = sqrt(error / energy.coerceAtLeast(1e-30))
        println("APSIS 64/32 internal-frame control reference periapsis relativeRmsError=$relative")
        assertTrue(relative.isFinite() && relative < .01,
            "16-output-frame controls diverged from the finer periapsis reference: $relative")
    }

    private fun spectrum(samples: FloatArray, seconds: Double): DoubleArray {
        val start = (seconds * Dsp.RATE).roundToInt()
        require(start + FFT_SIZE <= samples.size)
        val magnitude = Fft.magnitudeSpectrum(samples.copyOfRange(start, start + FFT_SIZE), FFT_SIZE)
        val power = DoubleArray(magnitude.size) { magnitude[it].toDouble().pow(2) }
        val sum = power.sum()
        assertTrue(sum.isFinite() && sum > 0.0)
        for (index in power.indices) power[index] /= sum
        return power
    }

    private fun bandwidth(power: DoubleArray, proportion: Double): Double {
        var cumulative = 0.0
        for (index in power.indices) {
            cumulative += power[index]
            if (cumulative >= proportion) return index.toDouble() * Dsp.RATE / FFT_SIZE
        }
        return Dsp.RATE * .5
    }

    private fun rms(samples: FloatArray, start: Int, end: Int): Double {
        var sum = 0.0
        for (frame in start until end) sum += samples[frame].toDouble().pow(2)
        return sqrt(sum / (end - start))
    }

    private fun Double.ceilToInt() = ceil(this).toInt()
    private companion object { const val FFT_SIZE = 8192 }
}
