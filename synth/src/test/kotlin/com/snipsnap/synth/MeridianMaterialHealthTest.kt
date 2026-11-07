package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Broad pitch, material range and real-state loop gates; listening remains separate. */
class MeridianMaterialHealthTest {
    private data class Reading(val voice: MeridianVoice, val midi: Int) {
        val macros = Meridian.defaults(voice)
        val probe by lazy { Meridian.renderProbe(voice, macros, midi, 1f, SEED, normalize = true) }
    }

    @Test
    fun `new material defaults use the selected profile and match the independent finite pass`() {
        for (voice in VOICES) {
            val reading = readings.single { it.voice == voice && it.midi == 57 }
            assertEquals(1f, reading.macros.getValue("SURFACE"))
            assertEquals(.4f, reading.macros.getValue("MEMORY"))
            assertEquals(0f, reading.macros.getValue("HOLD"))
            val public = Meridian.render(voice, reading.macros, 57, 1f, SEED, model = 3)
            assertContentEquals(reading.probe.samples, public.samples,
                "$voice optimized surface order changed the reference arithmetic")
            val decoded = Patches.fromJsonText(MeridianPatch("Material replay", voice,
                reading.macros, seedContext = SEED).toJsonText())
            assertContentEquals(public.samples, decoded.render().samples,
                "$voice saved recipe does not recreate the selected material")
        }
    }

    @Test
    fun `body and unforced tail retain a substantial requested root across four native notes`() {
        for (reading in readings) {
            val probe = reading.probe
            val root = Meridian.frequencyFor(reading.voice, .5f, reading.midi)
            assertTrue(probe.samples.all { it.isFinite() && abs(it) <= 1f })
            assertTrue(probe.diagnostics.rawPeak in 1e-5..8.0,
                "${reading.voice}/${reading.midi} raw peak ${probe.diagnostics.rawPeak}")
            assertTrue(probe.diagnostics.peakEnergy.isFinite() && probe.diagnostics.peakEnergy < 100.0)
            for (from in listOf(.18f, .90f, 2.05f)) {
                val duration = .35f
                val signal = rms(probe.samples, from.toDouble(), (from + duration).toDouble())
                assertTrue(signal > 1e-5, "${reading.voice}/${reading.midi} window $from is silent")
                val estimate = assertNotNull(Pitch.detect(Snip(probe.samples, 1, Dsp.RATE), from, duration),
                    "${reading.voice}/${reading.midi} lost periodicity in window $from")
                val cents = 1200 * ln(estimate.hz / root.toDouble()) / ln(2.0)
                val share = rootShare(probe.samples, root.toDouble(), from, duration)
                assertTrue(estimate.confidence >= .3f,
                    "${reading.voice}/${reading.midi} uncertain root ${estimate.confidence} at $from")
                assertTrue(abs(cents) < 50,
                    "${reading.voice}/${reading.midi} broad detector moved $cents cents at $from")
                assertTrue(share > .10,
                    "${reading.voice}/${reading.midi} detector followed a negligible root ($share) at $from")
                println("MERIDIAN material ${reading.voice} MIDI${reading.midi} from=$from " +
                    "cents=$cents confidence=${estimate.confidence} rootShare=$share rms=$signal")
            }
        }
    }

    @Test
    fun `all three material spectra remain broader than SWEEP and distinct from one another`() {
        val stock = spectrum(Meridian.render(MeridianVoice.SWEEP, midi = 57,
            seedContext = SEED, model = 2).samples, .0f, 1.5f)
        val materials = VOICES.associateWith { voice ->
            val samples = readings.single { it.voice == voice && it.midi == 57 }.probe.samples
            spectrum(samples, .0f, 1.5f)
        }
        for ((voice, bins) in materials) {
            val centroid = centroid(bins)
            val above = bandShare(bins) { it > 880.0 }
            assertTrue(centroid > centroid(stock) * 1.4,
                "$voice collapsed toward SWEEP's spectral balance: $centroid Hz")
            assertTrue(above > .06, "$voice lost the selected upper-mode presence: $above")
            println("MERIDIAN $voice centroid=$centroid above880=$above")
        }
        for (a in VOICES.indices) for (b in a + 1 until VOICES.size) {
            val left = materials.getValue(VOICES[a])
            val right = materials.getValue(VOICES[b])
            val difference = left.indices.sumOf { abs(left[it] - right[it]) } * .5
            assertTrue(difference > .10,
                "${VOICES[a]} and ${VOICES[b]} differ only in level or share one profile: $difference")
        }
    }

    @Test
    fun `nonfinite controls and map order retain deterministic material noise identity`() {
        for (voice in VOICES) {
            val controls = Meridian.defaults(voice)
            val expected = readings.single { it.voice == voice && it.midi == 57 }.probe.samples
            val reordered = controls.entries.reversed().associate { it.key to it.value }
            assertContentEquals(expected, Meridian.render(voice, reordered, seedContext = SEED, model = 3).samples)
            assertContentEquals(expected, Meridian.render(voice, controls + mapOf(
                "SURFACE" to Float.NaN, "LENGTH" to Float.POSITIVE_INFINITY),
                seedContext = SEED, model = 3).samples)
            val changed = Meridian.render(voice, controls, seedContext = SEED + 1, model = 3)
            assertFalse(expected.contentEquals(changed.samples), "$voice lost its saved roughness seed")
        }
    }

    @Test
    fun `MEMORY changes retained state while preserving the actual contact force`() {
        for (voice in VOICES) {
            fun probe(memory: Float) = Meridian.renderProbe(voice, mapOf("MEMORY" to memory),
                seedContext = SEED, activeSeconds = .65f, tailSeconds = .6f)
            val short = probe(.2f)
            val long = probe(.8f)
            assertContentEquals(short.diagnostics.inputHashes, long.diagnostics.inputHashes,
                "$voice MEMORY changed excitation instead of stored response")
            assertContentEquals(short.diagnostics.roughnessHashes, long.diagnostics.roughnessHashes)
            assertTrue(rms(long.samples, .75, .90) > rms(short.samples, .75, .90) * 1.1,
                "$voice MEMORY no longer retains a longer unforced tail")
        }
    }

    @Test
    fun `new held voices close real source and modal states through PCM24 export`() {
        for (voice in VOICES) {
            val macros = Meridian.defaults(voice) + mapOf("LATITUDE" to .5f, "HOLD" to 1f)
            val held = Meridian.renderHeld(voice, macros, 57, 1f, SEED, model = 3)
            val samples = held.snip.samples
            val start = held.loopStartFrame
            assertEquals(2 * start, held.loopEndFrameExclusive)
            assertEquals(held.loopEndFrameExclusive, held.snip.frameCount)
            assertTrue(start.toDouble() / Dsp.RATE in .75..1.5)
            assertTrue(held.rawPeak.isFinite() && held.rawPeak < 8.0)
            assertTrue(held.peakEnergy.isFinite() && held.peakEnergy < 100.0)
            assertTrue(held.stateError.isFinite() && held.stateError < 1e-5)
            assertTrue(held.noiseStateError.isFinite() && held.noiseStateError < 1e-9)
            assertTrue(held.cycleError.isFinite() && held.cycleError < 1e-5)
            assertTrue(held.seamError.isFinite() && held.seamError < Keys.MAX_SEAM_ERROR)
            assertTrue(cycleDifference(samples, start) < 1e-3,
                "$voice complete consecutive cycles differ despite a closed endpoint")
            assertTrue(rms(samples, (start - 256).toDouble() / Dsp.RATE,
                start.toDouble() / Dsp.RATE) > 1e-5, "$voice silent wrap made the seam vacuous")
            val patch = MeridianPatch("Held material", voice, macros, seedContext = SEED, model = 3)
            assertContentEquals(samples, patch.render().samples,
                "$voice generic rendering discarded its saved held version")
            val bytes = ByteArrayOutputStream().also {
                WavWriter.write(it, held.snip, WavWriter.BitDepth.PCM_24,
                    smpl = SmplChunk(57, SmplChunk.Loop(start.toLong(), held.loopEndFrameExclusive.toLong())))
            }.toByteArray()
            val decoded = WavReader.read(bytes)
            val sheet = assertNotNull(WavReader.readSmplStrict(bytes))
            assertEquals(57, sheet.rootNote)
            assertEquals(start.toLong(), sheet.loop?.startFrame)
            assertEquals(held.loopEndFrameExclusive.toLong(), sheet.loop?.endFrameExclusive)
            assertTrue(Keys.seamError(decoded.samples, start) < Keys.MAX_SEAM_ERROR,
                "$voice PCM24 encoding broke the independently evolved loop")
            val from = start.toFloat() / Dsp.RATE + .05f
            val estimate = assertNotNull(Pitch.detect(decoded, from, .35f))
            val cents = 1200 * ln(estimate.hz / 220.0) / ln(2.0)
            assertTrue(estimate.confidence >= .3f && abs(cents) < 50,
                "$voice held broad pitch: $cents cents / ${estimate.confidence}")
        }
    }

    @Test
    fun `low accumulation and highest bright note stay healthy before output leveling`() {
        for (voice in VOICES) for ((midi, controls) in listOf(
            36 to mapOf("TUNE" to 0f, "LATITUDE" to .5f, "LENGTH" to 0f, "REACH" to 1f,
                "SURFACE" to 1f, "MEMORY" to 1f),
            96 to mapOf("TUNE" to 1f, "REACH" to 1f, "SURFACE" to 1f, "MEMORY" to .8f),
        )) {
            val probe = Meridian.renderProbe(voice, controls, midi = midi, seedContext = SEED)
            assertTrue(probe.samples.all { it.isFinite() })
            assertTrue(probe.diagnostics.rawPeak.isFinite() && probe.diagnostics.rawPeak < 8.0,
                "$voice MIDI$midi raw output grew to ${probe.diagnostics.rawPeak}")
            assertTrue(probe.diagnostics.peakEnergy.isFinite() && probe.diagnostics.peakEnergy < 100.0,
                "$voice MIDI$midi internal modal energy grew to ${probe.diagnostics.peakEnergy}")
            val end = probe.samples.size.toDouble() / Dsp.RATE
            assertTrue(rms(probe.samples, end - .02, end) < rms(probe.samples, 0.0, end) * .005,
                "$voice MIDI$midi retained substantial state at the finite boundary")
            assertTrue(abs(probe.samples.average()) < .001)
            if (midi == 96) {
                assertTrue(probe.diagnostics.rootModalHz.any { it > 18000 }, "fixture missed suppressed modes")
                val bins = spectrum(probe.samples, .15f, .65f)
                assertTrue(bandShare(bins) { it > 18000 } < .001,
                    "$voice leaked substantial output above the modal fade")
            }
        }
    }

    private fun spectrum(samples: FloatArray, from: Float, duration: Float): DoubleArray {
        val n = 65536
        val offset = (from * Dsp.RATE).toInt()
        val count = minOf(n, (duration * Dsp.RATE).toInt(), samples.size - offset)
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until count) re[i] = (samples[offset + i] *
            (.5 - .5 * cos(2 * PI * i / (count - 1)))).toFloat()
        Fft.forward(re, im)
        val power = DoubleArray(n / 2) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        power[0] = 0.0
        val total = power.sum()
        assertTrue(total > 1e-12, "spectrum was silent")
        return DoubleArray(power.size) { power[it] / total }
    }

    private fun rootShare(samples: FloatArray, root: Double, from: Float, duration: Float): Double {
        val bins = spectrum(samples, from, duration)
        return bandShare(bins) { abs(it - root) < max(5.0, root * .04) }
    }

    private fun centroid(bins: DoubleArray): Double = bins.indices.sumOf { frequency(it, bins.size) * bins[it] }
    private fun frequency(bin: Int, size: Int): Double = bin.toDouble() * Dsp.RATE / (2 * size)
    private fun bandShare(bins: DoubleArray, include: (Double) -> Boolean): Double =
        bins.indices.filter { include(frequency(it, bins.size)) }.sumOf { bins[it] }

    private fun rms(samples: FloatArray, fromSeconds: Double, toSeconds: Double): Double {
        val from = (fromSeconds * Dsp.RATE).toInt().coerceIn(0, samples.size)
        val to = (toSeconds * Dsp.RATE).toInt().coerceIn(from, samples.size)
        var energy = 0.0
        for (i in from until to) energy += samples[i].toDouble() * samples[i]
        return sqrt(energy / (to - from).coerceAtLeast(1))
    }

    private fun cycleDifference(samples: FloatArray, start: Int): Double {
        var difference = 0.0
        var energy = 0.0
        // The first cycle alone has a 4 ms entry fade. The remainder is real continuation.
        for (i in 256 until start) {
            val previous = samples[i].toDouble()
            val next = samples[start + i].toDouble()
            difference += (previous - next) * (previous - next)
            energy += previous * previous
        }
        return sqrt(difference / energy.coerceAtLeast(1e-30))
    }

    private companion object {
        const val SEED = 4810
        val VOICES = listOf(MeridianVoice.CLACK, MeridianVoice.CHIME, MeridianVoice.RASP)
        val readings by lazy { VOICES.flatMap { voice -> listOf(36, 48, 57, 72).map { Reading(voice, it) } } }
    }
}
