package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Low-range ensemble and actual keygroup handoff checks for the model-6 sustain revision. */
class RegistersTransistorSustainTest {
    private val voice = RegistersVoice.TRANSISTOR

    private fun rms(samples: FloatArray, from: Int = 0, until: Int = samples.size): Double =
        sqrt((from until until).sumOf { samples[it].toDouble() * samples[it] } / (until - from))

    private fun normalizedDifference(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size)
        val aRms = rms(a)
        val bRms = rms(b)
        return sqrt(a.indices.sumOf {
            val d = a[it] / aRms - b[it] / bRms
            d * d
        } / a.size)
    }

    private fun pcm24Hash(samples: FloatArray): String {
        val encoded = ByteArrayOutputStream()
        WavWriter.write(encoded, Snip(samples, channels = 1, sampleRate = Dsp.RATE), WavWriter.BitDepth.PCM_24)
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()))
    }

    @Test
    fun `accepted model five finite and held recordings retain their published PCM`() {
        // Literal SHA-256 values from the published r5-transistor-depth pack, captured
        // before model-6 edits. Both recordings had rawExportGain=1; no pack is needed here.
        val finite = Registers.probe(voice, midi = 60, model = 5)
        assertEquals(101518, finite.samples.size)
        assertEquals("380d1dd8ff3acbfcc68b2f724c652f1ea8a22c70500792aea513ef14d4837747", pcm24Hash(finite.samples))
        val held = Registers.probe(voice, mapOf("HOLD" to 1f, "SPREAD" to .9f, "MOTION" to .9f),
            midi = 60, velocity = .8f, model = 5)
        val playback = FloatArray(held.samples.size * 3) { held.samples[it % held.samples.size] }
        assertEquals(254865, playback.size)
        assertEquals("173a6d127c0c37dcaedda012e4bca1292f4cd1695f7d74d0016e24d7538b6215", pcm24Hash(playback))
    }

    @Test
    fun `low spread moves the normalized held waveform without extending the loop`() {
        val held = mapOf("HOLD" to 1f, "MOTION" to 0f)
        for (midi in listOf(48, 60, 72)) {
            val unison = Registers.probe(voice, held + ("SPREAD" to 0f), midi, model = 6)
            var lastDifference = 0.0
            for (spread in listOf(.05f, .12f, .18f)) {
                val moving = Registers.probe(voice, held + ("SPREAD" to spread), midi, model = 6)
                val difference = normalizedDifference(unison.samples, moving.samples)
                // The measured minimum across these cases is .000919. This lower guard
                // rejects the old exact-unison dead zone without treating gain as movement.
                assertTrue(difference > .0001, "MIDI$midi SPREAD$spread lost its ensemble: $difference")
                assertTrue(difference > lastDifference, "MIDI$midi low-range ensemble should grow with SPREAD")
                lastDifference = difference
                assertEquals(unison.samples.size, moving.samples.size)
                assertTrue(moving.samples.size.toDouble() / Dsp.RATE < 2.1,
                    "subtle ensemble must not require a long beat-period sample")
                assertTrue(moving.effectiveSpreadHz > 0.0)
                assertEquals(Dsp.RATE.toDouble() / moving.samples.size, moving.spreadModulationHz, 1e-12)
                assertFalse(requireNotNull(moving.previousCycle) === moving.samples)
                assertTrue(requireNotNull(moving.cycleError) < 1e-5)
                assertTrue(requireNotNull(moving.seamError) < Keys.MAX_SEAM_ERROR)
            }
            val old = Registers.probe(voice, held + ("SPREAD" to .12f), midi, model = 5)
            assertEquals(0.0, old.effectiveSpreadHz, "the accepted model must retain its old loop fitting")
            assertEquals(0.0, old.spreadModulationHz)
        }
    }

    @Test
    fun `finite notes retain the accepted foundation and exact initial keyer`() {
        val clickFrames = (.012 * Dsp.RATE).roundToInt()
        for (midi in listOf(48, 60, 72)) {
            val accepted = Registers.probe(voice, midi = midi, model = 5)
            val current = Registers.probe(voice, midi = midi, model = 6)
            assertContentEquals(accepted.samples.copyOfRange(0, clickFrames),
                current.samples.copyOfRange(0, clickFrames), "MIDI$midi initial keyer changed")
            assertTrue(normalizedDifference(accepted.samples, current.samples) < 1e-6,
                "finite model-6 optimization changed the accepted foundation")
            assertEquals(accepted.effectiveSpreadHz, current.effectiveSpreadHz)
            assertEquals(0.0, current.spreadModulationHz, "finite notes retain constant detuning")
        }
    }

    @Test
    fun `factory keygroups retain their attack and avoid cancellation through the handoff`() {
        val join = (.350 * Dsp.RATE).roundToInt()
        val overlapEnd = (.450 * Dsp.RATE).roundToInt()
        val window = (.050 * Dsp.RATE).roundToInt()
        for (factory in RegistersPresets.forVoice(voice).filter { it.name != "Clean Combo" }) {
            val patch = factory.copy(model = 6)
            for (midi in listOf(48, 60, 72)) {
                val finite = Registers.render(voice, patch.macros, midi, patch.velocity, model = 6).samples
                val key = Keys.registersPad(patch, midi)
                val samples = key.snip.samples
                assertContentEquals(finite.copyOfRange(0, join), samples.copyOfRange(0, join),
                    "${patch.name} MIDI$midi lost its actual finite attack")
                val before = rms(samples, join - 2 * window, join)
                val after = rms(samples, overlapEnd, overlapEnd + 2 * window)
                val reference = minOf(before, after)
                // Fresh Kotlin probes measured .9975 or better. A 20% allowance leaves
                // room for normal ensemble variation but rejects a cancelling phase join.
                for (from in join - window..overlapEnd step (Dsp.RATE / 100)) {
                    assertTrue(rms(samples, from, from + window) > .8 * reference,
                        "${patch.name} MIDI$midi handoff cancelled near frame$from")
                }
                assertTrue(key.loopStartFrame > overlapEnd)
                assertTrue(Keys.seamError(samples, key.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
            }
        }
    }

    @Test
    fun `periodic ensemble is deterministic and silent velocity remains truly silent`() {
        val macros = mapOf("HOLD" to 1f, "SPREAD" to .12f)
        val first = Registers.probe(voice, macros, 60, model = 6)
        assertContentEquals(first.samples, Registers.probe(voice, macros, 60, model = 6).samples)
        val silent = Registers.probe(voice, macros, 60, velocity = 0f, model = 6)
        assertTrue(silent.samples.all { it == 0f })
        assertEquals(0.0, requireNotNull(silent.cycleError))
        assertEquals(0.0, requireNotNull(silent.seamError))
        assertTrue(first.samples.all { it.isFinite() && abs(it) <= Registers.RAW_PEAK_CEILING })
    }
}
