package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
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
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Independent guards for the accepted audio and the app's editable, pitched sound contract. */
class RegistersIntegrationReviewTest {
    private data class PublishedCase(
        val id: String,
        val voice: RegistersVoice,
        val macros: Map<String, Float>,
        val velocity: Float,
        val frames: Int,
        val sha256: String,
        val held: Boolean = false,
    )

    private fun pcm24Hash(samples: FloatArray): String {
        val encoded = ByteArrayOutputStream()
        WavWriter.write(encoded, Snip(samples, channels = 1, sampleRate = Dsp.RATE), WavWriter.BitDepth.PCM_24)
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()))
    }

    @Test
    fun `integrated model three retains the published finite and held PCM`() {
        // Literal SHA-256 values from the independently verified, published r3-airflow pack:
        // audio fingerprint a79d2f2f9e35a7427bad6a6742aba88b8a7751797d5789bee4b9abe0540ea48f.
        // Every chosen source had rawExportGain = 1. No audition files are needed by this test.
        val cases = listOf(
            PublishedCase("pipe_velocity_100", RegistersVoice.PIPE, emptyMap(), 1f, 103423,
                "ad4921d465fcce7c6e9a1ae06cae02d9232bab9b604c518dd30d59233cf52004"),
            PublishedCase("musette_velocity_100", RegistersVoice.MUSETTE, emptyMap(), 1f, 103635,
                "1d14e120113508a0f623c76b00057d642253d5bfaee3256205a7891e4e4be07c"),
            PublishedCase("pipe_held_high", RegistersVoice.PIPE,
                mapOf("HOLD" to 1f, "SPREAD" to .9f, "MOTION" to .9f), .8f, 254865,
                "e32fc9e5888d450ed07fa28f33f476ad527d9b082edef1395d5a4612ae2197fa", held = true),
            PublishedCase("musette_held_high", RegistersVoice.MUSETTE,
                mapOf("HOLD" to 1f, "SPREAD" to .9f, "MOTION" to .9f), .8f, 254865,
                "84d499276e8495354a2bef5dae8d92f5a02b8abe4551d8a1f89d4951e40dc169", held = true),
        )
        assertEquals(3, Registers.MODEL_VERSION, "the app default is the approved airflow model")
        for (case in cases) {
            val patch = RegistersPatch(case.id, case.voice, case.macros, midi = 60, velocity = case.velocity, model = 3)
            val probe = patch.probe()
            assertEquals(case.held, probe.isLoop, case.id)
            assertEquals(if (case.held) DrumClass.LOOP else DrumClass.TONAL,
                Registers.drumClassFor(case.voice, case.macros), case.id)
            // The published held files contain three exact passes of the independently checked region.
            val playback = if (case.held) FloatArray(probe.samples.size * 3) { probe.samples[it % probe.samples.size] }
                else probe.samples
            assertEquals(case.frames, playback.size, case.id)
            assertEquals(case.sha256, pcm24Hash(playback), "${case.id}: integration changed accepted audio")
        }
    }

    private fun measuredPitch(samples: FloatArray, expectedHz: Double): Double {
        val size = 65536
        val count = 32768
        val from = Dsp.RATE / 3
        assertTrue(samples.size >= from + count)
        val re = FloatArray(size)
        val im = FloatArray(size)
        for (i in 0 until count) re[i] = samples[from + i] *
            (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        val binHz = Dsp.RATE.toDouble() / size
        val power = DoubleArray(size / 2) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        val low = (expectedHz * .97 / binHz).roundToInt().coerceAtLeast(1)
        val high = (expectedHz * 1.03 / binHz).roundToInt().coerceAtMost(power.lastIndex - 1)
        val bin = (low..high).maxBy { power[it] }
        val a = ln(power[bin - 1].coerceAtLeast(1e-30))
        val b = ln(power[bin].coerceAtLeast(1e-30))
        val c = ln(power[bin + 1].coerceAtLeast(1e-30))
        val offset = (.5 * (a - c) / (a - 2 * b + c)).coerceIn(-.5, .5)
        return (bin + offset) * binHz
    }

    @Test
    fun `moving TUNE after loading an explicit note changes the actual audio pitch`() {
        for (voice in RegistersVoice.entries) {
            // Original audition JSON could save any explicit MIDI alongside TUNE .5.
            val imported = RegistersPatch("Saved A4", voice,
                mapOf("TUNE" to .5f, "SPREAD" to 0f, "MOTION" to 0f), midi = 69, velocity = .7f, model = 3)
            val editable = imported.forEditing()
            assertEquals(69, editable.midi)
            for ((tune, midi) in listOf(0f to 48, 1f to 72)) {
                val edited = Patches.edited(editable, "Moved pitch", editable.macros + ("TUNE" to tune)) as RegistersPatch
                assertEquals(midi, edited.midi)
                assertEquals(imported.velocity, edited.velocity)
                assertEquals(imported.model, edited.model)
                val expectedHz = 440.0 * 2.0.pow((midi - 69) / 12.0)
                val actualHz = measuredPitch(edited.render().samples, expectedHz)
                val cents = abs(1200 * ln(actualHz / expectedHz) / ln(2.0))
                assertTrue(cents < 2.0, "$voice TUNE $tune stayed at the saved A4 or missed MIDI $midi: $cents cents")
            }
        }
    }

    @Test
    fun `scramble stays bounded and finite when its starting recipe is held`() {
        for (voice in RegistersVoice.entries) {
            val held = RegistersPatch("Held A4", voice, mapOf("TUNE" to .5f, "HOLD" to 1f), midi = 69,
                velocity = .6f, model = 3)
            for (temperature in listOf(-1f, 0f, .35f, 1f, 2f, Float.NaN, Float.POSITIVE_INFINITY)) {
                val macros = Registers.scramble(voice, Random(37), temperature, held)
                assertEquals(Registers.defaults(voice).keys, macros.keys)
                assertTrue(macros.values.all { it.isFinite() && it in 0f..1f }, "$voice temperature $temperature")
                assertEquals(0f, macros.getValue("HOLD"), "SCRAMBLE must retain an authored finite onset")
                assertEquals(DrumClass.TONAL, Registers.drumClassFor(voice, macros))
                val patch = held.forEditing().withMacros(macros)
                assertEquals(Registers.midiFor(macros.getValue("TUNE")), patch.midi,
                    "a scrambled TUNE must remain live after a preset was loaded")
                if (temperature == 0f || temperature == 1f) {
                    val probe = patch.probe()
                    assertFalse(probe.isLoop)
                    assertTrue(probe.samples.all { it.isFinite() })
                    assertTrue(abs(probe.samples.last()) < 1e-6f,
                        "the finite scrambled note must finish near silence: ${probe.samples.last()}")
                }
            }
        }
    }
}
