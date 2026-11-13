package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Regression and contrast measurements for model 2. These do not establish audible acceptance. */
class SplayRevisionTest {
    private val voice = SplayVoice.TONGUE
    private fun macros(vararg edits: Pair<String, Float>) = Splay.defaults(voice, model = 2) + edits.toMap()
    private fun render(m: Map<String, Float> = macros(), midi: Int = 60, velocity: Float = .8f) =
        Splay.renderDetailed(voice, m, midi = midi, velocity = velocity, seed = 41001, model = 2)

    private fun rms(a: FloatArray, from: Double, to: Double): Double {
        val start = (from * Dsp.RATE).toInt().coerceIn(0, a.size)
        val end = (to * Dsp.RATE).toInt().coerceIn(start, a.size)
        var energy = 0.0
        for (i in start until end) energy += a[i].toDouble() * a[i]
        return sqrt(energy / (end - start).coerceAtLeast(1))
    }

    private fun correlation(a: FloatArray, b: FloatArray, from: Double, to: Double): Double {
        val start = (from * Dsp.RATE).toInt()
        val end = (to * Dsp.RATE).toInt().coerceAtMost(minOf(a.size, b.size))
        var aa = 0.0; var bb = 0.0; var ab = 0.0; var ma = 0.0; var mb = 0.0
        for (i in start until end) {
            val x = a[i].toDouble(); val y = b[i].toDouble()
            aa += x * x; bb += y * y; ab += x * y; ma += x; mb += y
        }
        val n = (end - start).coerceAtLeast(1)
        return (ab - ma * mb / n) / sqrt((aa - ma * ma / n) * (bb - mb * mb / n)).coerceAtLeast(1e-30)
    }

    @Test
    fun `original first seams raw PCM remains byte identical to the published model one`() {
        // Fixed published PCM16 reference over the supported JVM render contract. This includes
        // the original defaults, acoustic seed, solver arithmetic, conditioning and WAV writer.
        val old = Splay.renderDetailed(voice, Splay.defaults(voice, model = 1), midi = 60,
            velocity = .8f, seed = 41001, model = 1)
        val bytes = ByteArrayOutputStream().also {
            WavWriter.write(it, Snip(old.raw, channels = 1, sampleRate = Dsp.RATE), WavWriter.BitDepth.PCM_16)
        }.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("6affcf34d0c2692bcb69a1e8ee176e5b0e896ab6595d51dcf6063e513962071b", hash,
            "the published model-1 performance changed")
    }

    @Test
    fun `revised identical population remains equivalent to one physical mechanism`() {
        val single = render(macros("SEED GAP" to 0f, "POPULATION" to 0f))
        val many = render(macros("SEED GAP" to 0f, "POPULATION" to 15f / 99f))
        var difference = 0.0
        var energy = 0.0
        for (i in single.raw.indices) {
            val d = single.raw[i].toDouble() - many.raw[i]
            difference += d * d
            energy += single.raw[i].toDouble() * single.raw[i]
        }
        assertTrue(sqrt(difference / energy.coerceAtLeast(1e-30)) < 1e-5,
            "model 2 adds population-dependent sound to identical trajectories")
    }

    @Test
    fun `revision keeps an initially coherent opening then separates within three seconds without a quiet collapse`() {
        val divergent = render()
        val identical = render(macros("SEED GAP" to 0f))
        val early = correlation(divergent.raw, identical.raw, .02, .15)
        val later = correlation(divergent.raw, identical.raw, 1.0, 3.0)
        val levelRatio = rms(divergent.raw, 1.0, 3.0) / rms(identical.raw, 1.0, 3.0)
        assertTrue(early > .98, "the release is already dispersed: correlation $early")
        assertTrue(abs(later) < .90, "the revision does not create an earlier waveform difference: correlation $later")
        assertTrue(levelRatio > .50, "the separated population loses more than 6 dB: RMS ratio $levelRatio")
        assertTrue(rms(divergent.raw, 1.0, 3.0) > rms(divergent.raw, .02, .20) * .20,
            "the opening still overwhelms the separated gesture")
        assertTrue(divergent.diagnostics.lateCorrelation < divergent.diagnostics.earlyCorrelation,
            "the acoustic contrast is not accompanied by changing contact histories")
    }

    @Test
    fun `revised acoustic compensation preserves the explicit bank reference`() {
        val m = macros("POPULATION" to 3f / 99f)
        val summed = render(m)
        val individual = Splay.renderDetailed(voice, m, midi = 60, velocity = .8f,
            seed = 41001, model = 2, referenceBank = true)
        var difference = 0.0
        var energy = 0.0
        for (i in summed.raw.indices) {
            val delta = summed.raw[i].toDouble() - individual.raw[i]
            difference += delta * delta
            energy += summed.raw[i].toDouble() * summed.raw[i]
        }
        assertTrue(sqrt(difference / energy.coerceAtLeast(1e-30)) < 1e-5,
            "revised force aggregation changes independently mapped participant responses")
    }

    @Test
    fun `revision remains finite and rooted across registers and maximum population`() {
        for ((midi, population) in listOf(36 to 0f, 60 to 1f, 84 to 0f)) {
            val p = render(macros("POPULATION" to population), midi = midi)
            assertEquals(1, p.snip.channels)
            assertEquals(44_100, p.snip.sampleRate)
            assertTrue(p.raw.all { it.isFinite() })
            assertTrue(p.snip.samples.all { it.isFinite() && abs(it) <= .94001f })
            assertTrue(p.diagnostics.rawPeak < 2.0, "MIDI $midi raw peak ${p.diagnostics.rawPeak}")
            val estimate = assertNotNull(Pitch.detect(p.snip, .04f, .25f), "MIDI $midi lost its opening root")
            val cents = 1200 * ln(estimate.hz / Keys.midiHz(midi).toDouble()) / ln(2.0)
            assertTrue(abs(cents) < 15, "MIDI $midi root error $cents cents")
            assertTrue(estimate.confidence > .40f, "MIDI $midi root confidence ${estimate.confidence}")
            assertTrue(rms(p.raw, 6.8, 7.8) < rms(p.raw, 4.0, 6.0) * .1,
                "MIDI $midi has not decayed before the end fade")
        }
    }
}
