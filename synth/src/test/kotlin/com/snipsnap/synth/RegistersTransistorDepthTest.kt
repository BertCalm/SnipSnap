package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sound-contract checks for the fuller model, including literal published model-4 PCM. */
class RegistersTransistorDepthTest {
    private val voice = RegistersVoice.TRANSISTOR

    private fun pcm24Hash(samples: FloatArray): String {
        val encoded = ByteArrayOutputStream()
        WavWriter.write(encoded, Snip(samples, channels = 1, sampleRate = Dsp.RATE), WavWriter.BitDepth.PCM_24)
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()))
    }

    private fun rms(samples: FloatArray, from: Int, until: Int): Double =
        sqrt((from until until).sumOf { samples[it].toDouble() * samples[it] } / (until - from))

    private fun spectrum(samples: FloatArray): DoubleArray {
        val count = 32768
        val size = 65536
        val from = Dsp.RATE / 3
        val real = FloatArray(size)
        val imaginary = FloatArray(size)
        for (i in 0 until count) real[i] = samples[from + i] *
            (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(real, imaginary)
        return DoubleArray(size / 2) { real[it].toDouble() * real[it] + imaginary[it].toDouble() * imaginary[it] }
    }

    private fun band(power: DoubleArray, hz: Double, widthHz: Double = 12.0): Double {
        val from = ((hz - widthHz) * 65536 / Dsp.RATE).roundToInt().coerceAtLeast(1)
        val until = ((hz + widthHz) * 65536 / Dsp.RATE).roundToInt().coerceAtMost(power.lastIndex)
        return (from..until).sumOf { power[it] }
    }

    @Test
    fun `published model four finite and held recordings retain their exact PCM`() {
        // Literal hashes from the published r4-transistor audition. Neither fixture needs
        // its audio pack at test time; rawExportGain was 1 for both published recordings.
        val finite = Registers.probe(voice, midi = 60, model = 4)
        assertEquals(101518, finite.samples.size)
        assertEquals("2914bc32937dbecc79439f68fbc2dc64ee8206ff3b0558bf94b688d3038da396", pcm24Hash(finite.samples))
        val held = Registers.probe(voice, mapOf("HOLD" to 1f, "SPREAD" to .9f, "MOTION" to .9f),
            midi = 60, velocity = .8f, model = 4)
        val playback = FloatArray(held.samples.size * 3) { held.samples[it % held.samples.size] }
        assertEquals(254865, playback.size)
        assertEquals("7a48aa787aa5f22c712fc5d0fd1a6fa264bb876160a2e312d395e7bca796a1d5", pcm24Hash(playback))
    }

    @Test
    fun `fuller registration adds same pitch body at matched energy and retains the initial keyer`() {
        for (midi in listOf(48, 60, 69)) {
            val old = Registers.probe(voice, midi = midi, model = 4)
            val full = Registers.probe(voice, midi = midi, model = 5)
            assertEquals(old.samples.size, full.samples.size)
            val clickFrames = (.012 * Dsp.RATE).roundToInt()
            assertContentEquals(old.samples.copyOfRange(0, clickFrames), full.samples.copyOfRange(0, clickFrames),
                "MIDI$midi initial click/keying changed")
            val oldPower = spectrum(old.samples)
            val fullPower = spectrum(full.samples)
            val oldBody = band(oldPower, old.requestedHz) / oldPower.drop(1).sum()
            val fullBody = band(fullPower, full.requestedHz) / fullPower.drop(1).sum()
            println("TRANSISTOR MIDI$midi normalized fundamental: $oldBody -> $fullBody")
            assertTrue(fullBody > oldBody + .025,
                "MIDI$midi body must gain low-partial weight at the same energy: $oldBody -> $fullBody")
            assertTrue(band(fullPower, full.requestedHz / 2) / band(fullPower, full.requestedHz) < 1e-5,
                "added depth must not introduce a lower note")
            val from = (.300 * Dsp.RATE).roundToInt()
            val until = Dsp.RATE
            val oldRms = rms(old.samples, from, until)
            val fullRms = rms(full.samples, from, until)
            val matchedDifference = sqrt((from until until).sumOf {
                val difference = full.samples[it] / fullRms - old.samples[it] / oldRms
                difference * difference
            } / (until - from))
            assertTrue(matchedDifference > .06, "MIDI$midi change must survive equal RMS: $matchedDifference")
            assertTrue(rms(full.samples, 0, (.010 * Dsp.RATE).roundToInt()) >
                rms(full.samples, (.100 * Dsp.RATE).roundToInt(), (.150 * Dsp.RATE).roundToInt()) * .45,
                "the fuller registration must retain immediate electronic speech")
            assertNull(full.airflowSamples)
        }
    }

    @Test
    fun `full depth held banks recur independently at both pitch extremes`() {
        val maximum = mapOf("COLOR" to 1f, "AIR" to 1f, "EDGE" to 1f,
            "SPREAD" to 1f, "MOTION" to 1f, "HOLD" to 1f)
        for (midi in listOf(24, 96)) {
            val probe = Registers.probe(voice, maximum, midi, .8f, model = 5)
            assertFalse(requireNotNull(probe.previousCycle) === probe.samples)
            assertTrue(requireNotNull(probe.cycleError) < 1e-5)
            assertTrue(requireNotNull(probe.seamError) < Keys.MAX_SEAM_ERROR)
            assertTrue(abs(1200 * ln(probe.effectiveHz / probe.requestedHz) / ln(2.0)) < .1)
            assertTrue(probe.samples.all { it.isFinite() })
            assertTrue(probe.rawPeak <= Registers.RAW_PEAK_CEILING)
        }
    }

    @Test
    fun `current model preserves accepted pipe and musette finite and held audio`() {
        for (accepted in listOf(RegistersVoice.PIPE, RegistersVoice.MUSETTE)) for (hold in listOf(.35f, 1f)) {
            val macros = mapOf("AIR" to .72f, "SPREAD" to .8f, "MOTION" to .7f, "HOLD" to hold)
            assertContentEquals(Registers.probe(accepted, macros, model = 4).samples,
                Registers.probe(accepted, macros, model = 5).samples, "$accepted HOLD$hold changed")
        }
    }
}
